package com.maeumpet.app.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.maeumpet.app.R
import com.maeumpet.app.databinding.OverlayPetBinding
import com.maeumpet.app.notif.NotifDatabase
import com.maeumpet.app.notif.PetBriefing
import com.maeumpet.app.personal.Prefs
import com.maeumpet.app.plan.PlanManager
import com.maeumpet.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * 홈 화면 위에 떠 있는 작은 텍스트 얼굴. 누르면 챙길 것(답장할 메시지, 잊으면 안 되는 일, 다가오는 일정)을 보여 준다.
 * 챙길 게 있으면 얼굴이 바뀌고 개수 배지가 뜬다. 접근성 서비스가 띄우는 창이라 "다른 앱 위에 표시" 권한이 필요 없다.
 */
class PetOverlay(private val service: AccessibilityService, private val onExpanded: () -> Unit) {

    private val prefs = Prefs(service)
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ctx = ContextThemeWrapper(service, R.style.Theme_MaeumPet)
    private val binding = OverlayPetBinding.inflate(LayoutInflater.from(ctx))
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = prefs.overlayX
        y = prefs.overlayY
    }

    private var attached = false
    private var expanded = false
    private var content = PetBriefing.Content(emptyList(), emptyList(), emptyList())
    private var watcher: Job? = null

    init {
        setupTouch()
        binding.btnClose.setOnClickListener { collapse() }
        binding.btnMore.setOnClickListener {
            collapse()
            open(Intent(service, MainActivity::class.java))
        }
        render()
        scheduleBlink()
    }

    // ---- 표시/숨김 ----

    fun show() {
        if (attached) return
        runCatching { wm.addView(binding.root, params) }.onSuccess {
            attached = true
            watch()
        }
    }

    fun hide() {
        if (!attached) return
        collapse()
        watcher?.cancel()
        runCatching { wm.removeView(binding.root) }
        attached = false
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        hide()
        scope.cancel()
    }

    // ---- 데이터 ----

    /** 보이는 동안 DB를 지켜보며 배지와 카드를 갱신한다. 시간 창(24시간, 내일 끝)은 1분마다 다시 잡는다. */
    private fun watch() {
        watcher?.cancel()
        val db = NotifDatabase.get(service)
        watcher = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                val endOfTomorrow = today.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
                val observer = launch {
                    combine(
                        db.notifs().observeDay(now - PetBriefing.REPLY_WINDOW_MS, Long.MAX_VALUE),
                        db.plans().observeDay(now - 3600_000L, endOfTomorrow),
                        db.todos().observeAll(),
                    ) { notifs, plans, todos ->
                        PetBriefing.content(notifs, todos, plans, System.currentTimeMillis(), today.toEpochDay(), endOfTomorrow)
                    }.collect {
                        content = it
                        render()
                    }
                }
                delay(60_000L)
                observer.cancel()
            }
        }
    }

    // ---- 그리기 ----

    private fun render() {
        val n = content.count
        binding.face.text = if (n > 0) FACE_ALERT else FACE_IDLE
        binding.bubble.backgroundTintList = ColorStateList.valueOf(color(if (n > 0) R.color.pet_alert else R.color.pet_idle))
        binding.badge.text = if (n > 9) "9+" else n.toString()
        binding.badge.visibility = if (n > 0) View.VISIBLE else View.GONE
        if (expanded) renderCard()
    }

    private fun renderCard() {
        binding.line.text = PetBriefing.headline(content)
        val sections = binding.sections
        sections.removeAllViews()
        if (content.replies.isNotEmpty()) {
            sections.addView(header("답장할 메시지"))
            content.replies.forEach { r ->
                sections.addView(item("${r.who} · ${r.appLabel}", r.text) {
                    collapse()
                    val launch = service.packageManager.getLaunchIntentForPackage(r.packageName)
                    if (launch == null || !open(launch)) showBubbleLine("${r.appLabel}을(를) 열 수 없어.")
                })
            }
        }
        if (content.tasks.isNotEmpty()) {
            sections.addView(header("잊으면 안 되는 일"))
            val today = LocalDate.now().toEpochDay()
            content.tasks.forEach { t ->
                val day = t.dueDay
                val due = when {
                    day == null -> null
                    day < today -> "기한 지남"
                    day == today -> "오늘까지"
                    else -> "내일까지"
                }
                sections.addView(item(t.title, due, null))
            }
        }
        if (content.plans.isNotEmpty()) {
            sections.addView(header("다가오는 일정"))
            content.plans.forEach { p -> sections.addView(item(PlanManager.describe(p), p.text.takeIf { it.isNotBlank() }, null)) }
        }
        binding.root.post { clampToScreen() }
    }

    private fun dp(n: Int) = (n * service.resources.displayMetrics.density).toInt()

    private fun header(title: String) = TextView(ctx).apply {
        text = title
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color(R.color.primary))
        setPadding(0, dp(10), 0, dp(2))
    }

    private fun item(title: String, detail: String?, onClick: (() -> Unit)?) = TextView(ctx).apply {
        text = if (detail.isNullOrBlank()) "• $title" else "• $title\n   $detail"
        textSize = 13f
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(color(R.color.text))
        setPadding(0, dp(3), 0, dp(3))
        if (onClick != null) setOnClickListener { onClick() }
    }

    private fun open(intent: Intent): Boolean =
        runCatching { service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }.isSuccess

    private fun showBubbleLine(line: String) {
        binding.toast.text = line
        binding.toast.visibility = View.VISIBLE
        handler.removeCallbacks(hideToast)
        handler.postDelayed(hideToast, 2500)
    }

    private val hideToast = Runnable { binding.toast.visibility = View.GONE }

    private fun color(res: Int) = ContextCompat.getColor(ctx, res)

    // ---- 카드 ----

    private fun toggle() = if (expanded) collapse() else expand()

    private fun expand() {
        expanded = true
        onExpanded()
        binding.card.visibility = View.VISIBLE
        renderCard()
    }

    private fun collapse() {
        expanded = false
        binding.card.visibility = View.GONE
    }

    private fun clampToScreen() {
        if (!attached) return
        val dm = service.resources.displayMetrics
        val w = binding.root.width
        val h = binding.root.height
        var changed = false
        if (params.x + w > dm.widthPixels) { params.x = (dm.widthPixels - w).coerceAtLeast(0); changed = true }
        if (params.y + h > dm.heightPixels) { params.y = (dm.heightPixels - h).coerceAtLeast(0); changed = true }
        if (changed) runCatching { wm.updateViewLayout(binding.root, params) }
    }

    // ---- 애니메이션 ----

    private fun scheduleBlink() {
        handler.postDelayed({
            val before = binding.face.text
            if (before == FACE_IDLE) {
                binding.face.text = FACE_BLINK
                handler.postDelayed({ if (binding.face.text == FACE_BLINK) binding.face.text = before }, 130)
            }
            scheduleBlink()
        }, 3000L + (Math.random() * 3000).toLong())
    }

    // ---- 드래그 ----

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var moved = false
        binding.bubble.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    downX = ev.rawX; downY = ev.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        params.x = (startX + dx).toInt().coerceAtLeast(0)
                        params.y = (startY + dy).toInt().coerceAtLeast(0)
                        if (attached) runCatching { wm.updateViewLayout(binding.root, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.overlayX = params.x
                        prefs.overlayY = params.y
                    } else {
                        v.performClick()
                        toggle()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private companion object {
        const val FACE_IDLE = ":)"
        const val FACE_ALERT = ":o"
        const val FACE_BLINK = "-)"
    }
}
