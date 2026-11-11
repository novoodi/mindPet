package com.maeumpet.app.notif

object BriefingPolicy {
    enum class MoneyStatus(val label: String) {
        FAILED("실패"), REFUND("취소·환불"), REWARD("적립"), TRANSFER("이체·충전"),
        INCOME("입금"), PAYMENT("결제"), WITHDRAWAL("출금"), OTHER("기타 금융")
    }
    fun moneyStatus(text: String): MoneyStatus = when {
        Regex("실패|거절|잔액.{0,5}부족").containsMatchIn(text) -> MoneyStatus.FAILED
        Regex("취소|환불").containsMatchIn(text) -> MoneyStatus.REFUND
        Regex("적립|캐시백").containsMatchIn(text) -> MoneyStatus.REWARD
        Regex("이체|송금|충전|페이충|오픈뱅킹").containsMatchIn(text) -> MoneyStatus.TRANSFER
        "입금" in text -> MoneyStatus.INCOME
        Regex("결제|승인|체크카드출금").containsMatchIn(text) -> MoneyStatus.PAYMENT
        "출금" in text -> MoneyStatus.WITHDRAWAL
        else -> MoneyStatus.OTHER
    }

    /** needsReply: Kev의 질문·요청 판단(ImportanceLearner.needsReply). null이면 물음표·키워드 규칙. */
    /** timely: Kev가 "오늘 봐야 함"으로 본 카톡 외 알림이면 true, 판단이 없으면 null(규칙만). */
    fun eligible(kind: String, body: String, importance: Int, needsReply: Boolean? = null, timely: Boolean? = null): Boolean {
        val asks = needsReply ?: NotifRules.isQuestion(body)
        val compact = body.replace(Regex("[\\s\\p{P}~]"), "")
        if (compact.isEmpty() || compact.all { it.isDigit() }) return false
        if (Regex("[ㅇㅋㅎㅠㅜ]+|응+|네+|넵+|웅+|어흐|화긴스|확인|확인했어|알겠어|ok|okay", RegexOption.IGNORE_CASE).matches(compact)) return false
        if (timely == true && kind != NotifRules.PROMO && kind != NotifRules.MESSAGE) return true
        return when (kind) {
            NotifRules.MONEY -> moneyStatus(body) != MoneyStatus.REWARD
            NotifRules.DELIVERY, NotifRules.CALENDAR -> true
            NotifRules.MESSAGE -> importance >= 55 && (asks || NotifRules.hasTime(body))
            NotifRules.EMAIL -> importance >= 50 && (asks || NotifRules.hasTime(body))
            else -> false
        }
    }
}
