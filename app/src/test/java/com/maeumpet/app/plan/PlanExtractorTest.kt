package com.maeumpet.app.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

class PlanExtractorTest {
    // 2026-09-07 (월) 21:00 기준
    private val now = LocalDateTime.of(2026, 9, 7, 21, 0)
    private fun x(s: String) = PlanExtractor.extract(s, now)

    @Test
    fun tomorrow_with_time() {
        val c = x("내일 3시에 볼까?")!!
        assertEquals(LocalDateTime.of(2026, 9, 8, 15, 0), c.at)
        assertEquals("PROPOSE", c.type)
    }

    @Test
    fun evening_seven_and_half() {
        assertEquals(LocalDateTime.of(2026, 9, 8, 19, 30), x("낼 저녁 7시 반에 강남에서 보자")!!.at)
    }

    @Test
    fun weekday_next_week() {
        val c = x("다음주 수요일 점심 어때?")!!
        assertEquals(DayOfWeek.WEDNESDAY, c.at!!.dayOfWeek)
        assertEquals(12, c.at!!.hour)
        assert(c.at!!.toLocalDate().isAfter(now.toLocalDate().plusDays(6)))
    }

    @Test
    fun explicit_date() {
        assertEquals(LocalDateTime.of(2026, 9, 12, 9, 0), x("9월 12일에 모임 있어 올래?")!!.at)
    }

    @Test
    fun time_only_uses_today_or_tomorrow() {
        // 21시 기준 "10시"는 오늘 22시로 해석 (1~6시만 오후로 올림) → 22시 > 21시 이므로 오늘
        assertEquals(LocalDateTime.of(2026, 9, 7, 22, 0), x("오늘 10시에 통화 가능해?")!!.at)
    }

    @Test
    fun cancel_and_change() {
        assertEquals("CANCEL", x("미안 내일 못 갈 것 같아")!!.type)
        assertEquals("CHANGE", x("내일 말고 모레 보자")!!.type)
        assertEquals(LocalDateTime.of(2026, 9, 8, 9, 0), x("내일 말고 모레 보자")!!.at) // 첫 날짜 단어 기준 (변경은 펫이 물어봄)
    }

    @Test
    fun not_plans() {
        assertNull(x("ㅇㅇ"))
        assertNull(x("오늘 진짜 힘들다"))
        assertNull(x("[신한카드] 승인 12,500원 09/07 21:32"))
        assertNull(x("배송이 내일 도착 예정입니다"))
        assertNull(x("3시간 걸렸어"))
    }

    @Test
    fun intent_without_time_is_still_candidate() {
        val c = x("주말에 밥 먹자")
        assertNotNull(c)
        assertNull(c!!.at)
    }

    @Test fun casualAndNegativePhrasesAreNotAppointments() {
        listOf("일단 가보자", "친구 만나면", "굳이 누군가를 만날 필요가 없지 않을까", "게임면접이", "걍 집갈까", "주도장 가볼까").forEach {
            assertNull(it, x(it))
        }
        assertNotNull(x("오늘 5시 학교 맞지?"))
        assertNotNull(x("10시에 보자잉~~"))
        assertNotNull(x("우리 커피 마시자"))
    }
}
