package com.maeumpet.app.notif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AutoClearTest {
    private fun n(
        kind: String, title: String, text: String, promo: Float? = null, timely: Float? = null, pkg: String = "com.example",
        category: String? = null,
    ) = NotifItem(
        key = "k", packageName = pkg, appLabel = "앱", postedAt = 0, category = category, title = title, text = text,
        sender = null, conversationTitle = null, isGroupConversation = false, messages = null, kind = kind, amount = null,
        isQuestion = false, importance = 10, kevPromo = promo, kevTimely = timely,
    )

    @Test fun clearsSureAdsAndNudges() {
        // 가짜 긴급함: 실측 promo 0.99, timely 0.64
        assertTrue(AutoClear.shouldClear(n(NotifRules.PROMO, "순금 복권 응모장 1장이 생겼어요", "오늘 응모하지 않으면 사라져요.", promo = 0.99f, timely = 0.64f), emptySet()))
        // "배달의민족"이라 규칙이 배송으로 분류했지만 광고 표시가 있음 (실측)
        assertTrue(AutoClear.shouldClear(n(NotifRules.DELIVERY, "배달의민족", "(광고) 피자헛 7,000원 할인 드려요", promo = 1.0f, timely = 0f), emptySet()))
        assertTrue(AutoClear.shouldClear(n(NotifRules.PROMO, "불금 저녁은 피자로", "(광고) 피자헛 7,000원 할인 드려요"), emptySet()))   // Kev 없음, 광고 표시
        assertTrue(AutoClear.shouldClear(n(NotifRules.EMAIL, "Stocktwits", "Here's what you missed this week", promo = 0.95f, timely = 0.02f), emptySet()))
    }

    @Test fun keepsWhatNeedsAnswerOrAttention() {
        // 메신저·결제·배송은 Kev가 광고라 해도 남긴다
        assertFalse(AutoClear.shouldClear(n(NotifRules.MESSAGE, "엄마", "오이 좀 사다 놔", promo = 0.99f), emptySet()))
        assertFalse(AutoClear.shouldClear(n(NotifRules.MONEY, "결제 승인", "45,000원", promo = 0.99f), emptySet()))
        // 사람이 보낸 메일
        assertFalse(AutoClear.shouldClear(n(NotifRules.EMAIL, "김교수", "기한이 바뀌었습니다", promo = 0.02f, timely = 0.9f), emptySet()))
        // 보안 문구
        assertFalse(AutoClear.shouldClear(n(NotifRules.OTHER, "보안 경고", "새 기기에서 로그인했습니다", promo = 0.95f), emptySet()))
        // 오늘 봐야 함
        assertFalse(AutoClear.shouldClear(n(NotifRules.PROMO, "당첨!", "내일까지 배송지를 입력하세요", promo = 0.95f, timely = 0.95f), emptySet()))
        // 배송 알림은 Kev가 광고라 해도 광고 표시가 없으면 남긴다
        assertFalse(AutoClear.shouldClear(n(NotifRules.DELIVERY, "택배", "문 앞에 도착했습니다", promo = 0.95f), emptySet()))
        // 카톡 채널의 (광고) 메시지도 메신저라 남긴다
        assertFalse(AutoClear.shouldClear(n(NotifRules.MESSAGE, "브랜드", "(광고) 신상 입고", promo = 0.99f), emptySet()))
        // 확신이 부족한 광고, 규칙만 광고(쿠폰 사용 완료 같은)
        assertFalse(AutoClear.shouldClear(n(NotifRules.PROMO, "쿠폰", "쿠폰이 사용되었습니다", promo = 0.6f), emptySet()))
    }

    @Test fun respectsExemptApps() {
        assertFalse(AutoClear.shouldClear(n(NotifRules.PROMO, "특가", "(광고) 오늘만", promo = 0.99f, pkg = "com.toss"), setOf("com.toss")))
    }

    @Test fun defersWhatCanWait() {
        val defer = AutoClear.Action.DEFER
        // 커뮤니티·웹툰 소식: 광고는 아니지만 오늘 안 봐도 됨
        assertEquals(defer, AutoClear.decide(n(NotifRules.OTHER, "네이버 웹툰", "새 회차가 올라왔어요", promo = 0.4f, timely = 0.1f), emptySet()))
        // 확신이 부족한 광고는 치우지 않고 미룬다
        assertEquals(defer, AutoClear.decide(n(NotifRules.PROMO, "쿠팡", "이번 주 추천 상품", promo = 0.7f, timely = 0.2f), emptySet()))
        // 광고성 메일(확신 부족)
        assertEquals(defer, AutoClear.decide(n(NotifRules.EMAIL, "뉴스레터", "이번 주 소식", promo = 0.7f, timely = 0.1f), emptySet()))
    }

    @Test fun doesNotDeferWhatMatters() {
        val keep = AutoClear.Action.KEEP
        // 사람이 보낸 메일은 급하지 않아도 남긴다
        assertEquals(keep, AutoClear.decide(n(NotifRules.EMAIL, "김교수", "다음 주 자료 공유합니다", promo = 0.05f, timely = 0.2f), emptySet()))
        // 오늘 봐야 함
        assertEquals(keep, AutoClear.decide(n(NotifRules.OTHER, "병원", "오늘 3시 진료 예약", promo = 0.1f, timely = 0.8f), emptySet()))
        // Kev 판단이 없으면 미루지 않는다
        assertEquals(keep, AutoClear.decide(n(NotifRules.OTHER, "앱", "새 소식"), emptySet()))
        // 부재중 전화 같은 카테고리
        assertEquals(keep, AutoClear.decide(n(NotifRules.OTHER, "부재중 전화", "엄마", promo = 0.1f, timely = 0.2f, category = android.app.Notification.CATEGORY_CALL), emptySet()))
        // 메신저·배송·결제
        assertEquals(keep, AutoClear.decide(n(NotifRules.MESSAGE, "친구", "ㅋㅋ", timely = 0.1f), emptySet()))
        assertEquals(keep, AutoClear.decide(n(NotifRules.DELIVERY, "택배", "배송 출발", promo = 0.1f, timely = 0.2f), emptySet()))
        // 보안 문구, 자주 여는 앱, 건드리지 말라고 한 앱
        assertEquals(keep, AutoClear.decide(n(NotifRules.OTHER, "구글", "계정 활동 요약", promo = 0.1f, timely = 0.1f), emptySet()))
        assertEquals(keep, AutoClear.decide(n(NotifRules.OTHER, "웹툰", "새 회차", promo = 0.1f, timely = 0.1f), emptySet(), opensOften = true))
        assertEquals(keep, AutoClear.decide(n(NotifRules.OTHER, "웹툰", "새 회차", promo = 0.1f, timely = 0.1f, pkg = "w"), setOf("w")))
    }

    @Test fun deferScheduleIsLunchAndEvening() {
        val zone = ZoneId.of("Asia/Seoul")
        fun at(d: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, d, h, m, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(at(3, 12), DeferSchedule.next(at(3, 9), zone))
        assertEquals(at(3, 19), DeferSchedule.next(at(3, 12), zone))
        assertEquals(at(4, 12), DeferSchedule.next(at(3, 23, 30), zone))
        assertEquals("저녁 7시", DeferSchedule.label(at(3, 19), at(3, 13), zone))
        assertEquals("내일 낮 12시", DeferSchedule.label(at(4, 12), at(3, 23), zone))
    }
}
