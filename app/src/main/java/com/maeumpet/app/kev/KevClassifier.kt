package com.maeumpet.app.kev

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * Kev-0.8B (kev-notif-v3: 카톡 질문·요청 + 알림 광고·오늘 봐야 함) on llama.cpp. The GGUF backbone returns hidden states; the PointerHead (assets/kev/head.bin)
 * runs here. Input layout mirrors kev.model.encode + rows_of: every question is its own causal row
 * [<fim_prefix> state] [<fim_middle> instr (<box_start> option <box_end>)... <fim_suffix>], positions 0..n-1.
 */
class KevClassifier(
    private val context: Context,
    private val modelPath: String,
    private val nThreads: Int = 4,
    private val nUbatch: Int = N_UBATCH,
    private val loadMode: Int = LOAD_MODE,
    private val repack: Boolean = REPACK,
) {
    private var handle = 0L
    private lateinit var head: Head
    private lateinit var special: Map<String, Int>
    private lateinit var questions: List<Question>

    val isLoaded: Boolean
        get() = handle != 0L

    data class TaskResult(val yesProb: Float, val noProb: Float) {
        val isYes get() = yesProb > noProb
    }

    private class Question(val name: String, val branch: IntArray, val optEnds: IntArray)

    private class Head(val d: Int, val dp: Int, val scale: Float, val temperature: Float,
                       val qW: FloatArray, val qb: FloatArray, val kW: FloatArray, val kb: FloatArray)

    fun load() {
        if (!File(modelPath).exists()) throw IllegalStateException("Model file not found at $modelPath")
        handle = KevNative.load(modelPath, nThreads, N_CTX, nUbatch, loadMode, repack)
        if (handle == 0L) throw IllegalStateException("llama.cpp failed to load $modelPath")
        head = readHead()
        if (head.d != KevNative.embdDim(handle)) throw IllegalStateException("head dim ${head.d} != model dim ${KevNative.embdDim(handle)}")
        special = SPECIAL.associateWith { name ->
            KevNative.tokenize(handle, name.toByteArray(Charsets.UTF_8), true).singleOrNull()
                ?: throw IllegalStateException("special token $name is not a single token")
        }
        questions = TASKS.map { (name, instr) -> buildQuestion(name, instr, listOf("Yes", "No")) }
    }

    fun close() {
        if (handle != 0L) KevNative.free(handle)
        handle = 0L
    }

    /** kev.model.user_tokens: caller text can never form control tokens. */
    fun userTokens(text: String): IntArray =
        KevNative.tokenize(handle, SPECIAL_RE.replace(text, "<¦$1¦>").toByteArray(Charsets.UTF_8), false)

    private fun buildQuestion(name: String, instr: String, options: List<String>): Question {
        val branch = ArrayList<Int>()
        branch += special.getValue("<|fim_middle|>")
        branch += userTokens(instr).toList()
        val ends = ArrayList<Int>()
        for (o in options) {
            branch += special.getValue("<|box_start|>")
            branch += userTokens(o).toList()
            branch += special.getValue("<|box_end|>")
            ends += branch.size - 1
        }
        branch += special.getValue("<|fim_suffix|>")
        return Question(name, branch.toIntArray(), ends.toIntArray())
    }

    fun specialId(name: String): Int = special.getValue(name)

    private fun stateIds(text: String): IntArray =
        intArrayOf(special.getValue("<|fim_prefix|>")) + userTokens(text).take(MAX_STATE - 1).toIntArray()

    /** The full causal row analyze() runs for TASKS[task]; the parity test compares it with kev.model's ids. */
    fun rowIds(text: String, task: Int): IntArray = stateIds(text) + questions[task].branch

    /** state(KevFormat으로 만든 입력)에 대해 고른 질문들만 돌린다. 질문 하나에 llama.cpp 한 번(약 0.3초). */
    suspend fun analyze(text: String, tasks: Collection<String>): Map<String, TaskResult> = withContext(Dispatchers.Default) {
        check(isLoaded) { "Model not loaded" }
        val state = stateIds(text)
        questions.filter { it.name in tasks }
            .associate { q -> q.name to score(state + q.branch, state.size + q.branch.size - 1, q.optEnds.map { state.size + it }) }
    }

    /** One causal row -> option probabilities. Also used by the parity test with the reference ids. */
    fun score(ids: IntArray, decide: Int, opts: List<Int>): TaskResult {
        val read = intArrayOf(decide) + opts.toIntArray()
        val h = KevNative.hidden(handle, ids, read) ?: throw IllegalStateException("llama.cpp decode failed (${ids.size} tokens)")
        val d = head.d
        val q = affine(head.qW, head.qb, h, 0)
        val logits = FloatArray(opts.size) { j ->
            val k = affine(head.kW, head.kb, h, (j + 1) * d)
            var z = 0f
            for (i in 0 until head.dp) z += k[i] * q[i]
            z * head.scale / head.temperature
        }
        val m = logits.max()
        val e = logits.map { exp(it - m) }
        val sum = e.sum()
        return TaskResult(e[0] / sum, e[1] / sum)
    }

    private fun affine(w: FloatArray, b: FloatArray, x: FloatArray, xOff: Int): FloatArray {
        val d = head.d
        return FloatArray(head.dp) { r ->
            var s = b[r]
            val base = r * d
            for (c in 0 until d) s += w[base + c] * x[xOff + c]
            s
        }
    }

    private fun readHead(): Head {
        val bytes = context.assets.open("kev/head.bin").use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val d = buf.int
        val dp = buf.int
        val scale = buf.float
        val temperature = buf.float
        fun floats(n: Int) = FloatArray(n).also { buf.asFloatBuffer().get(it); buf.position(buf.position() + n * 4) }
        return Head(d, dp, scale, temperature, floats(dp * d), floats(dp), floats(dp * d), floats(dp))
    }

    companion object {
        const val N_CTX = 512
        // Tokens per llama.cpp compute pass. Its buffer reserves n_ubatch x vocab(248k) floats, about 1 MB per token, so
        // 128 (123 MB) instead of 512 (492 MB). Typical rows are 30-110 tokens; longer ones run in several passes, same result.
        const val N_UBATCH = 128
        // Weights: read the file (llama_load_mode 0) and keep only the repacked i8mm copy. Measured on Fold7 (2026-10-01):
        //   mmap + repack 1.52 GB PSS, 281 ms/row | read + repack 1.12 GB, 285 ms | mmap, no repack 0.92 GB, 683 ms.
        // mmap + repack keeps the mapped original next to the repacked copy.
        const val LOAD_MODE = 0
        const val REPACK = true
        const val MAX_STATE = 384   // kev.model.MAX_STATE: <fim_prefix> + up to 383 message tokens
        val SPECIAL = listOf("<|fim_prefix|>", "<|fim_middle|>", "<|box_start|>", "<|box_end|>", "<|fim_suffix|>")
        private val SPECIAL_RE = Regex("<\\|([A-Za-z0-9_]+)\\|>")   // kev.model._SPECIAL_RE
        // 앱이 쓰는 질문 4개(KevFormat) + 이전 3i4K 질문 2개(학습에 함께 넣어 유지, Parity Test로 확인)
        val TASKS = KevFormat.TASKS.toList() + listOf(
            "information_question" to "Does the Korean message ask a genuine question seeking information? Rhetorical questions do not count.",
            "action_request" to "Is the Korean message a command or request for the listener to perform an action? Rhetorical commands do not count.",
        )
    }
}
