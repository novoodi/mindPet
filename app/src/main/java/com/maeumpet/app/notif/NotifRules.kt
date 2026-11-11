package com.maeumpet.app.notif

import android.app.Notification
import com.maeumpet.app.kev.NotifOpinion

/**
 * 알림의 종류와 중요도를 정하는 규칙. 첫 버전은 전부 규칙이고,
 * 사용자가 열어 본 것/지운 것이 쌓이면 보낸 사람별 통계로 개인화된다.
 */
object NotifRules {

    const val MESSAGE = "MESSAGE"
    const val EMAIL = "EMAIL"
    const val MONEY = "MONEY"
    const val DELIVERY = "DELIVERY"
    const val CALENDAR = "CALENDAR"
    const val PROMO = "PROMO"
    const val OTHER = "OTHER"

    val MESSENGERS = setOf(
        "com.kakao.talk", "com.samsung.android.messaging", "com.google.android.apps.messaging", "com.android.mms",
        "jp.naver.line.android", "org.telegram.messenger", "com.discord", "com.instagram.android", "com.Slack",
        "com.whatsapp", "com.facebook.orca",
        // 사람끼리 대화하는 앱
        "com.towneers.www", "kr.co.quicket", "com.nhn.android.band",
    )
    val MAIL_APPS = setOf(
        "com.google.android.gm", "com.nhn.android.mail", "com.microsoft.office.outlook", "com.samsung.android.email.provider",
        "com.kakao.mail", "net.daum.android.mail",
    )
    val CALENDAR_APPS = setOf("com.google.android.calendar", "com.samsung.android.calendar", "com.android.calendar")

    /** 인증번호·OTP 는 아예 저장하지 않는다 */
    /** 시스템이 내용을 가린 알림(주로 인증번호 문자). 내용이 없으니 저장하지 않는다. */
    val REDACTED = Regex("""민감한\s*알림\s*콘텐츠\s*숨김|sensitive notification content hidden""", RegexOption.IGNORE_CASE)

    val SECRET = Regex("""인증\s*번호|인증코드|인증 코드|OTP|verification code|일회용\s*비밀번호|보안카드""", RegexOption.IGNORE_CASE)

    private val MONEY_WORDS = Regex("""승인|결제|출금|입금|이체|송금|잔액|카드|페이|납부|청구|환불""")
    private val AMOUNT = Regex("""([0-9]{1,3}(,[0-9]{3})+|[0-9]+)\s*원""")
    private val DELIVERY_WORDS = Regex("""택배|배송|배달|도착했|출발했|픽업|배차|라이더|기사님|운송장""")
    private val PROMO_WORDS = Regex("""\(광고\)|\[광고\]|광고|쿠폰|할인|이벤트|프로모션|세일|무료 수신|수신거부|혜택""")
    private val QUESTION = Regex("""\?|언제|어디|어떻|할래|할까|될까|가능해|가능할|괜찮아\??|부탁|해줘|해 줘|해주세요|알려줘|알려 줘|확인해|답장|답 좀|답 줘|어때|뭐해|뭐 해|올래|갈래|볼래|먹을래""")
    private val TIME_WORDS = Regex("""오늘|내일|모레|이번 주|이번주|주말|[0-9]{1,2}\s*시|마감|까지|약속|미팅|회의|면접""")

    fun kind(pkg: String, category: String?, title: String?, text: String?, hasMessages: Boolean): String {
        val all = "${title.orEmpty()} ${text.orEmpty()}"
        if (category == Notification.CATEGORY_PROMO) return PROMO
        // 대화형 알림(MessagingStyle, 메시지 카테고리)은 듀오링고 같은 앱도 참여 유도에 쓴다. 메신저 앱만 메시지로 보고,
        // 나머지는 일반 알림으로 두어 Kev가 광고·유도인지 판단하게 한다.
        if (pkg in MESSENGERS) {
            // 카톡 알림톡으로 오는 결제 안내
            return if (MONEY_WORDS.containsMatchIn(all) && AMOUNT.containsMatchIn(all) && !QUESTION.containsMatchIn(text.orEmpty())) MONEY else MESSAGE
        }
        if (category == Notification.CATEGORY_EMAIL || pkg in MAIL_APPS) return EMAIL
        if (category == Notification.CATEGORY_EVENT || category == Notification.CATEGORY_REMINDER || category == Notification.CATEGORY_ALARM || pkg in CALENDAR_APPS) return CALENDAR
        if (MONEY_WORDS.containsMatchIn(all) && AMOUNT.containsMatchIn(all)) return MONEY
        if (DELIVERY_WORDS.containsMatchIn(all)) return DELIVERY
        if (PROMO_WORDS.containsMatchIn(all)) return PROMO
        return OTHER
    }

    fun amount(text: String?): Long? {
        val m = AMOUNT.find(text ?: return null) ?: return null
        return m.groupValues[1].replace(",", "").toLongOrNull()
    }

    fun isQuestion(text: String?): Boolean = text != null && QUESTION.containsMatchIn(text)

    fun hasTime(text: String?): Boolean = text != null && TIME_WORDS.containsMatchIn(text)

    fun isPromoText(text: String?): Boolean = text != null && PROMO_WORDS.containsMatchIn(text)

    /** 0..100. 높을수록 아침에 먼저 보여 준다. */
    /** needsReply: Kev의 질문·요청 판단. Kev가 돌지 않았으면 물음표·키워드 규칙. */
    fun importance(
        kind: String, text: String?, amount: Long?, isGroup: Boolean, stat: SenderStat?, needsReply: Boolean = isQuestion(text),
        notifKev: NotifOpinion? = null,
    ): Int {
        var s = when (kind) {
            CALENDAR -> 60
            MONEY -> 55
            MESSAGE -> 50
            DELIVERY -> 45
            EMAIL -> 40
            OTHER -> 20
            else -> 5
        }
        if (needsReply) s += 15
        if (hasTime(text)) s += 10
        if (amount != null) s += when {
            amount >= 100_000 -> 15
            amount >= 30_000 -> 10
            amount >= 10_000 -> 5
            else -> 0
        }
        if (isPromoText(text)) s -= 20
        // Kev(카톡 외 알림): 오늘 봐야 하면 올리고, 광고·유도면 내린다. 규칙의 광고 문구 감점과 겹치지 않게 한쪽만.
        if (notifKev != null) {
            if (notifKev.timely >= ImportanceLearner.NOTIF_YES) s += 15
            if (notifKev.promo >= ImportanceLearner.NOTIF_YES && !isPromoText(text)) s -= 20
        }
        if (isGroup) s -= 5
        if (stat != null) {
            s += minOf(15, stat.count / 2)               // 자주 오는 사람
            s += ((stat.clickRate - 0.5f) * 30).toInt()   // 내가 열어 보는 사람은 +, 지우는 사람은 -
        }
        return s.coerceIn(0, 100)
    }

    fun kindLabel(kind: String) = when (kind) {
        MESSAGE -> "메시지"
        EMAIL -> "메일"
        MONEY -> "결제"
        DELIVERY -> "배송"
        CALENDAR -> "일정"
        PROMO -> "광고"
        else -> "기타"
    }
}
