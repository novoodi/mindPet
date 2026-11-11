package com.maeumpet.app.notif

import org.junit.Assert.*
import org.junit.Test

class NotificationSummaryTest {
    @Test fun keepsChangedScheduleAndLatestReply() {
        val result = NotificationSummary.summarize(listOf("안녕", "내일 7시에 보자", "8시로 변경하자", "알겠어"))
        assertEquals("8시로 변경하자 → 알겠어", result)
    }
    @Test fun repeatedCorrectionKeepsLastOccurrenceInOrder() {
        assertEquals("8시에 보자 → 7시에 보자", NotificationSummary.summarize(listOf("7시에 보자", "8시에 보자", "7시에 보자")))
    }
    @Test fun picksRequestOutOfLongMessage() {
        val result = NotificationSummary.summarize(listOf("안녕하세요. 잘 지내시죠. 내일까지 서류를 보내주세요. 감사합니다."))
        assertEquals("내일까지 서류를 보내주세요. → 감사합니다.", result)
    }
    @Test fun removesSecretsAndMarksTruncation() {
        assertFalse(NotificationSummary.summarize(listOf("인증번호 123456", "배송 도착")).contains("123456"))
        assertTrue(NotificationSummary.summarize(listOf("가".repeat(300))).endsWith("…"))
        assertEquals("요약할 내용이 없어.", NotificationSummary.summarize(emptyList()))
    }
}
