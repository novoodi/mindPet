package com.maeumpet.app.plan

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.maeumpet.app.R
import com.maeumpet.app.notif.NotifDatabase
import com.maeumpet.app.notif.Plan
import com.maeumpet.app.ui.MainActivity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * 약속 후보를 모으고, 사용자가 "맞아"라고 하면 확정해서 당일 아침과 한 시간 전에 알려 준다.
 * 펫은 추측만 하고 결정은 사용자가 한다.
 */
object PlanManager {
    private const val TAG = "MaeumPet.Plan"
    private const val CHANNEL = "plan"
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val FMT = DateTimeFormatter.ofPattern("M/d(E) HH:mm")
    private val FMT_DAY = DateTimeFormatter.ofPattern("M/d(E)")

    /** Re-evaluate old unconfirmed candidates without changing the user's confirmed plans. */
    suspend fun relevantCandidates(context: Context): List<Plan> =
        NotifDatabase.get(context).plans().candidates().filter(::isRelevant).take(10)

    fun isRelevant(p: Plan): Boolean =
        PlanExtractor.extract(p.text, Instant.ofEpochMilli(p.createdAt).atZone(zone).toLocalDateTime()) != null

    /** 문장에서 약속 후보를 찾아 저장. 같은 문장이 하루 안에 또 오면 무시. */
    suspend fun addCandidate(context: Context, who: String, text: String, source: String): Plan? {
        val c = PlanExtractor.extract(text) ?: return null
        val db = NotifDatabase.get(context)
        val dayAgo = System.currentTimeMillis() - 24 * 3600 * 1000L
        if (db.plans().countSame(c.text, dayAgo) > 0) return null
        val plan = Plan(
            createdAt = System.currentTimeMillis(),
            who = who,
            text = c.text,
            why = c.why,
            at = c.at?.atZone(zone)?.toInstant()?.toEpochMilli(),
            hasExplicitTime = c.hasExplicitTime,
            type = c.type,
            source = source,
        )
        val id = db.plans().insert(plan)
        Log.d(TAG, "candidate #$id [${c.type}] $who: ${c.text.take(40)} @ ${c.at}")
        return plan.copy(id = id)
    }

    suspend fun confirm(context: Context, id: Long) {
        val db = NotifDatabase.get(context)
        val p = db.plans().get(id) ?: return
        db.plans().setStatus(id, "CONFIRMED")
        scheduleReminders(context, p)
    }

    suspend fun deny(context: Context, id: Long) {
        NotifDatabase.get(context).plans().setStatus(id, "DENIED")
    }

    fun describe(p: Plan): String {
        val whenText = p.at?.let {
            val dt = Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime()
            if (p.hasExplicitTime) dt.format(FMT) else dt.toLocalDate().format(FMT_DAY)
        } ?: "시간 미정"
        val typeText = when (p.type) { "CANCEL" -> " (취소?)"; "CHANGE" -> " (변경?)"; else -> "" }
        return "$whenText · ${p.who}$typeText"
    }

    /** 오늘 확정된 약속을 브리핑용 한 줄로 */
    suspend fun todayLine(context: Context): String? {
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = start + 24 * 3600 * 1000L
        val plans = NotifDatabase.get(context).plans().confirmedBetween(start, end)
        if (plans.isEmpty()) return null
        return "오늘 약속: " + plans.joinToString(", ") { p ->
            val dt = Instant.ofEpochMilli(p.at!!).atZone(zone).toLocalDateTime()
            (if (p.hasExplicitTime) dt.format(DateTimeFormatter.ofPattern("H:mm")) + " " else "") + p.who
        }
    }

    // ---- 알림 예약 ----

    private fun scheduleReminders(context: Context, p: Plan) {
        val at = p.at ?: return
        val now = System.currentTimeMillis()
        val dt = Instant.ofEpochMilli(at).atZone(zone).toLocalDateTime()
        val morning = LocalDateTime.of(dt.toLocalDate(), java.time.LocalTime.of(8, 30)).atZone(zone).toInstant().toEpochMilli()
        val hourBefore = at - 3600 * 1000L
        val wm = WorkManager.getInstance(context)
        for ((tag, t) in listOf("morning" to morning, "hour" to hourBefore)) {
            if (t <= now) continue
            if (tag == "hour" && !p.hasExplicitTime) continue
            val req = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(t - now, TimeUnit.MILLISECONDS)
                .setInputData(Data.Builder().putLong("planId", p.id).putString("kind", tag).build())
                .addTag("plan-${p.id}")
                .build()
            wm.enqueue(req)
        }
        Log.d(TAG, "reminders scheduled for #${p.id} at $dt")
    }

    fun notify(context: Context, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "펫의 약속 알림", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_pet_small)
            .setContentTitle(":)  약속 있어")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify((2000 + text.hashCode() % 1000), n) }
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong("planId", -1)
        val kind = inputData.getString("kind") ?: "morning"
        val p = NotifDatabase.get(applicationContext).plans().get(id) ?: return Result.success()
        if (p.status != "CONFIRMED") return Result.success()
        val text = if (kind == "hour") "한 시간 뒤야. ${PlanManager.describe(p)}\n\"${p.text.take(40)}\"" else "오늘이야. ${PlanManager.describe(p)}\n\"${p.text.take(40)}\""
        PlanManager.notify(applicationContext, text)
        return Result.success()
    }
}
