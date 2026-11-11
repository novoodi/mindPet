package com.maeumpet.app.plan

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * 카톡 문장에서 약속 후보를 찾는 규칙.
 * 모델이 아니라 규칙인 이유: "내일 7시 볼까?"처럼 시간 표현은 규칙적이고,
 * 애매한 건 펫이 사용자에게 물어보는 게 더 정확하기 때문.
 */
object PlanExtractor {

    data class Candidate(
        val text: String,
        /** 약속으로 보이는 이유 (예: "내일 + 7시 + 볼까") */
        val why: String,
        /** 추정 시각. 날짜만 알면 그날 09:00, 모르면 null */
        val at: LocalDateTime?,
        val hasExplicitTime: Boolean,
        /** PROPOSE(제안) / CHANGE(변경) / CANCEL(취소) */
        val type: String,
    )

    private val INTENT = Regex("""보자|볼까|볼래|만나|만날|갈래|갈까|가자|하자|할까|할래|올래|와라|올 수|약속|예약|미팅|회의|면접|모임|식사|밥 먹|저녁 먹|점심 먹|술 한|커피|영화|시간 돼|시간 되|시간 있|가능해|가능하|어때|콜\??""")
    private val CANCEL = Regex("""못 가|못가|못 갈|취소|미루|다음에|안 될 것|안될 것|힘들 것 같|어려울 것 같|빠질게|못 볼|못 만나""")
    private val CHANGE = Regex("""말고|대신|바꾸|바꿀|옮기|미루|당기|늦춰|앞당""")
    private val EXCLUDE = Regex("""광고|쿠폰|배송|승인|결제|출금|입금|이체|예약번호|주문|택배|뉴스레터""")

    private val REL_DAY = mapOf("오늘" to 0L, "내일" to 1L, "낼" to 1L, "모레" to 2L, "글피" to 3L)
    private val WEEKDAYS = mapOf("월" to DayOfWeek.MONDAY, "화" to DayOfWeek.TUESDAY, "수" to DayOfWeek.WEDNESDAY, "목" to DayOfWeek.THURSDAY, "금" to DayOfWeek.FRIDAY, "토" to DayOfWeek.SATURDAY, "일" to DayOfWeek.SUNDAY)
    private val WEEKDAY_RX = Regex("""(이번\s*주|다음\s*주|담주|이번주|다음주)?\s*([월화수목금토일])요일|(이번\s*주|다음\s*주|담주)\s*([월화수목금토일])(?![가-힣])""")
    private val DATE_RX = Regex("""(\d{1,2})\s*월\s*(\d{1,2})\s*일|(\d{1,2})\s*/\s*(\d{1,2})(?!\d)""")
    private val TIME_RX = Regex("""(새벽|아침|오전|낮|점심|오후|저녁|밤)?\s*(\d{1,2})\s*시\s*(반|(\d{1,2})\s*분)?""")
    private val MEAL = mapOf("아침" to 8, "점심" to 12, "저녁" to 19, "밤" to 21)

    fun extract(text: String, now: LocalDateTime = LocalDateTime.now(ZoneId.systemDefault())): Candidate? {
        val t = text.trim()
        if (t.length < 4 || EXCLUDE.containsMatchIn(t)) return null
        val intent = INTENT.find(t)
        val cancel = CANCEL.containsMatchIn(t)
        val change = CHANGE.containsMatchIn(t)
        val date = findDate(t, now)
        val time = findTime(t)
        // A keyword alone is not an appointment. Require a temporal anchor or a concrete invitation.
        if (Regex("필요.{0,6}없|만나면|만날 경우|면접이").containsMatchIn(t) && !cancel && !change) return null
        val concreteInvitation = Regex("(?:같이|우리).{0,15}(?:만나자|밥 먹자|커피 마시자)|(?:밥 먹자|커피 마시자|만나자)").containsMatchIn(t)
        if (date == null && time == null && !concreteInvitation && !("주말" in t && intent != null)) return null
        // 약속 문장 조건: (의도 단어) 또는 (날짜와 시간이 같이 있음)
        if (intent == null && !(date != null && time != null) && !cancel) return null
        if (intent == null && cancel && date == null && time == null) return null

        val why = listOfNotNull(date?.second, time?.second, intent?.value).joinToString(" + ")
        val at = when {
            date != null && time != null -> {
                var dt = LocalDateTime.of(date.first, time.first)
                // "오늘 10시"를 밤 9시에 말했으면 아침 10시가 아니라 밤 10시
                if (dt.isBefore(now) && date.first == now.toLocalDate() && time.first.hour < 12 && !hasPeriod(t)) dt = dt.plusHours(12)
                dt
            }
            date != null -> LocalDateTime.of(date.first, LocalTime.of(9, 0))
            time != null -> {
                // 날짜 없이 시간만 있으면 오늘, 이미 지났으면 내일
                val today = LocalDateTime.of(now.toLocalDate(), time.first)
                if (today.isAfter(now)) today else today.plusDays(1)
            }
            else -> null
        }
        val type = when {
            cancel -> "CANCEL"
            change -> "CHANGE"
            else -> "PROPOSE"
        }
        return Candidate(t, why, at, time != null, type)
    }

