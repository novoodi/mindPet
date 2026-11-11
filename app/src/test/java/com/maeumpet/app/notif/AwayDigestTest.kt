package com.maeumpet.app.notif

import org.junit.Assert.*
import org.junit.Test

class AwayDigestTest {
    private fun row(key: String = "room", at: Long = 200, text: String = "안녕",
                    messages: List<MessageCodec.Message> = emptyList(), kind: String = NotifRules.MESSAGE,
                    clicked: Boolean = false, group: Boolean = false, sender: String = "민수") = NotifItem(
        key = key, packageName = "chat", appLabel = "카카오톡", postedAt = at,
        category = null, title = "민수", text = text, sender = sender,
        conversationTitle = if (group) "친구방" else null, isGroupConversation = group,
        messages = if (messages.isEmpty()) null else MessageCodec.encode(messages), kind = kind,
        amount = null, isQuestion = false, importance = 40, removeReason = if (clicked) 1 else null)

    @Test fun onlyActualAbsenceTriggersReturn() {
        assertNull(AwayDigest.returnStart(0, 0, 2_000_000))
        assertNull(AwayDigest.returnStart(100, 0, 101))
        assertEquals(500L, AwayDigest.returnStart(100, 500, 1_000_100))
        assertNull(AwayDigest.returnStart(100, 1_000_100, 1_000_100))
    }

    @Test fun rollingSnapshotsKeepChangesOnceAndExcludeOldHistory() {
        val old = MessageCodec.Message("민수", "어제 얘기", 50)
        val first = MessageCodec.Message("민수", "내일 7시 보자", 150)
        val change = MessageCodec.Message("민수", "아니 8시로 바꿔", 250)
        val rows = listOf(row(at = 200, messages = listOf(old, first)),
            row(at = 300, messages = listOf(old, first, change)))
        val c = AwayDigest.conversations(rows, 100, 300).single()
        assertEquals(listOf(first, change), c.messages)
        assertTrue(c.excerpt().indexOf("7시") < c.excerpt().indexOf("8시"))
        assertTrue(c.needsAttention)
        assertTrue(AwayDigest.conversations(rows, 300, 400).isEmpty())
    }

    @Test fun mailWithoutQuestionIsStillIncluded() {
        val c = AwayDigest.conversations(listOf(row(kind = NotifRules.EMAIL, text = "견적서 수정본 첨부합니다")), 100, 300)
        assertEquals(1, c.size)
    }

    @Test fun separateRoomsWithSameNameNeverMerge() {
        assertEquals(2, AwayDigest.conversations(listOf(row(key = "one"), row(key = "two")), 100, 300).size)
    }

    @Test fun groupAuthorsStayInOneRoom() {
        val c = AwayDigest.conversations(listOf(row(group = true, messages = listOf(
            MessageCodec.Message("민수", "어디야?", 150), MessageCodec.Message("지연", "잠실역", 180)))), 100, 300).single()
        assertEquals("친구방", c.name)
        assertTrue(c.excerpt().contains("지연: 잠실역"))
    }

    @Test fun clickedAndSecretContentDoNotReappear() {
        assertTrue(AwayDigest.conversations(listOf(row(at = 150), row(clicked = true)), 100, 300).isEmpty())
        assertTrue(AwayDigest.conversations(listOf(row(text = "인증번호 123456")), 100, 300).isEmpty())
    }

    @Test fun newMessageAfterClickStillAppears() {
        val c = AwayDigest.conversations(listOf(row(at = 150, clicked = true),
            row(at = 200, text = "새로운 소식")), 100, 300).single()
        assertEquals("새로운 소식", c.messages.single().text)
    }

    @Test fun missingMessageTimestampUsesObservedTime() {
        val c = AwayDigest.conversations(listOf(row(messages = listOf(
            MessageCodec.Message("민수", "시간 없는 메시지", 0)))), 100, 300).single()
        assertEquals(200L, c.messages.single().timestamp)
    }
}
