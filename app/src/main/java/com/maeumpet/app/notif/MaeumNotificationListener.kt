package com.maeumpet.app.notif

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.room.withTransaction
import com.maeumpet.app.personal.Prefs
import com.maeumpet.app.plan.PlanManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 폰에 뜨는 알림을 읽어 폰 안에만 저장한다.
 * 인증번호·OTP, 진행 중(음악/다운로드) 알림, 우리 앱 알림은 저장하지 않는다.
 * 알림이 사라질 때 "눌러서 열었는지 / 밀어서 지웠는지"를 함께 기록해 중요도 학습에 쓴다.
 */
class MaeumNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: Prefs
    private var receiverRegistered = false

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> if (prefs.screenOffAt == 0L) prefs.screenOffAt = System.currentTimeMillis()
                Intent.ACTION_USER_PRESENT -> {
                    val awayAt = prefs.screenOffAt
                    prefs.screenOffAt = 0L
                    scope.launch { BriefingManager.maybeDeliver(applicationContext, trigger = "unlock", awayAt = awayAt) }
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        prefs = Prefs(this)
        connected = true
        if (!receiverRegistered) runCatching { registerReceiver(unlockReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }) }
            .onSuccess { receiverRegistered = true }
        NightWorker.schedule(this)
        KevRescore.startIfNeeded(this)
        Log.d(TAG, "listener connected")
    }

    override fun onListenerDisconnected() {
        connected = false
        if (receiverRegistered) runCatching { unregisterReceiver(unlockReceiver) }
        receiverRegistered = false
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        connected = false
        if (receiverRegistered) runCatching { unregisterReceiver(unlockReceiver) }
        receiverRegistered = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!::prefs.isInitialized) prefs = Prefs(this)
        if (!prefs.briefingEnabled) return
        val item = parse(sbn) ?: return
        scope.launch {
            val db = NotifDatabase.get(applicationContext)
            // 미뤄 둔 알림이 시간이 돼서 다시 올라온 것: 원래 기록을 되살리고, 다시 판단·저장·미루기를 하지 않는다
            if (db.notifs().reactivateDeferred(sbn.key, item.title, item.text) > 0 ||
                db.notifs().countReturnedActive(sbn.key, System.currentTimeMillis() + 60_000, item.title, item.text) > 0) {
                Log.d(TAG, "returned ${item.appLabel}: ${item.title}")
                return@launch
            }
            val stat = db.senderStats().get(item.senderKey)
            val scored = NotifScorer.score(applicationContext, item, item.kind, stat)
            val id = db.withTransaction {
                val inserted = db.notifs().insert(scored)
                if (inserted > 0) ExpenseExtractor.record(scored)?.let { db.expenses().insert(it) }
                if (inserted > 0) TodoExtractor.candidates(scored).forEach { db.todos().insert(it) }
                inserted
            }
            if (id > 0 && prefs.autoClearEnabled) tidy(db, id, sbn, scored, stat)
            if (id > 0 && item.kind == NotifRules.MESSAGE) {
                PlanManager.addCandidate(applicationContext, item.sender ?: item.title ?: item.appLabel, item.lastMessageText() ?: "", "notif")
            }
            if (id > 0) {
                db.senderStats().upsert(
                    SenderStat(
                        key = item.senderKey,
                        count = (stat?.count ?: 0) + 1,
                        clicks = stat?.clicks ?: 0,
                        dismisses = stat?.dismisses ?: 0,
                        lastAt = item.postedAt,
                    ),
                )
                Log.d(TAG, "saved [${scored.kind} ${scored.importance}] q=${scored.isQuestion} promo=${scored.kevPromo?.let { "%.2f".format(it) } ?: "-"} timely=${scored.kevTimely?.let { "%.2f".format(it) } ?: "-"} ${item.appLabel}: ${item.sender ?: item.title} / ${item.text?.take(30)}")
            }
        }
    }

    /**
     * 확실한 광고·유도는 알림창에서 치우고, 덜 급한 소식은 다음 모아 보기 시각까지 미룬다(시스템 snooze라 그 시각에
     * 원래 알림이 그대로 다시 나타난다). 기록은 남겨 "치운 알림"에서 다시 볼 수 있다.
     */
    private suspend fun tidy(db: NotifDatabase, id: Long, sbn: StatusBarNotification, n: NotifItem, stat: SenderStat?) {
        val now = System.currentTimeMillis()
        val opensOften = stat != null && stat.clicks >= 3 && stat.clickRate >= 0.6f
        val action = AutoClear.decide(n, prefs.clearExemptPackages, opensOften)
        when (action) {
            AutoClear.Action.CLEAR -> {
                db.notifs().setCleared(id, now)
                runCatching { cancelNotification(sbn.key) }.onFailure { Log.w(TAG, "clear failed", it) }
                Log.d(TAG, "cleared ${n.appLabel}: ${n.title}")
            }
            AutoClear.Action.DEFER -> if (prefs.autoDeferEnabled) {
                val until = DeferSchedule.next(now)
                runCatching { snoozeNotification(sbn.key, until - now) }
                    .onSuccess { db.notifs().setDeferred(id, until); Log.d(TAG, "deferred to ${DeferSchedule.label(until, now)} ${n.appLabel}: ${n.title}") }
                    .onFailure { Log.w(TAG, "defer failed", it) }
            }
            AutoClear.Action.KEEP -> Unit
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        if (sbn.packageName == packageName) return
        scope.launch {
            val db = NotifDatabase.get(applicationContext)
            val latest = db.notifs().latestActive(sbn.key) ?: return@launch
            db.notifs().markRemoved(sbn.key, System.currentTimeMillis(), reason)
            if (reason == REASON_CLICK || reason == REASON_CANCEL || reason == REASON_CANCEL_ALL || reason == REASON_GROUP_SUMMARY_CANCELED) {
                val stat = db.senderStats().get(latest.senderKey) ?: return@launch
                db.senderStats().upsert(
                    stat.copy(
                        clicks = stat.clicks + if (reason == REASON_CLICK) 1 else 0,
                        dismisses = stat.dismisses + if (reason != REASON_CLICK) 1 else 0,
                    ),
                )
            }
            Log.d(TAG, "removed reason=$reason ${latest.appLabel}: ${latest.sender ?: latest.title}")
        }
    }

    // ---- 파싱 ----

    private fun parse(sbn: StatusBarNotification): NotifItem? {
        val n = sbn.notification ?: return null
        val pkg = sbn.packageName
        if (pkg == packageName || pkg == "android" || pkg == "com.android.systemui") return null
        if (pkg in prefs.excludedNotifPackages) return null
        if (sbn.isOngoing) return null
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val extras = n.extras ?: return null
        if (extras.containsKey(Notification.EXTRA_PROGRESS_MAX) && extras.getInt(Notification.EXTRA_PROGRESS_MAX) > 0) return null

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
        val text = (bigText?.takeIf { it.isNotEmpty() } ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim())
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n") { it.toString() }

        var sender: String? = null
        var conversationTitle: String? = null
        var isGroup = false
        var messages: String? = null
        val style = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }.getOrNull()
        if (style != null && style.messages.isNotEmpty()) {
            conversationTitle = style.conversationTitle?.toString()
            isGroup = style.isGroupConversation
            val last = style.messages.last()
            sender = last.person?.name?.toString() ?: title
            messages = MessageCodec.encode(style.messages.takeLast(10).map { m ->
                MessageCodec.Message(m.person?.name?.toString().orEmpty(), m.text?.toString().orEmpty(), m.timestamp)
            })
        }

        val lastFromMessages = MessageCodec.decode(messages).lastOrNull()?.text
        val body = text ?: lines ?: lastFromMessages ?: return null
        val all = "${title.orEmpty()} $body"
        if (all.isBlank()) return null
        if (NotifRules.REDACTED.containsMatchIn(body)) return null
        if (NotifRules.SECRET.containsMatchIn(all) || MessageCodec.decode(messages).any { NotifRules.SECRET.containsMatchIn(it.text) }) return null

        val kind = NotifRules.kind(pkg, n.category, title, body, messages != null)
        val lastText = lastFromMessages ?: body
        return NotifItem(
            key = sbn.key,
            packageName = pkg,
            appLabel = appLabel(pkg),
            postedAt = sbn.postTime,
            category = n.category,
            title = title,
            text = body.take(500),
            sender = sender,
            conversationTitle = conversationTitle,
            isGroupConversation = isGroup,
            messages = messages,
            kind = kind,
            amount = NotifRules.amount(all),
            isQuestion = kind == NotifRules.MESSAGE && NotifRules.isQuestion(lastText),
            importance = 0,
        )
    }

    private fun appLabel(pkg: String): String = runCatching {
        val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
            packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(pkg, 0)
        }
        packageManager.getApplicationLabel(info).toString()
    }.getOrElse { pkg.substringAfterLast('.') }

    companion object {
        private const val TAG = "MaeumPet.Notif"

        @Volatile
        var connected = false
            private set
    }
}
