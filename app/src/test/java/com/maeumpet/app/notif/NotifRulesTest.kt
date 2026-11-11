package com.maeumpet.app.notif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifRulesTest {
    @Test fun onlyMessengerAppsAreMessages() {
        assertEquals(NotifRules.MESSAGE, NotifRules.kind("com.kakao.talk", "msg", "민수", "내일 몇 시?", true))
        // 듀오링고는 대화형 알림으로 "팔로우" 유도를 보낸다 (실제 폰, 2026-10-03)
        assertEquals(NotifRules.OTHER, NotifRules.kind("com.duolingo", "msg", "😎 이지콩 followed you!", "Someone’s popular… Follow back!", true))
        assertEquals(NotifRules.MESSAGE, NotifRules.kind("com.towneers.www", "msg", "당근", "지금 도착했어요", true))
    }

    @Test fun redactedContentIsRecognized() {
        assertTrue(NotifRules.REDACTED.containsMatchIn("민감한 알림 콘텐츠 숨김"))
        assertTrue(NotifRules.REDACTED.containsMatchIn("Sensitive notification content hidden"))
    }
}
