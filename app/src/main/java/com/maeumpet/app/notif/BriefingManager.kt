package com.maeumpet.app.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.maeumpet.app.R
import com.maeumpet.app.personal.Prefs
import com.maeumpet.app.ui.MainActivity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One serialized delivery path for automatic briefings. */
object BriefingManager {
    private const val CHANNEL = "briefing"
    private const val NOTIF_ID = 1001
    private val deliveryMutex = Mutex()

    suspend fun maybeDeliver(context: Context, trigger: String, awayAt: Long = 0L) = deliveryMutex.withLock {
        val prefs = Prefs(context)
        if (!prefs.briefingEnabled) return@withLock
        val now = System.currentTimeMillis()
        val since = AwayDigest.returnStart(awayAt, prefs.lastBriefingAt, now) ?: return@withLock
        val briefing = build(context, since, now) ?: return@withLock
        // Do not consume news when notification permission/channel is disabled.
        if (!deliver(context, briefing.text)) return@withLock
        val db = NotifDatabase.get(context)
        val id = db.briefings().insert(briefing.copy(delivered = true))
        prefs.lastBriefingAt = now
        prefs.pendingHomePopupId = id
        Log.d("MaeumPet.Brief", "delivered: $trigger")
    }

    suspend fun build(context: Context, since: Long, until: Long): Briefing? {
        val rows = NotifDatabase.get(context).notifs().between(since, until)
        val conversations = AwayDigest.conversations(rows, since, until)
        val other = rows.groupBy { it.key }.values.map { it.maxBy { n -> n.postedAt } }
            .filter { !it.clicked && it.kind !in setOf(NotifRules.MESSAGE, NotifRules.EMAIL) &&
                BriefingPolicy.eligible(it.kind, "${it.title.orEmpty()} ${it.lastMessageText().orEmpty()}", it.importance, ImportanceLearner.needsReply(it.features), it.kevTimely?.let { t -> t >= ImportanceLearner.NOTIF_YES }) }
            .sortedByDescending { it.importance }
        if (conversations.isEmpty() && other.isEmpty()) return null
        val text = buildString {
            append("돌아왔구나! 그동안 온 소식 정리해 뒀어.")
            val chats = conversations.count { it.item.kind == NotifRules.MESSAGE }
            val mail = conversations.count { it.item.kind == NotifRules.EMAIL }
            append("\n대화 ${chats}곳 · 메일 알림 ${mail}묶음")
            val nano = NanoSummarizer(context)
            for (c in conversations.take(6)) {
                val label = if (c.item.kind == NotifRules.EMAIL) "메일" else c.item.appLabel
                append("\n\n• ${c.name} · $label")
                val lines = c.messages.takeLast(12).map { "${it.sender.ifBlank { c.name }}: ${it.text.take(500)}" }
                val summary = if (lines.size >= 3) nano.summarizeConversation(lines) else null
                if (summary != null) {
                    append("\nAI 요약: $summary")
                    append("\n마지막 원문: ${c.messages.last().text.replace('\n', ' ').take(120)}")
                } else append("\n핵심 요약: ${NotificationSummary.summarize(c.messages.map { it.text })}")
                if (c.needsAttention) append("\n질문·요청 또는 일정 표현이 있어. 확인해 봐.")
            }
            if (conversations.size > 6) append("\n\n다른 소식 ${conversations.size - 6}묶음은 ‘받은 알림’에서 볼 수 있어.")
            for (n in other.take(3)) append("\n\n• ${n.appLabel}: ${NotificationSummary.summarize(n)}")
            if (other.size > 3) append("\n기타 주요 알림 ${other.size - 3}개가 더 있어.")
            append("\n\n알림에 보인 내용 기준이야. 실제 읽음·답장 여부와 전체 대화는 알 수 없어.")
        }
        return Briefing(createdAt = until, fromTs = since, toTs = until,
            total = conversations.size + other.size, text = text)
    }

    fun deliver(context: Context, text: String): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "펫의 브리핑", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "15분 이상 화면을 끈 뒤 잠금을 풀면 그동안 온 소식을 알려 줍니다"
        })
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled() ||
            nm.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notif = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_pet_small)
            .setContentTitle(":) 펫이 모아 둔 소식")
            .setContentText(text.lineSequence().drop(1).firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open).setAutoCancel(true).build()
        return runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, notif); true }
            .getOrElse { Log.w("MaeumPet.Brief", "notification delivery failed", it); false }
    }
}

fun NotifItem.lastMessageText(): String? = MessageCodec.decode(messages).lastOrNull()?.text ?: text
