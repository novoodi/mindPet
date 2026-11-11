package com.maeumpet.app.notif

import android.content.Context
import android.util.Log
import com.maeumpet.app.kev.KevRuntime
import com.maeumpet.app.kev.KevTestActivity
import com.maeumpet.app.personal.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kev 모델이나 분류 규칙이 바뀌면 최근 알림을 새 판단으로 다시 매긴다. 답장 목록·브리핑이 업데이트 직후부터 새 판단을 쓰게 하려는 것.
 *
 * WorkManager가 아니라 앱 프로세스 안에서 돈다. 삼성 폰은 백그라운드 앱을 얼려(Freecess/OLAF) 긴 작업을 멈추는데,
 * 알림 접근 서비스가 묶여 있는 동안에는 프로세스가 살아 있다 (2026-10-03 실측: 작업이 OLAF로 멈춤, 리스너는 계속 동작).
 * 중간에 프로세스가 죽어도 Prefs.kevRescoreProgress로 이어서 한다.
 */
object KevRescore {
    private const val TAG = "MaeumPet.Rescore"
    private const val WINDOW_MS = 24 * 3600_000L
    private const val REST_MS = 500L

    /** 모델 파일 + 분류 규칙 판. 규칙(NotifRules.kind 등)을 바꿨을 때도 숫자를 올리면 최근 알림을 다시 판단한다. */
    const val VERSION = "${KevTestActivity.MODEL_FILE}#rules2"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicBoolean(false)

    fun isDone(context: Context) = Prefs(context).kevScoredModel == VERSION

    /** 아직 이 판으로 다시 판단하지 않았으면 시작한다. 이미 돌고 있으면 아무것도 하지 않는다. */
    fun startIfNeeded(context: Context) {
        val app = context.applicationContext
        // 예전 판(2026-10-03)의 WorkManager 작업이 남아 있으면 지운다
        runCatching { androidx.work.WorkManager.getInstance(app).cancelUniqueWork("kev-rescore") }
        if (!KevRuntime.isInstalled(app) || isDone(app) || !running.compareAndSet(false, true)) return
        scope.launch {
            try { run(app) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { Log.w(TAG, "rescore failed", e) }
            finally { running.set(false) }
        }
    }

    private suspend fun run(ctx: Context) {
        val db = NotifDatabase.get(ctx)
        val prefs = Prefs(ctx)
        val removed = db.notifs().deleteRedacted()
        // 범위를 줄인다(551개를 연달아 돌리면 폰이 뜨거워져 시스템이 작업을 멈춘다, 2026-10-03 실측):
        // 최근 24시간 카톡 중 아직 안 챙긴 대화방의 메시지 + 아직 알림창에 남은 그 밖의 알림
        val recent = db.notifs().since(System.currentTimeMillis() - WINDOW_MS)
        val isChat = { n: NotifItem -> NotifRules.kind(n.packageName, n.category, n.title, n.text, n.messages != null) == NotifRules.MESSAGE }
        val rows = (PetBriefing.unhandledMessages(recent.filter(isChat)) + recent.filter { !isChat(it) && it.removedAt == null })
            .sortedByDescending { it.id }
        // 멈췄다 다시 시작하면 이어서 한다: "VERSION:가장 작은 처리한 id"
        val resumeBelow = prefs.kevRescoreProgress?.takeIf { it.startsWith("$VERSION:") }?.substringAfter(':')?.toLongOrNull() ?: Long.MAX_VALUE
        var done = 0
        for (row in rows) {
            if (row.id >= resumeBelow) continue
            val ruleKind = NotifRules.kind(row.packageName, row.category, row.title, row.text, row.messages != null)
            val scored = NotifScorer.score(ctx, row, ruleKind, db.senderStats().get(row.senderKey))
            db.notifs().rescore(row.id, scored.kind, scored.importance, scored.features.orEmpty(), scored.isQuestion, scored.kevPromo, scored.kevTimely)
            prefs.kevRescoreProgress = "$VERSION:${row.id}"
            done++
            delay(REST_MS)   // 연달아 돌리면 뜨거워지므로 잠깐씩 쉰다
        }
        prefs.kevScoredModel = VERSION
        Log.d(TAG, "rescored $done of ${rows.size} notifications ($VERSION), removed $removed redacted")
    }
}
