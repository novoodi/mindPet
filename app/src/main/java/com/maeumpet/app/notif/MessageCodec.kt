package com.maeumpet.app.notif

import java.util.Base64

/** Versioned records preserve tabs/newlines; legacy records remain readable. */
object MessageCodec {
    data class Message(val sender: String, val text: String, val timestamp: Long)
    private const val PREFIX = "MP2\n"
    fun encode(messages: List<Message>): String = PREFIX + messages.joinToString("\n") {
        "${encodeField(it.sender)}\t${encodeField(it.text)}\t${it.timestamp}"
    }
    private fun encodeField(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
    private fun decodeField(s: String) = String(Base64.getDecoder().decode(s), Charsets.UTF_8)

    fun decode(raw: String?): List<Message> {
        if (raw.isNullOrBlank()) return emptyList()
        if (raw.startsWith(PREFIX)) return runCatching {
            raw.removePrefix(PREFIX).lines().filter { it.isNotEmpty() }.map {
                val parts = it.split('\t')
                require(parts.size == 3)
                Message(decodeField(parts[0]), decodeField(parts[1]), parts[2].toLong())
            }
        }.getOrDefault(emptyList())
        // A legacy record ends in a millisecond timestamp, not at every newline.
        val result = mutableListOf<Message>()
        var start = 0
        for (end in Regex("\\t[0-9]{13}(?:\\n|$)").findAll(raw)) {
            val record = raw.substring(start, end.range.first)
            val separator = record.indexOf('\t')
            if (separator < 0) return emptyList()
            result += Message(record.substring(0, separator), record.substring(separator + 1), end.value.trim().toLong())
            start = end.range.last + 1
        }
        return if (start == raw.length) result else emptyList()
    }
}
