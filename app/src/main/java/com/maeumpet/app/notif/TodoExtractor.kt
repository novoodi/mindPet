package com.maeumpet.app.notif

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Suggestions only: a request is never considered an accepted obligation. */
object TodoExtractor {
    private val action = Regex("보내|보내기|제출|답장|회신|확인|구매|사다|사와|사 와|준비|예약|작성|검토|챙겨|전화|연락|납부|신청|결제")
    private val request = Regex("해\\s?줘|해\\s?주|해\\s?주세요|해주세요|주세요|(?:보내|챙겨|사다|사와|사 와)\\s?줘|부탁|바라|해야|해\\s?줄|줄래|주시|주실|주면|부탁드|요청|마감|까지")
    private val negative = Regex("하지\\s?마|안\\s?해도|하지\\s?않아도|필요\\s?없|취소|보냈|제출했|완료했|이미\\s|내가.{0,20}(?:할게|보낼게)|제가.{0,20}(?:하겠|보낼)")

    fun dueDate(text: String, received: LocalDate): LocalDate? {
        val relative = Regex("(?<![가-힣])(오늘|내일|모레)(?=까지|중|\\s|$|[.!?])").findAll(text).toList()
        val fullDates = Regex("(?<![0-9])(\\d{4})-(\\d{1,2})-(\\d{1,2})(?![0-9])").findAll(text).toList()
        val monthDates = Regex("(?<![0-9])(\\d{1,2})월\\s*(\\d{1,2})일").findAll(text).toList()
        if (relative.size + fullDates.size + monthDates.size > 1) return null
        relative.singleOrNull()?.let { return received.plusDays(when (it.groupValues[1]) { "내일" -> 1; "모레" -> 2; else -> 0 }) }
        fullDates.singleOrNull()?.let {
            return runCatching { LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }.getOrNull()
        }
        monthDates.singleOrNull()?.let {
            return runCatching { LocalDate.of(received.year, it.groupValues[1].toInt(), it.groupValues[2].toInt()) }.getOrNull()
        }
        return null
    }

    fun candidates(item: NotifItem): List<TodoItem> {
        if (item.kind !in setOf(NotifRules.MESSAGE, NotifRules.EMAIL)) return emptyList()
        val messages = MessageCodec.decode(item.messages).ifEmpty {
            listOf(MessageCodec.Message(item.sender.orEmpty(), item.text.orEmpty(), item.postedAt))
        }
        return messages.flatMap { message ->
            val text = message.text.trim()
            if (text.length < 4 || NotifRules.SECRET.containsMatchIn(text) || NotifRules.isPromoText(text) || negative.containsMatchIn(text)) return@flatMap emptyList()
            val sentences = text.split(Regex("[\\r\\n]+|(?<=[.!?])\\s+"))
                .map { it.trim() }.filter { action.containsMatchIn(it) && request.containsMatchIn(it) }.distinct()
            val timestamp = message.timestamp.takeIf { it > 0 } ?: item.postedAt
            val day = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
            sentences.map { sentence ->
            val due = (dueDate(sentence, day) ?: dueDate(text, day))?.toEpochDay()
            val normalized = sentence.replace(Regex("\\s+"), " ")
            val identity = "${item.packageName}\u0000${item.key}\u0000${message.sender}\u0000$day\u0000$normalized"
            val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
            TodoItem(sourceKey = hash, createdAt = timestamp, title = sentence.take(200),
                sourceLabel = "${item.appLabel} · ${message.sender.ifBlank { item.sender ?: item.title ?: "알림" }}",
                sourceText = text.take(1000), dueDay = due)
            }
        }.distinctBy { it.sourceKey }
    }
}
