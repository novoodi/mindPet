package com.maeumpet.app.notif

import android.content.Context
import android.util.Log
import com.maeumpet.app.kev.KevOpinion
import com.maeumpet.app.personal.Prefs
import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * "내가 열어 보는 알림인가"를 폰 안에서 배우는 아주 작은 분류기(로지스틱 회귀).
 * 정답은 사용자 행동에서 나온다: 눌러서 열었으면 1, 밀어서 지웠으면 0.
 * 밤에 충전 중일 때 NightWorker 가 다시 학습하고, 가중치는 설정 파일에 저장된다.
 */
object ImportanceLearner {
    private const val TAG = "MaeumPet.Learn"
    const val MIN_LABELS = 100
    private const val KINDS = 7

    /**
     * 특징 개수. 순서나 개수를 바꾸면 이 값도 바꿀 것: 개수가 다른 저장 특징·가중치는 학습과 점수에서 빠진다.
     * 23 = 감정 모델(E8) 7개를 쓰던 이전 형식, 19 = 기본 16 + Kev(질문·요청·실행 여부) 3.
     */
    const val FEATURE_DIM = 19

    /** 학습에 쓰는 특징. 순서를 바꾸면 저장된 가중치와 어긋나므로 바꾸지 말 것. */
    fun features(
        kind: String, text: String?, amount: Long?, isGroup: Boolean, stat: SenderStat?, postedAt: Long, kev: KevOpinion?,
    ): FloatArray {
        val f = ArrayList<Float>(32)
        val kinds = listOf(NotifRules.MESSAGE, NotifRules.EMAIL, NotifRules.MONEY, NotifRules.DELIVERY, NotifRules.CALENDAR, NotifRules.PROMO, NotifRules.OTHER)
        for (k in kinds) f += if (k == kind) 1f else 0f
        f += if (NotifRules.isQuestion(text)) 1f else 0f
        f += if (NotifRules.hasTime(text)) 1f else 0f
        f += when {
            amount == null -> 0f
            amount >= 100_000 -> 1f
            amount >= 30_000 -> 0.7f
            amount >= 10_000 -> 0.4f
            else -> 0.2f
        }
        f += if (isGroup) 1f else 0f
        f += (ln(1.0 + (stat?.count ?: 0)) / 5.0).toFloat().coerceAtMost(1f)
        f += stat?.clickRate ?: 0.5f
        val hour = Instant.ofEpochMilli(postedAt).atZone(ZoneId.systemDefault()).hour
        f += sin(2 * Math.PI * hour / 24).toFloat()
        f += cos(2 * Math.PI * hour / 24).toFloat()
        f += ((text?.length ?: 0) / 200f).coerceAtMost(1f)
        // Kev: 나에게 묻는 질문인가, 무언가를 해 달라는 요청인가. 돌리지 않았으면(모델 없음, 메시지·메일 아님) 0과 표시 0
        f += kev?.question ?: 0f
        f += kev?.request ?: 0f
        f += if (kev != null) 1f else 0f
        check(f.size == FEATURE_DIM) { "feature count ${f.size} != FEATURE_DIM" }
        return f.toFloatArray()
    }

    /**
     * Kev가 "질문 또는 요청"으로 볼 확률이 이 값 이상이면 답장이 필요한 메시지로 친다.
     * kev-notif-v3는 합성 dev에서 0.7~0.9가 똑같이 가장 좋아 가운데 0.8을 골랐다(실제 폰 카톡에서 오탐 14→9, 2026-10-03).
     */
    const val NEEDS_REPLY = 0.8f

    /** 카톡 외 알림의 광고·오늘 봐야 함 판단 기준 (dev 최적이 0.5 근처) */
    const val NOTIF_YES = 0.5f

    fun needsReply(kev: KevOpinion): Boolean = kev.question >= NEEDS_REPLY || kev.request >= NEEDS_REPLY

