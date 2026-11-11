package com.maeumpet.app.notif

import org.junit.Assert.*
import org.junit.Test

class BriefingRegressionTest {
    @Test fun legacyMultilineDoesNotReturnTimestamp() {
        val raw = "상대\t배송 안내\n문 앞에 놓았습니다\t1788943955710\n친구\t첫 줄\n둘째 줄\t1788946287000"
        val decoded = MessageCodec.decode(raw)
        assertEquals(2, decoded.size)
        assertEquals("첫 줄\n둘째 줄", decoded.last().text)
    }
    @Test fun versionedRecordsPreserveSpecialCharacters() {
        val messages = listOf(MessageCodec.Message("친구\t이름", "첫 줄\n둘째\t줄 😀", 1788943955710), MessageCodec.Message("", "", 1788943955711))
        assertEquals(messages, MessageCodec.decode(MessageCodec.encode(messages)))
        assertTrue(MessageCodec.decode("MP2\ninvalid").isEmpty())
        assertTrue(MessageCodec.decode("손상된 기록").isEmpty())
    }
    @Test fun financialEventsRemainDistinct() {
        val cases = mapOf(
            "990원 결제 실패 잔액이 부족해요" to BriefingPolicy.MoneyStatus.FAILED,
            "포인트 4원이 적립되었어요" to BriefingPolicy.MoneyStatus.REWARD,
            "출금 30,000원 네이버페이충" to BriefingPolicy.MoneyStatus.TRANSFER,
            "출금 10,000원 오픈뱅킹출금" to BriefingPolicy.MoneyStatus.TRANSFER,
            "990원 결제 잔액 9,045원" to BriefingPolicy.MoneyStatus.PAYMENT,
            "승인 취소 990원" to BriefingPolicy.MoneyStatus.REFUND,
            "입금 10,000원" to BriefingPolicy.MoneyStatus.INCOME)
        cases.forEach { (text, expected) -> assertEquals(text, expected, BriefingPolicy.moneyStatus(text)) }
    }
    @Test fun acknowledgementsAreNotImportantEvenForFrequentSenders() {
        listOf("ㅇㅇ", "화긴스", "어흐", "ㅋㅋㅋ", "1788943955710").forEach {
            assertFalse(it, BriefingPolicy.eligible(NotifRules.MESSAGE, it, 100))
        }
        assertTrue(BriefingPolicy.eligible(NotifRules.MESSAGE, "내일 7시 말고 8시에 보자", 65))
        assertTrue(BriefingPolicy.eligible(NotifRules.MONEY, "990원 결제 실패", 50))
        assertFalse(BriefingPolicy.eligible(NotifRules.OTHER, "새 영상이 올라왔어", 100))
    }
}
