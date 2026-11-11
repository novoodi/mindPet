package com.maeumpet.app.notif

import android.app.Notification
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 펫이 알아서 치울 알림인가, 나중으로 미룰 알림인가. 확실한 광고·참여 유도만 치우고, 지금 볼 필요 없는 소식은
 * 정해진 시간(DeferSchedule)까지 미룬다. 답해야 하거나 놓치면 손해인 알림은 절대 건드리지 않는다.
 * 치운·미룬 알림은 지우지 않고 DB에 남겨 "치운 알림" 화면에서 다시 볼 수 있다.
 */
object AutoClear {
    enum class Action { KEEP, CLEAR, DEFER }

    /** Kev가 광고·유도라고 이만큼 확신해야 치운다 (판단 기준 0.5보다 훨씬 보수적으로) */
    const val PROMO_SURE = 0.9f

    /** 메신저(카톡·문자), 결제, 배송, 일정은 어떤 경우에도 치우지 않는다 */
    private val NEVER = setOf(NotifRules.MESSAGE, NotifRules.MONEY, NotifRules.DELIVERY, NotifRules.CALENDAR)

    /** 보안·계정 관련 문구가 있으면 광고처럼 보여도 남긴다 */
    private val SECURITY = Regex("""로그인|비밀번호|보안|본인\s*확인|계정|security|sign[- ]?in|password""", RegexOption.IGNORE_CASE)

    /** 법으로 붙이는 광고 표시 */
    private val AD_LABEL = Regex("""[(\[]\s*광고\s*[)\]]""")

    /** 광고가 "오늘만!"으로 급한 척하면 Kev의 급함 확률도 0.6대로 오른다(실측). 이보다 확실히 급할 때만 광고보다 우선한다. */
    const val TIMELY_KEEP = 0.9f

    /** 미뤄도 되는 알림 카테고리. 전화·알람·시스템·길안내 같은 카테고리가 붙은 알림은 미루지 않는다. */
    private val DEFERRABLE_CATEGORIES = setOf(
        null, Notification.CATEGORY_PROMO, Notification.CATEGORY_SOCIAL, Notification.CATEGORY_RECOMMENDATION,
        Notification.CATEGORY_EMAIL, Notification.CATEGORY_MESSAGE,
    )

    /** opensOften: 이 보낸 사람의 알림을 평소에 자주 열어 본다(SenderStat). 그러면 미루지 않는다. */
    fun decide(n: NotifItem, exemptPackages: Set<String>, opensOften: Boolean = false): Action = when {
        shouldClear(n, exemptPackages) -> Action.CLEAR
        shouldDefer(n, exemptPackages, opensOften) -> Action.DEFER
        else -> Action.KEEP
    }

    fun shouldClear(n: NotifItem, exemptPackages: Set<String>): Boolean {
        // 메신저(카톡·문자)는 광고 표시가 있어도 사람 대화가 섞일 수 있어 건드리지 않는다
        if (n.packageName in exemptPackages || n.kind == NotifRules.MESSAGE) return false
        val all = "${n.title.orEmpty()} ${n.text.orEmpty()}"
        if (SECURITY.containsMatchIn(all)) return false
        if ((n.kevTimely ?: 0f) >= TIMELY_KEEP) return false
        // "(광고)"는 법으로 붙이는 표시라 분류와 상관없이 광고다 ("배달의민족"이 규칙상 배송으로 분류돼도)
        if (AD_LABEL.containsMatchIn(all)) return true
        if (n.kind in NEVER) return false
        val kevSure = (n.kevPromo ?: 0f) >= PROMO_SURE
        return when (n.kind) {
            NotifRules.PROMO, NotifRules.OTHER -> kevSure
            // 광고성 메일만. 메일 자체는 받은편지함에 그대로 있다
            NotifRules.EMAIL -> kevSure
            else -> false
        }
    }

    /**
     * 덜 급한 소식: Kev가 "오늘 안 봐도 된다"고 본 광고·기타 알림, 광고성으로 보이는 메일.
     * Kev 판단이 없으면(모델 없음) 미루지 않는다. 사람이 보낸 메일은 미루지 않는다.
     */
    fun shouldDefer(n: NotifItem, exemptPackages: Set<String>, opensOften: Boolean = false): Boolean {
        if (n.packageName in exemptPackages || opensOften) return false
        if (n.kind != NotifRules.PROMO && n.kind != NotifRules.OTHER && n.kind != NotifRules.EMAIL) return false
        if (n.category !in DEFERRABLE_CATEGORIES) return false
        val timely = n.kevTimely ?: return false
        if (timely >= ImportanceLearner.NOTIF_YES) return false
        if (SECURITY.containsMatchIn("${n.title.orEmpty()} ${n.text.orEmpty()}")) return false
        return n.kind != NotifRules.EMAIL || (n.kevPromo ?: 0f) >= ImportanceLearner.NOTIF_YES
    }
}

/** 미룬 알림이 다시 나타나는 시각: 점심과 저녁 */
object DeferSchedule {
    val HOURS = listOf(12, 19)

    fun next(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (0L..1L).flatMap { d -> HOURS.map { h -> today.plusDays(d).atTime(h, 0).atZone(zone).toInstant().toEpochMilli() } }
            .first { it > now }
    }

    /** "저녁 7시", "내일 낮 12시" */
    fun label(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(at).atZone(zone)
        val today: LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day = when (t.toLocalDate()) {
            today -> ""
            today.plusDays(1) -> "내일 "
            else -> "${t.monthValue}/${t.dayOfMonth} "
        }
        val h = t.hour
        val part = when {
            h < 6 -> "새벽 $h"
            h < 12 -> "아침 $h"
            h == 12 -> "낮 12"
            h < 18 -> "오후 ${h - 12}"
            else -> "저녁 ${h - 12}"
        }
        return "$day${part}시"
    }
}
