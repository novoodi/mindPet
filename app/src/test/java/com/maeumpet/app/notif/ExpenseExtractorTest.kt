package com.maeumpet.app.notif

import org.junit.Assert.*
import org.junit.Test

class ExpenseExtractorTest {
    @Test fun paymentSeparatesBalanceAndMerchant() {
        val p = ExpenseExtractor.extract("신한카드 승인", "[Web발신]\n5,200원 일시불\n가맹점: 스타벅스 강남점\n잔액 100,000원")!!
        assertEquals(5200L, p.amount)
        assertEquals("스타벅스 강남점", p.merchant)
        assertFalse(p.refund)
    }

    @Test fun findsMerchantInCardSms() {
        val p = ExpenseExtractor.extract("삼성카드", "삼성카드 승인\n홍*동님\n09/17 12:34\n12,000원 일시불\n이마트 강남점\n누적 80,000원")!!
        assertEquals("이마트 강남점", p.merchant)
        assertEquals(12000L, p.amount)
    }

    @Test fun rejectsNonSpendingAndAmbiguousAmounts() {
        listOf("결제 실패 5,000원", "결제 예정 5,000원", "결제대금 출금 5,000원", "입금 5,000원",
            "송금 5,000원", "충전 5,000원", "결제 취소 요청 5,000원", "결제 5,000원 6,000원",
            "결제 잔액 5,000원", "5,000원 결제하면 할인", "5,000원 결제할 예정", "결제 5.50원", "승인 10 USD 13,000원",
            "결제 0원", "결제 99999999999999999999999999원", "5,000원 결제해 줘").forEach {
            assertNull(it, ExpenseExtractor.extract(null, it))
        }
    }

    @Test fun merchantMayShareTheTransactionDateLine() {
        val p = ExpenseExtractor.extract("국민카드", "국민카드 승인\n홍*동님\n5,600원 일시불\n09/17 12:34 스타벅스\n누적 100,000원")!!
        assertEquals("스타벅스", p.merchant)
        assertEquals(5600L, p.amount)
        assertEquals("카페", ExpenseExtractor.extract("카드 승인", "가맹점 카페\n5,000원")!!.merchant)
    }

    @Test fun refundsAreSeparateAndUnknownMerchantIsNotInvented() {
        val p = ExpenseExtractor.extract("승인 취소", "5,200원")!!
        assertTrue(p.refund)
        assertEquals("사용처 미확인", p.merchant)
        assertNull(ExpenseExtractor.extract("결제", "잔액 90,000원"))
    }

    @Test fun repeatedTitleDoesNotDuplicateAmount() {
        assertEquals(5200L, ExpenseExtractor.extract("5,200원 결제", "5,200원 결제\n가맹점: 카페")!!.amount)
    }

    @Test fun snapshotsAreIdempotentButSeparatePurchasesAreKept() {
        val n = item("승인 5,000원\n09/17 12:34\n가맹점: 카페")
        assertEquals(ExpenseExtractor.record(n)!!.fingerprint, ExpenseExtractor.record(n.copy(postedAt = 2000))!!.fingerprint)
        assertNotEquals(ExpenseExtractor.record(n)!!.fingerprint, ExpenseExtractor.record(n.copy(text = n.text!!.replace("12:34", "12:35")))!!.fingerprint)
        val untimed = item("승인 5,000원\n가맹점: 카페")
        assertNotEquals(ExpenseExtractor.record(untimed)!!.fingerprint, ExpenseExtractor.record(untimed.copy(postedAt = 2000))!!.fingerprint)
        assertNull(ExpenseExtractor.record(n.copy(kind = NotifRules.PROMO)))
    }

    private fun item(text: String) = NotifItem(key = "bank:1", packageName = "bank", appLabel = "은행", postedAt = 1000,
        category = null, title = "카드 승인", text = text, sender = null, conversationTitle = null,
        isGroupConversation = false, messages = null, kind = NotifRules.MONEY, amount = null, isQuestion = false, importance = 55)
}
