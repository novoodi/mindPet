package com.maeumpet.app.notif

/** Offline extractive summary: preserves wording and chronology, never invents facts. */
object NotificationSummary {
    private val key = Regex("변경|취소|마감|까지|부탁|해줘|해주세요|확인|회의|약속|배송|도착|결제|승인|환불|[?？]|오늘|내일|[0-9]+시")
    private val boilerplate = Regex("^\\s*(\\[Web발신]|\\[웹발신])\\s*", RegexOption.IGNORE_CASE)

    fun summarize(texts: List<String>): String {
        val sentences = texts.flatMap { it.replace(boilerplate, "").split(Regex("[\\r\\n]+|(?<=[.!?。？])\\s+")) }
            .map { it.trim().replace(Regex("[ \\t]+"), " ") }
            .filter { it.isNotBlank() && !NotifRules.SECRET.containsMatchIn(it) }
            .asReversed().distinct().asReversed()
        if (sentences.isEmpty()) return "요약할 내용이 없어."
        val relevant = (0 until sentences.lastIndex).maxByOrNull { i ->
            (if (key.containsMatchIn(sentences[i])) 100 else 0) + i.toDouble() / sentences.size
        }
        val chosen = listOfNotNull(relevant, sentences.lastIndex).sorted()
        return chosen.joinToString(" → ") { i ->
            sentences[i].let { if (it.length > 180) it.take(177) + "…" else it }
        }
    }

    fun summarize(item: NotifItem): String {
        val messages = MessageCodec.decode(item.messages)
        return summarize(if (messages.isEmpty()) listOf(item.text.orEmpty()) else messages.map { it.text })
    }
}
