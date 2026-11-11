package com.maeumpet.app.notif

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TodoOverviewTest {
    private val day = LocalDate.of(2026, 9, 17)
    private val time = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun item(text: String, key: String = "chat:1", at: Long = time) = NotifItem(key = key, packageName = "chat", appLabel = "메신저",
        postedAt = at, category = null, title = "친구", text = text, sender = "친구", conversationTitle = null,
        isGroupConversation = false, messages = null, kind = NotifRules.MESSAGE, amount = null, isQuestion = true, importance = 70)

    @Test fun requestBecomesCandidateWithOriginalAndRelativeDeadline() {
        val t = TodoExtractor.candidates(item("내일까지 서류를 보내줘")).single()
        assertEquals("CANDIDATE", t.status)
        assertEquals(day.plusDays(1).toEpochDay(), t.dueDay)
        assertEquals("내일까지 서류를 보내줘", t.sourceText)
        assertEquals("서류 보내줘", TodoExtractor.candidates(item("서류 보내줘")).single().title)
        assertNull(TodoExtractor.candidates(item("자료 검토 부탁해")).single().dueDay)
    }
    @Test fun doesNotTurnConversationOrCancelledRequestIntoTask() {
        listOf("내일 7시에 만나자", "자료 확인했어요", "제출하지 않아도 돼", "내일까지 서류 제출 취소", "광고 쿠폰 확인해 주세요", "인증번호 1234 확인해 줘", "내가 내일까지 보낼게").forEach {
            assertTrue(it, TodoExtractor.candidates(item(it)).isEmpty())
        }
        assertTrue(TodoExtractor.candidates(item("내일까지 서류 보내줘").copy(kind = NotifRules.MONEY)).isEmpty())
    }
    @Test fun cumulativeUpdatesKeepStableIdentityAndSourceDate() {
        val messages = MessageCodec.encode(listOf(MessageCodec.Message("친구", "내일까지 제출해 주세요", time)))
        val first = item("새 메시지").copy(messages = messages)
        val later = first.copy(postedAt = time + 86_400_000)
        assertEquals(TodoExtractor.candidates(first), TodoExtractor.candidates(later))
        assertNotEquals(TodoExtractor.candidates(first).single().sourceKey, TodoExtractor.candidates(first.copy(key = "another-room")).single().sourceKey)
    }
    @Test fun datesDoNotInventInvalidOrConflictingDeadlines() {
        assertEquals(day.plusDays(2), TodoExtractor.dueDate("모레까지 제출", day))
        assertEquals(LocalDate.of(2026, 10, 2), TodoExtractor.dueDate("2026-10-02까지 제출", day))
        assertEquals(LocalDate.of(2026, 9, 20), TodoExtractor.dueDate("9월 20일까지 제출", day))
        assertNull(TodoExtractor.dueDate("2월 30일까지 제출", day))
        assertNull(TodoExtractor.dueDate("오늘 말고 내일까지 제출", day))
    }
    @Test fun overviewUsesLatestSnapshotAndExcludesHandledNotifications() {
        val n = item("내일까지 확인해 줘")
        assertEquals(1, TodayOverview.attention(listOf(n, n.copy(postedAt = time + 1))).size)
        assertTrue(TodayOverview.attention(listOf(n, n.copy(postedAt = time + 1, removeReason = 1))).isEmpty())
        assertTrue(TodayOverview.attention(listOf(n.copy(removeReason = 2))).isEmpty())
    }
    @Test fun multipleRequestsKeepTheirOwnDeadlines() {
        val tasks = TodoExtractor.candidates(item("오늘까지 서류 보내줘. 내일까지 예약 확인해 주세요."))
        assertEquals(2, tasks.size)
        assertEquals(day.toEpochDay(), tasks[0].dueDay)
        assertEquals(day.plusDays(1).toEpochDay(), tasks[1].dueDay)
        assertNull(TodoExtractor.dueDate("9월 20일 또는 9월 21일까지 제출", day))
    }
    @Test fun overviewCountsOnlyAcceptedDueTasksAndNetSpending() {
        val t = TodoExtractor.candidates(item("오늘까지 제출해 주세요")).single()
        assertEquals(2, TodayOverview.dueTasks(listOf(t, t.copy(status = "OPEN"), t.copy(status = "DONE"),
            t.copy(status = "OPEN", dueDay = day.minusDays(1).toEpochDay()), t.copy(status = "OPEN", dueDay = null),
            t.copy(status = "OPEN", dueDay = day.plusDays(1).toEpochDay())), day.toEpochDay()).size)
        val e = Expense(fingerprint = "a", postedAt = time, merchant = "카페", amount = 5000, refund = false, appLabel = "카드")
        assertEquals(3000L, TodayOverview.netExpense(listOf(e, e.copy(refund = true, amount = 2000), e.copy(deleted = true))))
    }
}
