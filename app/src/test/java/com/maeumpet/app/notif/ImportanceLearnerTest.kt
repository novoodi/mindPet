package com.maeumpet.app.notif

import com.maeumpet.app.kev.KevOpinion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ImportanceLearnerTest {

    private fun features(kind: String, kev: KevOpinion?) =
        ImportanceLearner.features(kind, "내일까지 자료 보내줄 수 있어?", null, false, null, 1_790_000_000_000L, kev)

    @Test
    fun feature_count_matches_declared_dim_with_and_without_kev() {
        assertEquals(ImportanceLearner.FEATURE_DIM, features(NotifRules.MESSAGE, KevOpinion(0.1f, 0.9f)).size)
        assertEquals(ImportanceLearner.FEATURE_DIM, features(NotifRules.PROMO, null).size)
    }

    @Test
    fun kev_values_sit_in_the_last_three_slots() {
        val f = features(NotifRules.MESSAGE, KevOpinion(0.25f, 0.75f))
        val n = f.size
        assertEquals(0.25f, f[n - 3], 0f)
        assertEquals(0.75f, f[n - 2], 0f)
        assertEquals(1f, f[n - 1], 0f)

        val none = features(NotifRules.PROMO, null)
        assertEquals(0f, none[n - 3], 0f)
        assertEquals(0f, none[n - 2], 0f)
        assertEquals(0f, none[n - 1], 0f)
    }

    private fun stored(kev: KevOpinion?) =
        ImportanceLearner.encode(features(if (kev != null) NotifRules.MESSAGE else NotifRules.PROMO, kev))

    @Test
    fun needs_reply_comes_from_kev_when_kev_ran() {
        assertEquals(true, ImportanceLearner.needsReply(stored(KevOpinion(0.98f, 0.01f))))    // question
        assertEquals(true, ImportanceLearner.needsReply(stored(KevOpinion(0.01f, 0.97f))))    // request
        assertEquals(false, ImportanceLearner.needsReply(stored(KevOpinion(0.0f, 0.16f))))    // rhetorical "꼭 지금 말해야 되냐?"
    }

    @Test
    fun needs_reply_is_unknown_without_kev_so_rules_decide() {
        assertNull(ImportanceLearner.needsReply(stored(null)))           // Kev did not run
        assertNull(ImportanceLearner.needsReply(null))                   // no features
        assertNull(ImportanceLearner.needsReply(List(23) { "0.5" }.joinToString(",")))  // old E8 layout
    }

    @Test
    fun briefing_follows_kev_over_the_question_mark_rule() {
        val rhetorical = "너는 그걸 꼭 지금 말해야 되냐?"
        assertTrue(BriefingPolicy.eligible(NotifRules.MESSAGE, rhetorical, 70))                 // rule: '?' -> asks
        assertFalse(BriefingPolicy.eligible(NotifRules.MESSAGE, rhetorical, 70, needsReply = false))
        val request = "보고서 메일로 보내주세요"   // no time words, so only the reply signal can make it eligible
        assertFalse(BriefingPolicy.eligible(NotifRules.MESSAGE, request, 70))                   // rule misses it
        assertTrue(BriefingPolicy.eligible(NotifRules.MESSAGE, request, 70, needsReply = true))
    }

    @Test
    fun weights_saved_in_the_old_emotion_layout_are_dropped() {
        val old = ImportanceLearner.Model(FloatArray(23) { 1f }, 0f)   // E8 layout
        val current = ImportanceLearner.Model(FloatArray(ImportanceLearner.FEATURE_DIM) { 1f }, 0f)
        assertNull(ImportanceLearner.usable(old))
        assertNotNull(ImportanceLearner.usable(current))
        // without a usable model the score is the rule score
        assertEquals(40, ImportanceLearner.blend(40, null, features(NotifRules.MESSAGE, null)))
    }
}
