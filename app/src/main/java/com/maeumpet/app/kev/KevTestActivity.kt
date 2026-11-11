package com.maeumpet.app.kev

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.maeumpet.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import kotlin.math.abs
import kotlin.system.measureTimeMillis

class KevTestActivity : AppCompatActivity() {

    private lateinit var tvModelStatus: TextView
    private lateinit var tvModelPath: TextView
    private lateinit var tvModelSize: TextView
    private lateinit var tvModelBackend: TextView
    private lateinit var etInput: EditText
    private lateinit var btnRun: Button
    private lateinit var btnBenchmark: Button
    private lateinit var btnParity: Button
    private lateinit var tvResults: TextView

    private var classifier: KevClassifier? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_kev_test)

        tvModelStatus = findViewById(R.id.tvModelStatus)
        tvModelPath = findViewById(R.id.tvModelPath)
        tvModelSize = findViewById(R.id.tvModelSize)
        tvModelBackend = findViewById(R.id.tvModelBackend)
        etInput = findViewById(R.id.etInput)
        btnRun = findViewById(R.id.btnRun)
        btnBenchmark = findViewById(R.id.btnBenchmark)
        btnParity = findViewById(R.id.btnParity)
        tvResults = findViewById(R.id.tvResults)

        val modelDir = File(getExternalFilesDir(null), "kev")
        if (!modelDir.exists()) modelDir.mkdirs()
        val modelFile = File(modelDir, MODEL_FILE)
        val threads = intent.getIntExtra("threads", 4)
        val ubatch = intent.getIntExtra("ubatch", KevClassifier.N_UBATCH)
        val loadMode = intent.getIntExtra("loadMode", KevClassifier.LOAD_MODE)
        val repack = intent.getBooleanExtra("repack", KevClassifier.REPACK)

        tvModelPath.text = "Path: ${modelFile.absolutePath}"

        if (modelFile.exists()) {
            tvModelSize.text = "Size: ${modelFile.length() / (1024 * 1024)} MB"
            tvModelStatus.text = "Status: Loading..."

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val newClassifier = KevClassifier(this@KevTestActivity, modelFile.absolutePath, threads, ubatch, loadMode, repack)
                    val loadMs = measureTimeMillis { newClassifier.load() }
                    classifier = newClassifier
                    report("load_ms=$loadMs threads=$threads ubatch=$ubatch loadMode=$loadMode repack=$repack native_heap_mb=${Debug.getNativeHeapAllocatedSize() / (1024 * 1024)}")
                    withContext(Dispatchers.Main) {
                        tvModelStatus.text = "Status: Ready (load ${loadMs}ms)"
                        tvModelBackend.text = "Backend: llama.cpp CPU, $threads threads"
                    }
                    if (intent.getBooleanExtra("autoParity", false)) withContext(Dispatchers.Main) { runParity() }
                } catch (e: Throwable) {
                    report("load_error=${e.message}")
                    withContext(Dispatchers.Main) {
                        tvModelStatus.text = "Status: Error - ${e.message}"
                    }
                }
            }
        } else {
            tvModelSize.text = "Size: N/A"
            tvModelStatus.text = "Status: Error - Model not found. Please adb push the model to the path above."
        }

        btnRun.setOnClickListener {
            val text = etInput.text.toString()
            if (text.isBlank()) return@setOnClickListener
            runInference(text)
        }

        btnBenchmark.setOnClickListener {
            val text = etInput.text.toString().ifBlank { "이거 어떻게 하는 거야?" }
            runBenchmark(text)
        }

        btnParity.setOnClickListener { runParity() }
    }

    /** 입력 칸의 글을 앞 대화 없는 1:1 카톡 한 줄로 보고 질문·요청을 묻는다. */
    private fun chatState(text: String) = KevFormat.chatState(false, emptyList(), KevFormat.Line("상대", text))

    /** Lines tagged KevResult are what install_and_test_kev.bat reads back with logcat. */
    private fun report(line: String) = Log.i("KevResult", line)

    private fun runInference(text: String) {
        val c = classifier
        if (c == null || !c.isLoaded) {
            tvResults.text = "Model not loaded!"
            return
        }

        tvResults.text = "Running..."
        btnRun.isEnabled = false

        lifecycleScope.launch {
            try {
                var results: Map<String, KevClassifier.TaskResult>? = null
                val timeMs = measureTimeMillis {
                    results = c.analyze(chatState(text), CHAT_TASKS)
                }

                val sb = StringBuilder()
                sb.appendLine("Inference Time: ${timeMs}ms")
                sb.appendLine()

                results?.forEach { (task, res) ->
                    sb.appendLine("Task: $task")
                    sb.appendLine("Result: ${if (res.isYes) "Yes" else "No"}")
                    sb.appendLine("Probabilities - Yes: ${"%.3f".format(res.yesProb)}, No: ${"%.3f".format(res.noProb)}")
                    sb.appendLine()
                }

                tvResults.text = sb.toString()
            } catch (e: Exception) {
                tvResults.text = "Error: ${e.message}"
            } finally {
                btnRun.isEnabled = true
            }
        }
    }

    private fun runBenchmark(text: String) {
        val c = classifier
        if (c == null || !c.isLoaded) {
            tvResults.text = "Model not loaded!"
            return
        }

        tvResults.text = "Running benchmark (100 iterations)..."
        btnBenchmark.isEnabled = false

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val times = mutableListOf<Long>()
                // warmup
                c.analyze(chatState(text), CHAT_TASKS)

                for (i in 0 until 100) {
                    val timeMs = measureTimeMillis {
                        c.analyze(chatState(text), CHAT_TASKS)
                    }
                    times.add(timeMs)
                    withContext(Dispatchers.Main) {
                        tvResults.text = "Running benchmark... ${i + 1}/100"
                    }
                }

                times.sort()
                val median = times[times.size / 2]
                val p95 = times[(times.size * 0.95).toInt()]
                val min = times.first()
                val max = times.last()

                val avg = times.average()

                val resultText = """
                    Benchmark Results (100 runs):
                    Text: $text

                    Median: ${median}ms
                    P95: ${p95}ms
                    Min: ${min}ms
                    Max: ${max}ms
                    Avg: ${"%.2f".format(avg)}ms
                """.trimIndent()
                report("benchmark median_ms=$median p95_ms=$p95 min_ms=$min max_ms=$max")

                withContext(Dispatchers.Main) {
                    tvResults.text = resultText
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvResults.text = "Error: ${e.message}"
                }
            } finally {
                withContext(Dispatchers.Main) {
                    btnBenchmark.isEnabled = true
                }
            }
        }
    }

    /**
     * assets/kev/parity.json holds PyTorch reference rows (llamacpp/05_export_app.py: kevfmt states x tasks): ids built by kev.model
     * and P(yes). Checks that the app builds the same ids, and that the on-device decisions match PyTorch.
     */
    private fun runParity() {
        val c = classifier
        if (c == null || !c.isLoaded) {
            tvResults.text = "Model not loaded!"
            return
        }
        tvResults.text = "Running parity test..."
        btnParity.isEnabled = false

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val rows = JSONArray(assets.open("kev/parity.json").bufferedReader().use { it.readText() })
                var idsMatch = 0
                var agree = 0
                var maxDiff = 0f
                val flips = StringBuilder()
                val start = SystemClock.elapsedRealtime()
                for (i in 0 until rows.length()) {
                    val r = rows.getJSONObject(i)
                    val ref = r.getJSONArray("ids").let { a -> IntArray(a.length()) { a.getInt(it) } }
                    val text = r.getString("text")
                    val task = r.getString("task")
                    val q = KevClassifier.TASKS.indexOfFirst { it.first == task }
                    if (c.rowIds(text, q).contentEquals(ref)) idsMatch++
                    // score with the reference ids so a tokenizer slip cannot hide a backbone mismatch
                    val n = ref.size
                    val boxEnd = c.specialId("<|box_end|>")
                    val opts = ref.indices.filter { ref[it] == boxEnd }
                    val res = c.score(ref, n - 1, opts)
                    val ptYes = r.getDouble("pt_yes").toFloat()
                    maxDiff = maxOf(maxDiff, abs(res.yesProb - ptYes))
                    if ((res.yesProb > 0.5f) == (ptYes > 0.5f)) agree++
                    else flips.appendLine("FLIP $task \"$text\" pt=${"%.3f".format(ptYes)} app=${"%.3f".format(res.yesProb)}")
                }
                val ms = SystemClock.elapsedRealtime() - start
                val n = rows.length()
                val summary = "parity agree=$agree/$n ids_match=$idsMatch/$n max_prob_diff=${"%.4f".format(maxDiff)} " +
                    "mean_ms_per_row=${ms / n} native_heap_mb=${Debug.getNativeHeapAllocatedSize() / (1024 * 1024)}"
                report(summary)
                flips.lines().filter { it.isNotBlank() }.forEach { report(it) }
                withContext(Dispatchers.Main) { tvResults.text = summary.replace(" ", "\n") + "\n\n" + flips }
            } catch (e: Throwable) {
                report("parity_error=${e.message}")
                withContext(Dispatchers.Main) { tvResults.text = "Error: ${e.message}" }
            } finally {
                withContext(Dispatchers.Main) { btnParity.isEnabled = true }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        classifier?.close()
    }

    companion object {
        const val MODEL_FILE = "kev-notif-v3-Q6_K.gguf"
        private val CHAT_TASKS = listOf(KevFormat.CHAT_QUESTION, KevFormat.CHAT_REQUEST)
    }
}
