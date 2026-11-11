package com.maeumpet.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager

/**
 * 홈 화면에 펫을 띄우고, 돌아왔을 때 브리핑 카드를 보여 주는 서비스.
 * 어떤 앱이 앞에 있는지(런처인지)만 본다. 다른 앱의 화면 내용이나 입력은 읽지 않는다.
 */
class MaeumAccessibilityService : AccessibilityService() {

    private var overlay: PetOverlay? = null
    private var homeBriefing: HomeBriefingController? = null

    private val imePackages: Set<String> by lazy {
        runCatching {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).inputMethodList.map { it.packageName }.toSet()
        }.getOrDefault(emptySet())
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        homeBriefing?.stop()
        homeBriefing = HomeBriefingController(this).also { it.start() }
        // 펫 카드를 펼치면 자동 브리핑 카드는 겹치지 않게 닫는다
        overlay = PetOverlay(this) { homeBriefing?.hide() }
        running = true
    }

    override fun onDestroy() {
        running = false
        homeBriefing?.stop()
        homeBriefing = null
        overlay?.destroy()
        overlay = null
        super.onDestroy()
    }

    override fun onInterrupt() { homeBriefing?.hide() }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Fold/unfold or rotation may change the available bounds. Keep the briefing in the app.
        homeBriefing?.hide()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        homeBriefing?.check()
        updatePet()
    }

    /**
     * 런처가 앞에 있으면 펫을 띄우고, 다른 앱(마음펫 화면 포함)이 앞에 있으면 숨긴다.
     * 이벤트의 패키지가 아니라 지금 포커스된 앱 창을 본다: 펫·카드·키보드 같은 겹쳐 뜨는 창은 앱 창이 아니라서
     * 판단을 흔들지 않는다. 전환 중이라 포커스된 앱 창이 없으면 그대로 둔다.
     */
    private fun updatePet() {
        val pkg = runCatching {
            windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }?.root?.packageName?.toString()
        }.getOrNull() ?: return
        if (pkg == "com.android.systemui" || pkg in imePackages) return
        val launcher = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
        if (pkg == launcher) {
            // Keep the round pet on the launcher; the return briefing card appears underneath it.
            overlay?.show()
        } else {
            overlay?.hide()
        }
    }

    companion object {
        @Volatile
        var running = false
            private set
    }
}
