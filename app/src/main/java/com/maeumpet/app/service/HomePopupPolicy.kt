package com.maeumpet.app.service

object HomePopupPolicy {
    fun canShow(home: Boolean, unlocked: Boolean, enabled: Boolean,
                pendingId: Long, shownId: Long, createdAt: Long, now: Long): Boolean =
        home && unlocked && enabled && pendingId > shownId &&
            createdAt > 0 && now >= createdAt && now - createdAt < 24 * 3600_000L
}
