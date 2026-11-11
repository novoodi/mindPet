package com.maeumpet.app.notif

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizationRequest
import com.google.mlkit.genai.summarization.Summarizer
import com.google.mlkit.genai.summarization.SummarizerOptions
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 폰 안의 Gemini Nano(ML Kit GenAI Summarization)로 대화 묶음을 한 줄로 줄인다.
 * 기기가 지원하지 않으면 null 을 돌려주고, 브리핑은 규칙 정리만으로 만들어진다.
 */
class NanoSummarizer(private val context: Context) {

    private fun client(): Summarizer =
        Summarization.getClient(
            SummarizerOptions.builder(context)
                .setInputType(SummarizerOptions.InputType.CONVERSATION)
                .setOutputType(SummarizerOptions.OutputType.ONE_BULLET)
                .setLanguage(SummarizerOptions.Language.KOREAN)
                .build(),
        )

    /** AVAILABLE / DOWNLOADABLE / DOWNLOADING / UNAVAILABLE 중 하나를 문자열로 */
    suspend fun status(): String {
        val summarizer = client()
        val s = try {
            runCatching { summarizer.checkFeatureStatus().await() }.getOrElse { FeatureStatus.UNAVAILABLE }
        } finally {
            summarizer.close()
        }
        return when (s) {
            FeatureStatus.AVAILABLE -> "AVAILABLE"
            FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
            FeatureStatus.DOWNLOADING -> "DOWNLOADING"
            else -> "UNAVAILABLE"
        }
    }

    /** 충전 중 밤에 호출. 내려받을 수 있으면 내려받는다. */
    suspend fun ensureDownloaded(): String {
        val summarizer = client()
        try {
            val s = runCatching { summarizer.checkFeatureStatus().await() }.getOrElse { return "UNAVAILABLE" }
            if (s == FeatureStatus.DOWNLOADABLE) {
                Log.d(TAG, "downloading Gemini Nano summarization feature")
                var result = "DOWNLOADING"
                summarizer.downloadFeature(object : DownloadCallback {
                    override fun onDownloadStarted(bytesToDownload: Long) = Unit
                    override fun onDownloadProgress(totalBytesDownloaded: Long) = Unit
                    override fun onDownloadCompleted() { result = "AVAILABLE" }
                    override fun onDownloadFailed(e: GenAiException) { result = "FAILED: ${e.message}" }
                }).await()
                return result
            }
            return if (s == FeatureStatus.AVAILABLE) "AVAILABLE" else "UNAVAILABLE"
        } finally {
            summarizer.close()
        }
    }

    /**
     * 대화 묶음("이름: 내용" 줄들)을 한 줄로. 짧으면 모델을 안 쓰고 null.
     * 실패하거나 지원 안 되면 null.
     */
    suspend fun summarizeConversation(lines: List<String>): String? {
        if (lines.size < 3) return null
        val text = lines.joinToString("\n")
        if (text.length < 60) return null
        val summarizer = try { client() } catch (e: Exception) {
            Log.w(TAG, "nano client unavailable: ${e.message}")
            return null
        }
        return try {
            val result = withTimeoutOrNull(8_000) {
                if (summarizer.checkFeatureStatus().await() != FeatureStatus.AVAILABLE) return@withTimeoutOrNull null
                summarizer.runInference(SummarizationRequest.builder(text).build()).await()
            } ?: return null
            result.summary.trim().trimStart('•', '-', '*', ' ').takeIf { it.isNotBlank() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "nano summarize failed: ${e.message}")
            null
        } finally {
            summarizer.close()
        }
    }

    companion object {
        private const val TAG = "MaeumPet.Nano"
    }
}
