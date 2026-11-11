package com.maeumpet.app.service

import org.junit.Assert.*
import org.junit.Test

class HomePopupPolicyTest {
    private fun allowed(home: Boolean = true, unlocked: Boolean = true, enabled: Boolean = true,
                        pending: Long = 2, shown: Long = 1,
                        created: Long = 1000, now: Long = 2000) =
        HomePopupPolicy.canShow(home, unlocked, enabled, pending, shown, created, now)

    @Test fun freshBriefingAppearsOnUnlockedHome() { assertTrue(allowed()) }
    @Test fun privateContentIsBlockedOnLockAndOtherApps() {
        assertFalse(allowed(home = false))
        assertFalse(allowed(unlocked = false))
    }
    @Test fun respectsSwitch() {
        assertFalse(allowed(enabled = false))
    }
    @Test fun shownBriefingDoesNotReopen() {
        assertFalse(allowed(shown = 2))
        assertFalse(allowed(shown = 3))
        assertFalse(allowed(pending = 0))
    }
    @Test fun rejectsExpiredAndFutureBriefings() {
        assertFalse(allowed(now = 1000 + 24 * 3600_000L))
        assertFalse(allowed(now = 999))
        assertFalse(allowed(created = 0))
    }
}
