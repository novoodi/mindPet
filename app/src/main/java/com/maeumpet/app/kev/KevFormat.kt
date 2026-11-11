package com.maeumpet.app.kev

/**
 * Kev 입력 문장(state)과 질문문. dataset-evaluation/kev-notif-v1/kevfmt.py 와 글자 하나까지 같아야 학습한 대로 동작한다.
 * 바꾸면 Python 쪽도 함께 바꾸고 다시 학습할 것. KevFormatTest가 두 쪽의 출력을 고정한다.
 */
object KevFormat {
    const val CHAT_CONTEXT_MAX = 4

    const val CHAT_QUESTION = "chat_question"
    const val CHAT_REQUEST = "chat_request"
    const val NOTIF_PROMO = "notif_promo"
    const val NOTIF_TIMELY = "notif_timely"

    val TASKS = linkedMapOf(
        CHAT_QUESTION to "The last Korean chat message was sent to the phone owner (나). Does it ask 나 a genuine question that needs an answer? " +
            "Rhetorical questions, self-talk about the sender's own matters, unfinished story fragments, and questions aimed at someone else in a group chat do not count.",
        CHAT_REQUEST to "The last Korean chat message was sent to the phone owner (나). Does it ask 나 to do something? " +
            "Joking or exclamatory commands, story fragments, the sender's own promises, and requests aimed at someone else in a group chat do not count. " +
            "A request split across several consecutive messages counts.",
        NOTIF_PROMO to "Is this Korean phone notification mainly trying to pull the user into the app for the sender's benefit, " +
            "such as ads, discounts, coupons, events, points to claim, recommendations, or engagement nudges?",
        NOTIF_TIMELY to "Does the user need to see or act on this Korean phone notification today? " +
            "Fake urgency in ads does not count.",
    )

    data class Line(val who: String, val text: String)

    fun chatState(group: Boolean, context: List<Line>, target: Line): String {
        val lines = mutableListOf("[대화방] ${if (group) "단톡" else "1:1"}")
        val ctx = context.takeLast(CHAT_CONTEXT_MAX)
        if (ctx.isNotEmpty()) {
            lines += "[앞 대화]"
            ctx.forEach { lines += "${it.who}: ${it.text}" }
        }
        lines += "[마지막 메시지]"
        lines += "${target.who}: ${target.text}"
        return lines.joinToString("\n")
    }

    fun notifState(app: String, title: String?, text: String): String {
        val lines = mutableListOf("[앱] $app")
        if (!title.isNullOrEmpty()) lines += "[제목] $title"
        lines += "[내용] $text"
        return lines.joinToString("\n")
    }
}