    /**
     * 저장된 특징에서 Kev 판단을 꺼낸다. Kev가 돌지 않은 알림(모델 없음, 이전 형식)은 null → 호출부가 규칙으로 판단.
     */
    fun needsReply(features: String?): Boolean? {
        val f = decode(features)?.takeIf { it.size == FEATURE_DIM } ?: return null
        if (f[FEATURE_DIM - 1] < 0.5f) return null
        return needsReply(KevOpinion(question = f[FEATURE_DIM - 3], request = f[FEATURE_DIM - 2]))
    }

    fun encode(f: FloatArray): String = f.joinToString(",") { "%.3f".format(it) }
    fun decode(s: String?): FloatArray? = s?.split(',')?.mapNotNull { it.toFloatOrNull() }?.toFloatArray()?.takeIf { it.isNotEmpty() }

    class Model(val w: FloatArray, val b: Float) {
        fun prob(f: FloatArray): Float {
            var z = b
            for (i in f.indices) if (i < w.size) z += w[i] * f[i]
            return (1.0 / (1.0 + exp(-z.toDouble()))).toFloat()
        }

        fun toJson(): String = JSONArray().apply { put(b.toDouble()); w.forEach { put(it.toDouble()) } }.toString()

        companion object {
            fun fromJson(s: String?): Model? = runCatching {
                val a = JSONArray(s ?: return null)
                Model(FloatArray(a.length() - 1) { a.getDouble(it + 1).toFloat() }, a.getDouble(0).toFloat())
            }.getOrNull()
        }
    }

    /** 이전 형식(다른 특징 개수)으로 학습된 가중치는 버리고 규칙 점수만 쓴다. 다음 밤 학습에서 새로 만든다. */
    fun load(context: Context): Model? = usable(Model.fromJson(Prefs(context).importanceModel))

    fun usable(model: Model?): Model? = model?.takeIf { it.w.size == FEATURE_DIM }

    /**
     * 라벨 있는 알림으로 학습. 20%는 검증용으로 떼어 정확도를 잰다.
     * 반환: 학습에 쓴 개수와 검증 정확도, 데이터가 부족하면 null.
     */
    fun train(context: Context, allRows: List<Pair<FloatArray, Int>>): Pair<Int, Float>? {
        val rows = allRows.filter { it.first.size == FEATURE_DIM }   // 이전 형식으로 저장된 알림은 제외
        if (rows.size < MIN_LABELS) return null
        val shuffled = rows.shuffled(java.util.Random(42))
        val nVal = rows.size / 5
        val valSet = shuffled.take(nVal)
        val trainSet = shuffled.drop(nVal)
        val dim = rows.first().first.size
        val w = FloatArray(dim)
        var b = 0f
        val pos = trainSet.count { it.second == 1 }.toFloat()
        val posWeight = ((trainSet.size - pos) / pos.coerceAtLeast(1f)).coerceIn(0.3f, 3f)
        val lr = 0.05f
        val l2 = 0.001f
        repeat(300) {
            for ((f, y) in trainSet) {
                var z = b
                for (i in f.indices) z += w[i] * f[i]
                val p = (1.0 / (1.0 + exp(-z.toDouble()))).toFloat()
                val g = (p - y) * (if (y == 1) posWeight else 1f)
                for (i in f.indices) w[i] -= lr * (g * f[i] + l2 * w[i])
                b -= lr * g
            }
        }
        val model = Model(w, b)
        val acc = if (valSet.isEmpty()) 0f else valSet.count { (f, y) -> (model.prob(f) >= 0.5f) == (y == 1) }.toFloat() / valSet.size
        val prefs = Prefs(context)
        prefs.importanceModel = model.toJson()
        prefs.importanceModelAcc = acc
        prefs.importanceModelN = rows.size
        Log.d(TAG, "trained on ${rows.size} labels, val acc=$acc")
        return rows.size to acc
    }

    /** 규칙 점수와 학습 점수를 섞는다. 모델이 없으면 규칙만. */
    fun blend(ruleScore: Int, model: Model?, f: FloatArray?): Int {
        if (model == null || f == null) return ruleScore
        val p = model.prob(f)
        return (ruleScore * 0.5f + p * 100f * 0.5f).toInt().coerceIn(0, 100)
    }
}