    private fun hasPeriod(t: String) = Regex("""새벽|아침|오전|낮|점심|오후|저녁|밤""").containsMatchIn(t)

    private fun findDate(t: String, now: LocalDateTime): Pair<LocalDate, String>? {
        for ((word, d) in REL_DAY) if (Regex("(^|[^가-힣])$word(?![가-힣])").containsMatchIn(t)) return now.toLocalDate().plusDays(d) to word
        WEEKDAY_RX.find(t)?.let { m ->
            val prefix = (m.groupValues[1].ifEmpty { m.groupValues[3] }).replace(" ", "")
            val dayChar = m.groupValues[2].ifEmpty { m.groupValues[4] }
            val dow = WEEKDAYS[dayChar] ?: return@let
            val today = now.toLocalDate()
            val date = if (prefix.startsWith("다음") || prefix == "담주") {
                // 다음 주 = 다음 월요일부터 시작하는 주
                today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).with(TemporalAdjusters.nextOrSame(dow))
            } else if (prefix.startsWith("이번")) {
                today.with(TemporalAdjusters.nextOrSame(dow))
            } else {
                // 접두어 없으면 앞으로 오는 가장 가까운 그 요일 (오늘이면 밤 9시 이후엔 다음 주)
                val d = today.with(TemporalAdjusters.nextOrSame(dow))
                if (d == today && now.hour >= 21) d.plusWeeks(1) else d
            }
            return date to m.value.trim()
        }
        DATE_RX.find(t)?.let { m ->
            val month = (m.groupValues[1].ifEmpty { m.groupValues[3] }).toIntOrNull() ?: return@let
            val day = (m.groupValues[2].ifEmpty { m.groupValues[4] }).toIntOrNull() ?: return@let
            if (month !in 1..12 || day !in 1..31) return@let
            var date = runCatching { LocalDate.of(now.year, month, day) }.getOrNull() ?: return@let
            if (date.isBefore(now.toLocalDate().minusDays(1))) date = date.plusYears(1)
            return date to m.value.trim()
        }
        return null
    }

    private fun findTime(t: String): Pair<LocalTime, String>? {
        val m = TIME_RX.find(t) ?: run {
            for ((meal, h) in MEAL) if (Regex("$meal(에|때|쯤|으로|로)?(?![가-힣])").containsMatchIn(t) && INTENT.containsMatchIn(t)) {
                return LocalTime.of(h, 0) to meal
            }
            return null
        }
        var hour = m.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..24) return null
        val period = m.groupValues[1]
        if (hour <= 12) {
            hour = when (period) {
                "오후", "저녁", "밤" -> if (hour == 12) 12 else hour + 12
                "새벽", "아침", "오전" -> if (hour == 12) 0 else hour
                "낮", "점심" -> if (hour < 6) hour + 12 else hour
                else -> if (hour in 1..6) hour + 12 else hour   // "3시에 볼까"는 대개 오후 3시
            }
        }
        if (hour == 24) hour = 0
        val minute = when {
            m.groupValues[3] == "반" -> 30
            m.groupValues[4].isNotEmpty() -> m.groupValues[4].toIntOrNull()?.coerceIn(0, 59) ?: 0
            else -> 0
        }
        return LocalTime.of(hour, minute) to m.value.trim()
    }
}
