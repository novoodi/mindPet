package com.maeumpet.app.notif

import com.maeumpet.app.kev.KevOpinion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetBriefingTest {
    private val now = 1_790_000_000_000L
    private val hour = 3600_000L

    private fun kev(question: Float, request: Float) = ImportanceLearner.encode(
        ImportanceLearner.features(NotifRules.MESSAGE, "", null, false, null, now, KevOpinion(question, request)),
    )

    private fun msg(
        text: String, sender: String = "민수", at: Long = now - hour, features: String? = null,
        isQuestion: Boolean = false, removeReason: Int? = null, kind: String = NotifRules.MESSAGE, importance: Int = 50,
        removedAt: Long? = null,
    ) = NotifItem(
        key = "kakao:$sender", packageName = "com.kakao.talk", appLabel = "카카오톡", postedAt = at, category = null,
        title = sender, text = text, sender = sender, conversationTitle = null, isGroupConversation = false, messages = null,
        kind = kind, amount = null, isQuestion = isQuestion, importance = importance, removeReason = removeReason, features = features, removedAt = removedAt,
    )

    @Test
    fun kev_question_and_request_need_a_reply_and_chatter_does_not() {
        val r = PetBriefing.replies(
            listOf(
                msg("내일 회의 몇 시야?", sender = "민수", features = kev(0.99f, 0f)),
                msg("보고서 메일로 보내주세요", sender = "팀장님", features = kev(0f, 0.98f), importance = 70),
                msg("나 방금 도착했어 ㅋㅋ", sender = "지영", features = kev(0f, 0f)),
                msg("너는 그걸 꼭 지금 말해야 되냐?", sender = "친구", features = kev(0f, 0.16f), isQuestion = true),
            ),
            now,
        )
        assertEquals(listOf("팀장님", "민수"), r.map { it.who })   // importance first
    }

    @Test
    fun an_earlier_question_survives_a_later_chatter_message() {
        val r = PetBriefing.replies(
            listOf(
                msg("토요일에 시간 돼?", at = now - 2 * hour, features = kev(0.97f, 0f)),
                msg("ㅋㅋㅋ", at = now - hour, features = kev(0f, 0f)),
            ),
            now,
        )
        assertEquals(listOf("토요일에 시간 돼?"), r.map { it.text })
    }

    @Test
    fun opening_or_dismissing_the_chat_after_the_question_counts_as_handled() {
        val asked = msg("토요일에 시간 돼?", at = now - 2 * hour, features = kev(0.97f, 0f))
        assertTrue(PetBriefing.replies(listOf(asked, msg("ㅇㅇ?", at = now - hour, removeReason = 1)), now).isEmpty())
        assertTrue(PetBriefing.replies(listOf(asked.copy(removeReason = 2)), now).isEmpty())
        // 묶음째 밀어서 지움(12)도 사용자가 지운 것
        assertTrue(PetBriefing.replies(listOf(asked.copy(removeReason = 12)), now).isEmpty())
        // 앱이 스스로 지움(8): 곧바로 다시 올라오면 교체일 뿐 챙긴 게 아니다 (실측 2026-10-03, 아래 테스트 참고)
        val cancelled = asked.copy(removeReason = 8, removedAt = asked.postedAt + 60_000)
        assertEquals(1, PetBriefing.replies(listOf(cancelled, msg("ㅋㅋ", at = asked.postedAt + 62_000)), now).size)
    }

    @Test
    fun old_messages_and_non_message_kinds_are_left_out() {
        assertTrue(PetBriefing.replies(listOf(msg("어제 그거 됐어?", at = now - 25 * hour, features = kev(0.9f, 0f))), now).isEmpty())
        assertTrue(PetBriefing.replies(listOf(msg("지금 구매하세요", kind = NotifRules.PROMO, isQuestion = true)), now).isEmpty())
    }

    @Test
    fun without_kev_the_question_rule_still_works() {
        assertEquals(1, PetBriefing.replies(listOf(msg("언제 와?", isQuestion = true)), now).size)
    }

    @Test
    fun tasks_put_overdue_and_soon_first_then_undated() {
        val today = 20_000L
        fun todo(title: String, due: Long?, status: String = "OPEN", created: Long = 0) =
            TodoItem(sourceKey = title, createdAt = created, title = title, sourceLabel = "", sourceText = "", dueDay = due, status = status)
        val t = PetBriefing.tasks(
            listOf(
                todo("다음 주 일", today + 7), todo("기한 없음", null, created = 5), todo("내일 마감", today + 1),
                todo("어제 마감", today - 1), todo("끝난 일", today, status = "DONE"), todo("후보", today, status = "CANDIDATE"),
            ),
            today,
        )
        assertEquals(listOf("어제 마감", "내일 마감", "기한 없음"), t.map { it.title })
    }

    @Test
    fun plans_from_an_hour_ago_until_end_of_tomorrow() {
        fun plan(who: String, at: Long?, status: String = "CONFIRMED") =
            Plan(createdAt = 0, who = who, text = "", why = "", at = at, hasExplicitTime = true, type = "PROPOSE", status = status, source = "notif")
        val end = now + 30 * hour
        val p = PetBriefing.plans(
            listOf(plan("지난 약속", now - 2 * hour), plan("방금", now - 30 * 60_000L), plan("내일", now + 20 * hour),
                plan("모레", now + 40 * hour), plan("후보", now + hour, status = "CANDIDATE"), plan("미정", null)),
            now, end,
        )
        assertEquals(listOf("방금", "내일"), p.map { it.who })
    }

    @Test
    fun a_chat_read_in_kakao_is_handled_but_a_replaced_notification_is_not() {
        val asked = now - hour
        // 카톡에서 읽어서 앱이 알림을 지움 (다시 안 옴) → 챙긴 것
        val read = PetBriefing.replies(listOf(
            msg("내일 몇 시에 와?", at = asked, features = kev(0.99f, 0f), removeReason = 8, removedAt = asked + 5 * 60_000),
        ), now)
        assertTrue(read.isEmpty())
        // 새 메시지로 교체하느라 지웠다가 3초 뒤 다시 올림 → 아직 안 챙김
        val replaced = PetBriefing.replies(listOf(
            msg("내일 몇 시에 와?", at = asked, features = kev(0.99f, 0f), removeReason = 8, removedAt = asked + 60_000),
            msg("ㅋㅋ", at = asked + 63_000, features = kev(0f, 0f)),
        ), now)
        assertEquals(listOf("민수"), replaced.map { it.who })
    }

    @Test
    fun a_short_last_message_is_shown_with_the_line_before_it() {
        val m = msg("아냐", features = kev(0.82f, 0f)).copy(messages = MessageCodec.encode(listOf(
            MessageCodec.Message("민수", "경자전성시대라고", now - hour - 2000),
            MessageCodec.Message("민수", "아냐", now - hour),
        )))
        assertEquals("경자전성시대라고 → 아냐", PetBriefing.replies(listOf(m), now).single().text)
        assertEquals("내일 회의 몇 시야?", PetBriefing.replies(listOf(msg("내일 회의 몇 시야?", features = kev(0.99f, 0f))), now).single().text)
    }
}
