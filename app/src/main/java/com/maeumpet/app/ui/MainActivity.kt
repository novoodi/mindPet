package com.maeumpet.app.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.maeumpet.app.R
import com.maeumpet.app.databinding.ActivityMainBinding
import com.maeumpet.app.databinding.ItemPlanCandidateBinding
import com.maeumpet.app.databinding.ItemTileBinding
import com.maeumpet.app.notif.DeferSchedule
import com.maeumpet.app.notif.ExpenseExtractor
import com.maeumpet.app.notif.KevRescore
import com.maeumpet.app.notif.NightWorker
import com.maeumpet.app.notif.NotifDatabase
import com.maeumpet.app.notif.NotifItem
import com.maeumpet.app.notif.PetBriefing
import com.maeumpet.app.notif.Plan
import com.maeumpet.app.notif.TodoExtractor
import com.maeumpet.app.plan.PlanManager
import com.maeumpet.app.service.MaeumAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** 첫 화면: 펫 얼굴, 한마디, 답장·일정·할 일 3칸. 칸을 누르면 아래 목록이 바뀐다. */
class MainActivity : AppCompatActivity() {

    private enum class Tab { REPLIES, PLANS, TASKS }

    private lateinit var b: ActivityMainBinding
    private var content = PetBriefing.Content(emptyList(), emptyList(), emptyList())
    private var candidates = emptyList<Plan>()
    private var suggestions = 0
    private var selected: Tab? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        selected = savedInstanceState?.getString("tab")?.let(Tab::valueOf)
        b.tileReplies.label.text = "답장"
        b.tilePlans.label.text = "일정"
        b.tileTasks.label.text = "할 일"
        b.tileReplies.root.setOnClickListener { select(Tab.REPLIES) }
        b.tilePlans.root.setOnClickListener { select(Tab.PLANS) }
        b.tileTasks.root.setOnClickListener { select(Tab.TASKS) }
        b.btnNotifs.setOnClickListener { startActivity(Intent(this, NotificationsActivity::class.java)) }
        b.btnExpenses.setOnClickListener { startActivity(Intent(this, ExpensesActivity::class.java)) }
        b.tvCleared.setOnClickListener { startActivity(Intent(this, ClearedActivity::class.java)) }
        b.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        watch()
        backfill()
        NightWorker.schedule(this)
        KevRescore.startIfNeeded(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        selected?.let { outState.putString("tab", it.name) }
        super.onSaveInstanceState(outState)
    }

    // ---- 데이터 ----

