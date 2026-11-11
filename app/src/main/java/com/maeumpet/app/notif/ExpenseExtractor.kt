package com.maeumpet.app.notif

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset

/** Conservative KRW payment extraction. Unknown merchants remain explicitly unknown. */
object ExpenseExtractor {
    data class Payment(val amount: Long, val merchant: String, val refund: Boolean)
    private val won = Regex("(?<![0-9.,])([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)\\s*원")
    private val excluded = Regex("실패|거절|예정|예상|청구|결제일|결제대금|납부|요청|취소\\s*요청|환불\\s*요청|입금|이체|송금|충전|페이충|오픈뱅킹|포인트|캐시백|적립|광고|쿠폰|할인|혜택|수신거부|USD|EUR|JPY|달러|엔화", RegexOption.IGNORE_CASE)
    private val event = Regex("승인|결제|사용")
    private val refund = Regex("취소|환불")
    private val explicitMerchant = Regex("(?:가맹점|사용처|이용처|결제처|상호|매장명)(?:\\s*[:：]\\s*|\\s+)([^\\n\\r]+)")
    private val dateTime = Regex("(?:[0-9]{4}[-./])?[0-9]{1,2}[-./][0-9]{1,2}\\s+[0-9]{1,2}:[0-9]{2}")

    fun extract(title: String?, body: String?): Payment? {
        val text = if (!title.isNullOrBlank() && body.orEmpty().contains(title)) body.orEmpty() else "${title.orEmpty()}\n${body.orEmpty()}"
        if (excluded.containsMatchIn(text) || NotifRules.isQuestion(text)) return null
        if (Regex("결제(?:할|하면|해\\s|하세요)|사용하면|승인되면|결제\\s*전").containsMatchIn(text)) return null
        if (!event.containsMatchIn(text) && !refund.containsMatchIn(text)) return null
        val amounts = won.findAll(text).filter { m ->
            val prefix = text.substring(maxOf(0, m.range.first - 12), m.range.first)
            !Regex("(?:잔액|누적|한도|총액|합계|누계)\\s*[:：]?\\s*$").containsMatchIn(prefix)
        }.toList()
        // Several unlabeled amounts are ambiguous; do not guess which was paid.
        if (amounts.size != 1) return null
        val amount = amounts.single().groupValues[1].replace(",", "").toLongOrNull()?.takeIf { it in 1..999_999_999_999L } ?: return null
        val merchant = explicitMerchant.find(text)?.groupValues?.get(1)?.let(::cleanMerchant)
            ?: text.lineSequence().map { it.replace(dateTime, "").trim() }.filter { line ->
                line.isNotBlank() && line != title && !Regex("승인|결제|사용|취소|환불|카드|잔액|누적|한도|일시불|할부|발신|[0-9]+원|[0-9]+:[0-9]+|\\*|https?://").containsMatchIn(line) &&
                    !won.containsMatchIn(line) && !Regex("^[0-9/ .:-]+$").matches(line) && !line.endsWith("님")
            }.lastOrNull()?.let(::cleanMerchant)
            ?: Regex("([가-힣a-zA-Z0-9][가-힣a-zA-Z0-9 .&_-]{1,40}?)에서\\s*(?:[0-9,]+\\s*원\\s*)?(?:결제|승인|사용)").find(text)?.groupValues?.get(1)?.trim()
            ?: "사용처 미확인"
        return Payment(amount, merchant, refund.containsMatchIn(text))
    }

    private fun cleanMerchant(value: String): String? = value.split(Regex("\\s+(?:잔액|누적|한도|일시불|할부|승인번호)"))[0].trim().take(80).takeIf { it.isNotBlank() }

    fun record(item: NotifItem): Expense? {
        if (item.kind != NotifRules.MONEY) return null
        val body = item.lastMessageText() ?: return null
        val payment = extract(item.title, body) ?: return null
        // Same source snapshot is idempotent. Exact transaction time permits update deduplication.
        val transactionTime = dateTime.find(body)?.value
        val year = Instant.ofEpochMilli(item.postedAt).atZone(ZoneOffset.UTC).year
        val identity = listOf(item.packageName, item.key, transactionTime?.let { "$year:$it" } ?: item.postedAt.toString(), item.title.orEmpty(), body.trim()).joinToString("\u0000")
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        return Expense(fingerprint = fingerprint, postedAt = item.postedAt, merchant = payment.merchant,
            amount = payment.amount, refund = payment.refund, appLabel = item.appLabel)
    }
}
