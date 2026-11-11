package com.maeumpet.app.notif

/** Pure, deterministic grouping. Counts refer to observed messages, never total chat history. */
object AwayDigest {
    const val MIN_AWAY_MS = 15 * 60_000L

    fun returnStart(awayAt: Long, lastBriefingAt: Long, now: Long): Long? =
        if (awayAt <= 0 || now - awayAt < MIN_AWAY_MS) null
        else maxOf(awayAt, lastBriefingAt).takeIf { it < now }

    data class Conversation(val item: NotifItem, val messages: List<MessageCodec.Message>) {
        val name: String get() = if (item.isGroupConversation)
            item.conversationTitle?.takeIf { it.isNotBlank() } ?: item.title ?: "단체 대화"
        else item.sender?.takeIf { it.isNotBlank() } ?: item.title ?: item.appLabel
        // Kev가 이 알림의 마지막 메시지를 봤으면 그 판단을, 아니면 물음표·키워드 규칙을 쓴다. 일정 표현은 규칙.
        val needsAttention: Boolean get() =
            (ImportanceLearner.needsReply(item.features) ?: messages.any { NotifRules.isQuestion(it.text) }) ||
                messages.any { NotifRules.hasTime(it.text) }
        fun excerpt(): String = messages.takeLast(3).joinToString(" → ") {
            val author = if (item.isGroupConversation && it.sender.isNotBlank()) "${it.sender}: " else ""
            author + it.text.replace('\n', ' ').take(120)
        }
    }

    fun conversations(rows: List<NotifItem>, since: Long, until: Long): List<Conversation> {
        val fresh = rows.filter { it.postedAt > since && it.postedAt <= until }
        // A clicked notification's earlier snapshots should not reappear as unseen news.
        val clickedThrough = fresh.filter { it.clicked }.groupBy { it.key }
            .mapValues { (_, items) -> items.maxOf { it.postedAt } }
        return fresh.filter { it.postedAt > (clickedThrough[it.key] ?: Long.MIN_VALUE) &&
            it.kind in setOf(NotifRules.MESSAGE, NotifRules.EMAIL) }
            .groupBy {
                // Notification keys keep separate rooms/mail threads with identical display names apart.
                listOf(it.packageName, it.kind, it.key)
            }.values.mapNotNull { snapshots ->
                val latest = snapshots.maxBy { it.postedAt }
                val cutoff = maxOf(since, clickedThrough[latest.key] ?: since)
                val messages = snapshots.sortedBy { it.postedAt }.flatMap { n ->
                    val decoded = MessageCodec.decode(n.messages)
                    if (decoded.isNotEmpty()) decoded.map {
                        if (it.timestamp <= 0) it.copy(timestamp = n.postedAt) else it
                    }.filter { it.timestamp > cutoff && it.timestamp <= until }
                    else listOf(MessageCodec.Message(n.sender.orEmpty(), n.text.orEmpty(), n.postedAt))
                }.filter { it.text.isNotBlank() && !NotifRules.SECRET.containsMatchIn(it.text) }
                    .distinctBy { Triple(it.sender, it.text, it.timestamp) }.sortedBy { it.timestamp }
                if (messages.isEmpty()) null else Conversation(latest, messages)
            }.sortedWith(compareByDescending<Conversation> { it.needsAttention }
                .thenByDescending { it.item.importance }.thenByDescending { it.item.postedAt })
    }
}
