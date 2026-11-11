package com.maeumpet.app.personal

import android.content.Context
import android.content.SharedPreferences

/** 폰 안에만 남는 설정과 학습값 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("maeumpet", Context.MODE_PRIVATE)

    // ---- 알림 브리핑 ----
    var homePopupEnabled: Boolean
        get() = sp.getBoolean(KEY_HOME_POPUP, true)
        set(v) = sp.edit().putBoolean(KEY_HOME_POPUP, v).apply()

    var homePopupPreview: Boolean
        get() = sp.getBoolean("home_popup_preview", false)
        set(v) = sp.edit().putBoolean("home_popup_preview", v).apply()

    var pendingHomePopupId: Long
        get() = sp.getLong(KEY_PENDING_POPUP, 0L)
        set(v) = sp.edit().putLong(KEY_PENDING_POPUP, v).apply()

    var shownHomePopupId: Long
        get() = sp.getLong("shown_home_popup_id", 0L)
        set(v) = sp.edit().putLong("shown_home_popup_id", v).apply()

    var screenOffAt: Long
        get() = sp.getLong("screen_off_at", 0L)
        set(v) = sp.edit().putLong("screen_off_at", v).apply()

    /** 확실한 광고·참여 유도 알림을 펫이 알림창에서 치우기 (AutoClear) */
    var autoClearEnabled: Boolean
        get() = sp.getBoolean("auto_clear_enabled", true)
        set(v) = sp.edit().putBoolean("auto_clear_enabled", v).apply()

    /** 덜 급한 알림을 점심·저녁 모아 보기 시각까지 미루기 (AutoClear.shouldDefer). autoClearEnabled가 꺼지면 같이 멈춘다 */
    var autoDeferEnabled: Boolean
        get() = sp.getBoolean("auto_defer_enabled", true)
        set(v) = sp.edit().putBoolean("auto_defer_enabled", v).apply()

    /** 사용자가 "이 앱은 건드리지 마"라고 한 앱 (치우기·미루기 둘 다 안 함) */
    var clearExemptPackages: Set<String>
        get() = sp.getStringSet("clear_exempt_packages", null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("clear_exempt_packages", v.toSet()).apply()

    var briefingEnabled: Boolean
        get() = sp.getBoolean(KEY_BRIEFING, true)
        set(v) = sp.edit().putBoolean(KEY_BRIEFING, v).apply()

    var notifRetentionDays: Int
        get() = sp.getInt(KEY_RETENTION, 7)
        set(v) = sp.edit().putInt(KEY_RETENTION, v.coerceIn(1, 30)).apply()

    var lastBriefingAt: Long
        get() = sp.getLong(KEY_LAST_BRIEFING, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_BRIEFING, v).apply()

    var lastNightRunAt: Long
        get() = sp.getLong(KEY_LAST_NIGHT, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_NIGHT, v).apply()

    /** 폰 안에서 학습한 중요도 분류기 가중치(JSON). 없으면 규칙만 */
    var importanceModel: String?
        get() = sp.getString(KEY_IMP_MODEL, null)
        set(v) = sp.edit().putString(KEY_IMP_MODEL, v).apply()

    var importanceModelAcc: Float
        get() = sp.getFloat(KEY_IMP_ACC, 0f)
        set(v) = sp.edit().putFloat(KEY_IMP_ACC, v).apply()

    var importanceModelN: Int
        get() = sp.getInt(KEY_IMP_N, 0)
        set(v) = sp.edit().putInt(KEY_IMP_N, v).apply()

    /** 최근 알림을 마지막으로 다시 판단한 Kev 모델 파일 이름 (KevRescore) */
    var kevScoredModel: String?
        get() = sp.getString("kev_scored_model", null)
        set(v) = sp.edit().putString("kev_scored_model", v).apply()

    /** 다시 판단 중간 저장: "판:가장 작은 처리한 알림 id" (KevRescore가 이어서 하도록) */
    var kevRescoreProgress: String?
        get() = sp.getString("kev_rescore_progress", null)
        set(v) = sp.edit().putString("kev_rescore_progress", v).apply()

    var nanoStatus: String
        get() = sp.getString(KEY_NANO, "UNKNOWN") ?: "UNKNOWN"
        set(v) = sp.edit().putString(KEY_NANO, v).apply()

    var excludedNotifPackages: Set<String>
        get() = sp.getStringSet(KEY_NOTIF_EXCLUDE, null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_NOTIF_EXCLUDE, v.toSet()).apply()

    var overlayX: Int
        get() = sp.getInt(KEY_OVERLAY_X, 24)
        set(v) = sp.edit().putInt(KEY_OVERLAY_X, v).apply()

    var overlayY: Int
        get() = sp.getInt(KEY_OVERLAY_Y, 360)
        set(v) = sp.edit().putInt(KEY_OVERLAY_Y, v).apply()

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.registerOnSharedPreferenceChangeListener(l)
    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.unregisterOnSharedPreferenceChangeListener(l)

    companion object {
        const val KEY_HOME_POPUP = "home_popup_enabled"
        const val KEY_PENDING_POPUP = "pending_home_popup_id"
        const val KEY_OVERLAY_X = "overlay_x"
        const val KEY_OVERLAY_Y = "overlay_y"
        const val KEY_BRIEFING = "briefing_enabled"
        const val KEY_RETENTION = "notif_retention_days"
        const val KEY_LAST_BRIEFING = "last_briefing_at"
        const val KEY_LAST_NIGHT = "last_night_run_at"
        const val KEY_NOTIF_EXCLUDE = "notif_excluded_packages"
        const val KEY_IMP_MODEL = "importance_model"
        const val KEY_IMP_ACC = "importance_model_acc"
        const val KEY_IMP_N = "importance_model_n"
        const val KEY_NANO = "nano_status"
    }
}
