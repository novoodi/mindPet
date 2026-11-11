package com.maeumpet.app.notif

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.maeumpet.app.personal.Prefs
import java.util.concurrent.TimeUnit

/**
 * 펫이 자면서 하는 일. 충전 중이고 배터리가 충분할 때만 돈다.
 *  1. 보낸 사람별 통계를 최근 30일 기록으로 다시 계산 (내가 열어 보는 사람은 앞으로 더 중요하게)
 *  2. 보관 기간이 지난 알림 원문 삭제
 *  3. 아침 브리핑을 미리 만들어 두기 (잠금 해제 때 바로 보여 주도록)
 */
class NightWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        if (!prefs.briefingEnabled) return Result.success()
        val db = NotifDatabase.get(ctx)
        val now = System.currentTimeMillis()

        // 1. 보낸 사람 통계 재계산
        val month = db.notifs().since(now - 30L * 24 * 3600 * 1000)
        db.senderStats().clear()
        month.groupBy { it.senderKey }.forEach { (key, items) ->
            db.senderStats().upsert(
                SenderStat(
                    key = key,
                    count = items.size,
                    clicks = items.count { it.clicked },
                    dismisses = items.count { it.dismissed },
                    lastAt = items.maxOf { it.postedAt },
                ),
            )
        }

        // Preserve pre-upgrade payments before notification retention removes their source.
        db.notifs().since(0).forEach { item ->
            ExpenseExtractor.record(item)?.let { db.expenses().insert(it) }
            TodoExtractor.candidates(item).forEach { db.todos().insert(it) }
        }

        // 2. 오래된 원문 삭제 (지출 장부는 별도 보관)
        val deleted = db.notifs().deleteBefore(now - prefs.notifRetentionDays * 24L * 3600 * 1000)

        // 3. 중요도 분류기 학습 (열어 본 것 = 1, 지운 것 = 0). 라벨이 충분할 때만
        val labeled = db.notifs().labeledSince(now - 30L * 24 * 3600 * 1000)
        val rows = labeled.mapNotNull { n -> ImportanceLearner.decode(n.features)?.let { it to (if (n.clicked) 1 else 0) } }
        val trained = ImportanceLearner.train(ctx, rows)

        // 4. Gemini Nano 요약 기능 준비 (지원 기기면 충전 중에 내려받기)
        prefs.nanoStatus = runCatching { NanoSummarizer(ctx).ensureDownloaded() }.getOrElse { "UNAVAILABLE" }

        // 5. 브리핑 미리 만들기 (전달은 잠금 해제 때)
        val since = prefs.lastBriefingAt
        val pending = db.notifs().countSince(since)
        // Build only on return: a nightly draft must not replace the user's delivered briefing.

        prefs.lastNightRunAt = now
        Log.d(TAG, "night run: senders=${month.groupBy { it.senderKey }.size} deleted=$deleted labels=${rows.size} trained=$trained nano=${prefs.nanoStatus} pending=$pending")
        return Result.success()
    }

    companion object {
        private const val TAG = "MaeumPet.Night"
        private const val NAME = "maeumpet-night"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<NightWorker>(24, TimeUnit.HOURS, 6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
