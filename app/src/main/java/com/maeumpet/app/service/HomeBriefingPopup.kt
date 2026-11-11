package com.maeumpet.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.maeumpet.app.R
import com.maeumpet.app.ui.MainActivity
import com.maeumpet.app.personal.Prefs

/** Bounded, scrollable card; the surrounding launcher remains touchable. */
class HomeBriefingPopup(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: LinearLayout? = null
    private fun dp(n: Int) = (n * service.resources.displayMetrics.density).toInt()

    fun show(text: String): Boolean {
        hide()
        val ctx = ContextThemeWrapper(service, R.style.Theme_MaeumPet)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundResource(R.drawable.bg_card)
            elevation = dp(8).toFloat()
        }
        // The original PetOverlay bubble remains above this card. Do not duplicate it here.
        card.addView(TextView(ctx).apply {
            this.text = "돌아왔구나! 내가 소식을 모아 뒀어."
            textSize = 16f
            setTextColor(ContextCompat.getColor(ctx, R.color.text))
        })
        val dm = service.resources.displayMetrics
        val scroll = ScrollView(ctx).apply {
            addView(TextView(ctx).apply {
                this.text = text
                textSize = 15f
                setTextColor(ContextCompat.getColor(ctx, R.color.text))
                setPadding(0, dp(12), 0, dp(12))
            })
        }
        card.addView(scroll, LinearLayout.LayoutParams(-1, minOf(dp(280), dm.heightPixels / 3)))
        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(ctx).apply {
            this.text = "자세히 보기"
            setOnClickListener {
                runCatching {
                    service.startActivity(Intent(service, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                }.onSuccess { hide() }
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(ctx).apply {
            this.text = "닫기"
            setOnClickListener { hide() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(actions)
        val pet = Prefs(service).let { it.overlayX to it.overlayY }
        val params = WindowManager.LayoutParams(
            minOf(dp(340), dm.widthPixels - dp(32)).coerceAtLeast(1), -2,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = pet.first
            y = pet.second + dp(64)
            title = "MaeumPet.HomeBriefing"
        }
        return runCatching {
            wm.addView(card, params); view = card
            android.util.Log.d("MaeumPet.Popup", "shown")
            true
        }.getOrElse { android.util.Log.e("MaeumPet.Popup", "show failed", it); false }
    }

    fun hide() {
        if (view != null) android.util.Log.d("MaeumPet.Popup", "hidden")
        view?.let { runCatching { wm.removeViewImmediate(it) } }
        view = null
    }
}
