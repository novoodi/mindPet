package com.maeumpet.app.kev

import com.maeumpet.app.kev.KevFormat.Line
import org.junit.Assert.assertEquals
import org.junit.Test

/** 기대값은 dataset-evaluation/kev-notif-v1/kevfmt.py 의 출력 그대로다 (2026-10-03). 학습한 형식과 어긋나면 실패한다. */
class KevFormatTest {
    @Test fun groupChatKeepsLastFourContextLines() {
        val ctx = listOf(Line("민수", "내일 몇 시?"), Line("나", "7시"), Line("지민", "a"), Line("b", "c"), Line("d", "e"))
        assertEquals("[대화방] 단톡\n[앞 대화]\n나: 7시\n지민: a\nb: c\nd: e\n[마지막 메시지]\n지민: 그럼 정문에서 봐",
            KevFormat.chatState(true, ctx, Line("지민", "그럼 정문에서 봐")))
    }

    @Test fun chatWithoutContextHasNoContextHeader() {
        assertEquals("[대화방] 1:1\n[마지막 메시지]\n엄마: 우유 사 와", KevFormat.chatState(false, emptyList(), Line("엄마", "우유 사 와")))
    }

    @Test fun notificationWithAndWithoutTitle() {
        assertEquals("[앱] 토스\n[제목] 순금 복권\n[내용] 오늘 응모하지 않으면 사라져요.", KevFormat.notifState("토스", "순금 복권", "오늘 응모하지 않으면 사라져요."))
        assertEquals("[앱] 시계\n[내용] 오전 7:35", KevFormat.notifState("시계", "", "오전 7:35"))
        assertEquals("[앱] 시계\n[내용] 오전 7:35", KevFormat.notifState("시계", null, "오전 7:35"))
    }

    @Test fun instructionsMatchTraining() {
        assertEquals("Does the user need to see or act on this Korean phone notification today? Fake urgency in ads does not count.",
            KevFormat.TASKS[KevFormat.NOTIF_TIMELY])
        assertEquals("The last Korean chat message was sent to the phone owner (나). Does it ask 나 to do something? " +
            "Joking or exclamatory commands, story fragments, the sender's own promises, and requests aimed at someone else in a group chat do not count. " +
            "A request split across several consecutive messages counts.", KevFormat.TASKS[KevFormat.CHAT_REQUEST])
    }
}
