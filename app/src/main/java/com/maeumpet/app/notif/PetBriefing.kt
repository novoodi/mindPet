package com.maeumpet.app.notif

/**
 * 펫을 눌렀을 때 보여 줄 세 묶음: 답장할 메시지, 잊으면 안 되는 일, 다가오는 일정.
 * DB를 읽지 않는 순수 함수라 테스트로 고정한다(읽기는 PetOverlay).
 */
object PetBriefing {
    const val REPLY_WINDOW_MS = 24 * 3600 * 1000L
    const val MAX_ITEMS = 3

    data class Reply(val who: String, val appLabel: String, val packageName: String, val text: String, val postedAt: Long)

    data class Content(val replies: List<Reply>, val tasks: List<TodoItem>, val plans: List<Plan>) {
        val count: Int get() = replies.size + tasks.size + plans.size
    }

    /**
     * 대화방마다 Kev(없으면 규칙)가 "질문·요청"이라고 본 마지막 메시지를 찾는다. 그 뒤에 같은 대화방 알림을
     * 눌러 열었거나 지웠으면 이미 챙긴 것으로 본다. 마지막 메시지가 "ㅋㅋ"여도 앞의 질문은 남는다.
     */
    fun replies(rows: List<NotifItem>, now: Long): List<Reply> = rows
        .filter { (it.kind == NotifRules.MESSAGE || it.kind == NotifRules.EMAIL) && now - it.postedAt in 0..REPLY_WINDOW_MS }
        .groupBy(::conversationKey)
        .values
        .mapNotNull { conversation ->
            val asked = conversation.filter { ImportanceLearner.needsReply(it.features) ?: it.isQuestion }
                .maxByOrNull { it.postedAt } ?: return@mapNotNull null
            val handled = handledTimes(conversation).any { it >= asked.postedAt }
            if (handled) null else asked
        }
        .sortedWith(compareByDescending<NotifItem> { it.importance }.thenByDescending { it.postedAt })
        .map {
            Reply(
                who = listOf(it.sender, it.conversationTitle, it.title).firstOrNull { n -> !n.isNullOrBlank() } ?: it.appLabel,
                appLabel = it.appLabel,
                packageName = it.packageName,
                text = replyText(it),
                postedAt = it.postedAt,
            )
        }

    /** 마지막 메시지가 이보다 짧으면("아냐", "에?") 그 말만으로는 뭘 묻는지 몰라서 바로 앞 줄을 붙여 보여 준다. */
    const val SHORT_REPLY = 8

    fun replyText(n: NotifItem): String {
        val messages = MessageCodec.decode(n.messages)
        val last = (messages.lastOrNull()?.text ?: n.text).orEmpty().replace('\n', ' ')
        val before = messages.getOrNull(messages.size - 2)?.text?.replace('\n', ' ')
        return if (last.length < SHORT_REPLY && !before.isNullOrBlank()) "$before → $last" else last
    }

    /** 같은 대화방으로 묶는 기준 */
    fun conversationKey(it: NotifItem): Pair<String, String> = it.packageName to (it.conversationTitle ?: it.sender ?: it.title ?: it.key)

    /**
     * 답장 목록에 영향을 줄 수 있는 메시지: 그 대화방을 마지막으로 열거나 지운 뒤에 온 것만.
     * 그 전 메시지는 질문이었어도 이미 챙긴 것으로 보므로 다시 판단할 필요가 없다 (KevRescore).
     */
    fun unhandledMessages(rows: List<NotifItem>): List<NotifItem> = rows
        .groupBy(::conversationKey).values
        .flatMap { conv ->
            val lastHandled = handledTimes(conv).maxOrNull() ?: Long.MIN_VALUE
            conv.filter { it.postedAt > lastHandled }
        }

    /** 앱이 알림을 지운 뒤 이 시간 안에 같은 대화방 알림이 다시 오면, 읽은 게 아니라 새 메시지로 교체한 것이다. */
    const val REPOST_MS = 10_000L

    /**
     * 사용자가 이 대화방을 챙긴 시점(알림 시각). 알림을 눌러 열었거나 밀어서 지웠을 때, 그리고
     * 메신저가 알림을 스스로 지웠는데(REASON_APP_CANCEL) 곧바로 다시 오지 않았을 때 — 카톡은 사용자가 앱에서 대화방을 읽으면
     * 알림을 지운다. 실측(2026-10-03): 카톡 지움 399번 중 183번은 10초 안에 다시 올라온 교체였다.
     */
    fun handledTimes(conversation: List<NotifItem>): List<Long> = conversation.filter { n ->
        n.clicked || n.dismissed || (n.readInApp && conversation.none { o ->
            val at = n.removedAt ?: return@none false
            o.postedAt > at && o.postedAt <= at + REPOST_MS
        })
    }.map { it.postedAt }

    /** 기한이 지났거나 오늘·내일까지인 할 일을 먼저, 남는 자리는 기한 없는 할 일로 채운다. */
    fun tasks(rows: List<TodoItem>, today: Long, limit: Int = MAX_ITEMS): List<TodoItem> {
        val open = rows.filter { it.status == "OPEN" }
        val soon = open.filter { it.dueDay != null && it.dueDay <= today + 1 }.sortedBy { it.dueDay }
        val rest = open.filter { it.dueDay == null }.sortedByDescending { it.createdAt }
        return (soon + rest).take(limit)
    }

    /** 확정된 약속 중 지금(1시간 전부터)부터 내일 끝까지. */
    fun plans(rows: List<Plan>, now: Long, endOfTomorrow: Long, limit: Int = MAX_ITEMS): List<Plan> = rows
        .filter { it.status == "CONFIRMED" && it.at != null && it.at >= now - 3600_000L && it.at < endOfTomorrow }
        .sortedBy { it.at }
        .take(limit)

    /** 펫 카드는 묶음마다 [MAX_ITEMS]개, 앱 첫 화면은 limit = Int.MAX_VALUE로 전부 */
    fun content(notifs: List<NotifItem>, todos: List<TodoItem>, plans: List<Plan>, now: Long, today: Long, endOfTomorrow: Long, limit: Int = MAX_ITEMS) =
        Content(replies(notifs, now).take(limit), tasks(todos, today, limit), plans(plans, now, endOfTomorrow, limit))

    /** 펫이 말하는 첫 줄 */
    fun headline(c: Content): String = when {
        c.count == 0 -> "지금은 챙길 게 없어. 편하게 있어."
        c.replies.isNotEmpty() -> "답장 기다리는 사람이 있어!"
        c.plans.isNotEmpty() -> "곧 약속이 있어."
        else -> "잊으면 안 되는 일이 있어."
    }
}