    /** 펫 카드(PetOverlay)와 같은 기준으로 세되, 개수 제한 없이. 시간 창은 1분마다 다시 잡는다. */
    private fun watch() {
        val db = NotifDatabase.get(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                db.notifs().observeTidied(startOfDay).collect { rows ->
                    b.tvCleared.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
                    b.tvCleared.text = tidiedLine(rows, System.currentTimeMillis())
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    val now = System.currentTimeMillis()
                    val zone = ZoneId.systemDefault()
                    val today = LocalDate.now(zone)
                    val endOfTomorrow = today.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
                    val observer = launch {
                        combine(
                            db.notifs().observeDay(now - PetBriefing.REPLY_WINDOW_MS, Long.MAX_VALUE),
                            db.plans().observeDay(now - 3600_000L, endOfTomorrow),
                            db.plans().observeCandidates(),
                            db.todos().observeAll(),
                        ) { notifs, plans, pending, todos ->
                            val c = PetBriefing.content(notifs, todos, plans, System.currentTimeMillis(),
                                today.toEpochDay(), endOfTomorrow, limit = Int.MAX_VALUE)
                            Triple(c, pending.filter(PlanManager::isRelevant), todos.count { it.status == "CANDIDATE" })
                        }.collect { (c, pending, s) ->
                            content = c
                            candidates = pending
                            suggestions = s
                            render()
                        }
                    }
                    delay(60_000L)
                    observer.cancel()
                }
            }
        }
    }

    /** 예전에 저장된 알림에서 할 일 추천과 지출을 한 번 더 찾아 둔다(이미 있는 건 DB가 무시). */
    private fun backfill() {
        val db = NotifDatabase.get(this)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    db.notifs().since(0).forEach { item ->
                        TodoExtractor.candidates(item).forEach { db.todos().insert(it) }
                        ExpenseExtractor.record(item)?.let { db.expenses().insert(it) }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { Toast.makeText(this@MainActivity, "이전 알림을 가져오지 못했어. 앱을 다시 열어 줘.", Toast.LENGTH_LONG).show() }
        }
    }

    // ---- 그리기 ----

    private fun render() {
        val replies = content.replies.size
        val plans = content.plans.size + candidates.size
        val tasks = content.tasks.size
        val busy = replies + plans + tasks > 0
        b.tvFace.text = if (busy) ":o" else ":)"
        b.tvHeadline.text = if (content.count == 0 && candidates.isNotEmpty()) "확인할 약속이 있어." else PetBriefing.headline(content)
        b.tileReplies.count.text = replies.toString()
        b.tilePlans.count.text = plans.toString()
        b.tileTasks.count.text = tasks.toString()
        val tab = selected ?: when {
            replies > 0 -> Tab.REPLIES
            plans > 0 -> Tab.PLANS
            tasks > 0 -> Tab.TASKS
            else -> Tab.REPLIES
        }
        mark(b.tileReplies, tab == Tab.REPLIES)
        mark(b.tilePlans, tab == Tab.PLANS)
        mark(b.tileTasks, tab == Tab.TASKS)
        b.list.removeAllViews()
        when (tab) {
            Tab.REPLIES -> renderReplies()
            Tab.PLANS -> renderPlans()
            Tab.TASKS -> renderTasks()
        }
    }

    private fun select(tab: Tab) {
        selected = tab
        render()
    }

    private fun mark(tile: ItemTileBinding, on: Boolean) {
        tile.root.setBackgroundResource(if (on) R.drawable.bg_card_selected else R.drawable.bg_card)
        tile.count.setTextColor(color(if (on) R.color.primary else R.color.text))
    }

    private fun renderReplies() {
        if (content.replies.isEmpty()) return empty("답장 기다리는 메시지가 없어.")
        content.replies.forEach { r ->
            val ago = DateUtils.getRelativeTimeSpanString(r.postedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            row("${r.who} · ${r.appLabel} · $ago", r.text) {
                val launch = packageManager.getLaunchIntentForPackage(r.packageName)
                if (launch == null || runCatching { startActivity(launch) }.isFailure)
                    Toast.makeText(this, "${r.appLabel}을(를) 열 수 없어.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderPlans() {
        if (content.plans.isEmpty() && candidates.isEmpty()) return empty("오늘·내일 일정이 없어.")
        content.plans.forEach { p -> row(PlanManager.describe(p), p.text.takeIf { it.isNotBlank() }, null) }
        for (p in candidates) {
            val item = ItemPlanCandidateBinding.inflate(layoutInflater, b.list, false)
            val what = when (p.type) { "CANCEL" -> "취소되는 거야?"; "CHANGE" -> "바뀌는 거야?"; else -> "약속 맞아?" }
            item.question.text = "${PlanManager.describe(p)} · $what"
            item.quote.text = "\"${p.text.take(60)}\""
            item.btnYes.setOnClickListener { lifecycleScope.launch { PlanManager.confirm(this@MainActivity, p.id) } }
            item.btnNo.setOnClickListener { lifecycleScope.launch { PlanManager.deny(this@MainActivity, p.id) } }
            b.list.addView(item.root)
        }
    }

    private fun renderTasks() {
        val today = LocalDate.now().toEpochDay()
        if (content.tasks.isEmpty()) empty("남은 할 일이 없어.")
        content.tasks.forEach { t ->
            val due = when (val day = t.dueDay) {
                null -> null
                in Long.MIN_VALUE until today -> "기한 지남"
                today -> "오늘까지"
                today + 1 -> "내일까지"
                else -> LocalDate.ofEpochDay(day).let { "${it.monthValue}/${it.dayOfMonth}까지" }
            }
            row(t.title, due) { startActivity(Intent(this, TodosActivity::class.java)) }
        }
        if (suggestions > 0) link("알림에서 찾은 할 일 ${suggestions}개 확인하기") {
            startActivity(Intent(this, TodosActivity::class.java).putExtra("candidates", true))
        }
    }

    private fun row(title: String, detail: String?, onClick: (() -> Unit)?) {
        b.list.addView(TextView(this).apply {
            text = if (detail.isNullOrBlank()) title else "$title\n$detail"
            textSize = 14f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(color(R.color.text))
            setPadding(0, dp(8), 0, dp(8))
            if (onClick != null) {
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { onClick() }
            }
        })
    }

    private fun empty(text: String) {
        b.list.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, dp(8), 0, dp(8))
        })
    }

    private fun link(text: String, onClick: () -> Unit) {
        b.list.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.primary))
            setPadding(0, dp(10), 0, dp(4))
            setOnClickListener { onClick() }
        })
    }

    /** "광고 2개 치웠고, 덜 급한 알림 3개는 저녁 7시에 다시 보여 줄게 ›" */
    private fun tidiedLine(rows: List<NotifItem>, now: Long): String {
        val cleared = rows.count { it.clearedAt != null }
        val waiting = rows.mapNotNull { it.deferredUntil }.filter { it > now }
        val returned = rows.count { it.deferredUntil != null && it.deferredUntil <= now }
        val parts = listOfNotNull(
            "광고 ${cleared}개 치웠어".takeIf { cleared > 0 },
            waiting.takeIf { it.isNotEmpty() }?.let { "덜 급한 알림 ${it.size}개는 ${DeferSchedule.label(it.min(), now)}에 보여 줄게" },
            "미뤄 둔 알림 ${returned}개를 다시 띄웠어".takeIf { returned > 0 && waiting.isEmpty() },
        )
        return "오늘 " + parts.joinToString(", ") + " ›"
    }

    // ---- 처음 설정 ----

    /** 알림 접근 → 접근성 순서로, 빠진 것 하나만 보여 준다. */
    private fun refreshSetup() {
        val listening = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        when {
            !listening -> setup("알림 접근이 꺼져 있어. 켜 주면 답장할 메시지와 할 일을 모아 둘게.", "알림 접근 켜기",
                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            !isServiceEnabled(this) -> setup("접근성에서 '마음펫'을 켜 주면 홈 화면에 펫이 나타나.", "접근성에서 마음펫 켜기",
                Settings.ACTION_ACCESSIBILITY_SETTINGS)
            else -> b.cardSetup.visibility = View.GONE
        }
    }

    private fun setup(text: String, button: String, action: String) {
        b.cardSetup.visibility = View.VISIBLE
        b.tvSetup.text = text
        b.btnSetup.text = button
        b.btnSetup.setOnClickListener { startActivity(Intent(action)) }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun color(res: Int) = ContextCompat.getColor(this, res)

    companion object {
        fun isServiceEnabled(context: android.content.Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val mine = ComponentName(context, MaeumAccessibilityService::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == mine } || MaeumAccessibilityService.running
        }
    }
}
