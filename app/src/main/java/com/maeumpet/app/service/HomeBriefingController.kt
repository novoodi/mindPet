package com.maeumpet.app.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.PowerManager
import com.maeumpet.app.notif.NotifDatabase
import com.maeumpet.app.personal.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Only launcher identity is inspected; this does not automate other apps. */
class HomeBriefingController(private val service: AccessibilityService) {
    private val prefs = Prefs(service)
    private val popup = HomeBriefingPopup(service)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refresh: Job? = null
    private var registered = false
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in setOf(Prefs.KEY_HOME_POPUP, Prefs.KEY_PENDING_POPUP, Prefs.KEY_BRIEFING, "home_popup_preview")) check()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                refresh?.cancel()
                popup.hide()
            } else check()
        }
    }

    fun start() {
        prefs.registerListener(listener)
        runCatching {
            service.registerReceiver(receiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            })
        }.onSuccess { registered = true }
        check()
    }

    private fun onHome(): Boolean {
        // The popup itself can become the accessibility active window on One UI.
        // Inspect the focused application window beneath accessibility overlays instead.
        val active = runCatching {
            service.windows.firstOrNull {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
            }?.root?.packageName?.toString()
        }.getOrNull() ?: return false
        val home = service.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName ?: return false
        return home != "android" && active == home
    }

    private fun unlocked(): Boolean = service.getSystemService(PowerManager::class.java).isInteractive &&
        !service.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    fun check() {
        if (!unlocked() || !onHome() || !prefs.homePopupEnabled || !prefs.briefingEnabled) popup.hide()
        refresh?.cancel()
        refresh = scope.launch {
            delay(300)
            if (prefs.homePopupPreview && unlocked() && onHome()) {
                android.util.Log.d("MaeumPet.Popup", "preview requested on home")
                prefs.homePopupPreview = false
                popup.show("[미리 보기 · 실제 받은 메시지가 아니야]\n\n친구 · 카카오톡\n내일 저녁 7시에 만날 수 있어?\n\n회사 · 메일\n견적서 수정본을 보냈어요.\n\n이렇게 부재중 소식을 전해 줄게.")
                return@launch
            }
            if (!unlocked() || !onHome() || !prefs.homePopupEnabled || !prefs.briefingEnabled) {
                popup.hide()
                return@launch
            }
            val id = prefs.pendingHomePopupId
            if (id <= prefs.shownHomePopupId) return@launch
            val b = NotifDatabase.get(service).briefings().get(id) ?: return@launch
            if (HomePopupPolicy.canShow(onHome(), unlocked(), prefs.homePopupEnabled && prefs.briefingEnabled,
                    id, prefs.shownHomePopupId, b.createdAt, System.currentTimeMillis())) {
                if (popup.show(b.text)) prefs.shownHomePopupId = id
            }
        }
    }

    fun stop() {
        prefs.unregisterListener(listener)
        if (registered) runCatching { service.unregisterReceiver(receiver) }
        registered = false
        scope.cancel()
        popup.hide()
    }

    fun hide() {
        refresh?.cancel()
        popup.hide()
    }
}
