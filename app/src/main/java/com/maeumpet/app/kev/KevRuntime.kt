package com.maeumpet.app.kev

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** 카톡 한 줄에 대한 Kev 판단: 나에게 묻는 진짜 질문일 확률, 나에게 해 달라는 요청일 확률. */
data class KevOpinion(val question: Float, val request: Float)

/** 카톡 외 알림에 대한 Kev 판단: 광고·참여 유도일 확률, 오늘 안에 봐야 할 확률. */
data class NotifOpinion(val promo: Float, val timely: Float)

/**
 * The one Kev instance background code shares. The model holds ~1.1 GB, so it is loaded on first use (~1 s) and
 * released after [IDLE_MS] without requests; calls are serialized (one llama.cpp context). Returns null when the
 * model file is missing or fails, and callers fall back to rules.
 */
object KevRuntime {
    private const val TAG = "MaeumPet.Kev"
    private const val IDLE_MS = 90_000L

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var classifier: KevClassifier? = null
    private var unloadJob: Job? = null
    private var failedPath: String? = null

    fun modelFile(context: Context): File =
        File(File(context.getExternalFilesDir(null), "kev"), KevTestActivity.MODEL_FILE)

    fun isInstalled(context: Context): Boolean = modelFile(context).exists()

    /** 카톡: 앞 대화(최대 4줄)와 마지막 메시지로 질문·요청을 본다. */
    suspend fun analyzeChat(context: Context, group: Boolean, previous: List<KevFormat.Line>, last: KevFormat.Line): KevOpinion? {
        if (last.text.isBlank()) return null
        val r = run(context, KevFormat.chatState(group, previous, last), listOf(KevFormat.CHAT_QUESTION, KevFormat.CHAT_REQUEST)) ?: return null
        return KevOpinion(question = r.getValue(KevFormat.CHAT_QUESTION), request = r.getValue(KevFormat.CHAT_REQUEST))
    }

    /** 카톡 외 알림: 광고·유도인지, 오늘 봐야 하는지. */
    suspend fun analyzeNotif(context: Context, app: String, title: String?, text: String): NotifOpinion? {
        if (text.isBlank()) return null
        val r = run(context, KevFormat.notifState(app, title, text), listOf(KevFormat.NOTIF_PROMO, KevFormat.NOTIF_TIMELY)) ?: return null
        return NotifOpinion(promo = r.getValue(KevFormat.NOTIF_PROMO), timely = r.getValue(KevFormat.NOTIF_TIMELY))
    }

    private suspend fun run(context: Context, state: String, tasks: List<String>): Map<String, Float>? = mutex.withLock {
        unloadJob?.cancel()
        val c = classifier ?: load(context) ?: return@withLock null
        val result = try {
            c.analyze(state, tasks).mapValues { it.value.yesProb }
        } catch (e: kotlinx.coroutines.CancellationException) {
            scheduleUnload()
            throw e   // 작업 취소(발열·종료)는 실패가 아니므로 그대로 올린다
        } catch (e: Exception) {
            Log.w(TAG, "analyze failed", e)
            null
        }
        scheduleUnload()
        result
    }

    private fun load(context: Context): KevClassifier? {
        val file = modelFile(context)
        if (!file.exists() || failedPath == file.path) return null
        val t0 = System.nanoTime()
        return runCatching { KevClassifier(context.applicationContext, file.absolutePath).also { it.load() } }
            .onSuccess {
                classifier = it
                Log.d(TAG, "loaded in ${(System.nanoTime() - t0) / 1_000_000} ms")
            }
            .onFailure {
                failedPath = file.path   // do not retry a broken file on every notification
                Log.w(TAG, "load failed", it)
            }
            .getOrNull()
    }

    private fun scheduleUnload() {
        unloadJob = scope.launch {
            delay(IDLE_MS)
            mutex.withLock {
                classifier?.close()
                classifier = null
                Log.d(TAG, "unloaded after idle")
            }
        }
    }
}
