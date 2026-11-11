package com.maeumpet.app.notif

import android.content.Context
import com.maeumpet.app.kev.KevFormat
import com.maeumpet.app.kev.KevOpinion
import com.maeumpet.app.kev.KevRuntime

/**
 * 알림 한 건의 분류·중요도·Kev 판단. 새 알림(MaeumNotificationListener)과 모델 교체 후 다시 판단(KevRescore)이 같이 쓴다.
 * 카톡(메시지)은 Kev가 앞 대화까지 보고 "나에게 묻는 질문인가 / 해 달라는 요청인가"를,
 * 그 밖의 알림(메일 포함)은 "광고·참여 유도인가 / 오늘 봐야 하는가"를 판단한다. 모델이 없으면 둘 다 null → 규칙.
 */
object NotifScorer {
    /** ruleKind: 규칙(NotifRules.kind)이 정한 분류. Kev가 광고로 보면 "기타"만 광고로 바꾼다. */
    suspend fun score(context: Context, item: NotifItem, ruleKind: String, stat: SenderStat?): NotifItem {
        val kev = if (ruleKind == NotifRules.MESSAGE) analyzeChat(context, item) else null
        val notifKev = if (ruleKind != NotifRules.MESSAGE) {
            KevRuntime.analyzeNotif(context, item.appLabel, item.title, item.text.orEmpty())
        } else null
        // 결제·배송·일정 같은 분류는 건드리지 않음
        val kind = if (ruleKind == NotifRules.OTHER && notifKev != null && notifKev.promo >= ImportanceLearner.NOTIF_YES) NotifRules.PROMO else ruleKind
        val needsReply = kev?.let { ImportanceLearner.needsReply(it) } ?: NotifRules.isQuestion(item.text)
        val ruleScore = NotifRules.importance(kind, item.text, item.amount, item.isGroupConversation, stat, needsReply, notifKev)
        val f = ImportanceLearner.features(kind, item.text, item.amount, item.isGroupConversation, stat, item.postedAt, kev)
        val score = ImportanceLearner.blend(ruleScore, ImportanceLearner.load(context), f)
        return item.copy(
            kind = kind,
            importance = score,
            features = ImportanceLearner.encode(f),
            isQuestion = kev?.let { ImportanceLearner.needsReply(it) } ?: (ruleKind == NotifRules.MESSAGE && NotifRules.isQuestion(item.lastMessageText())),
            kevPromo = notifKev?.promo,
            kevTimely = notifKev?.timely,
        )
    }

    /**
     * 알림에 담긴 최근 메시지로 Kev 입력을 만든다. 보낸 사람이 비어 있는 메시지는 이 폰 주인(나)이 보낸 것이다.
     * 실제 카톡 알림에는 대개 상대 메시지만 들어 있어서 앞 대화가 없는 경우가 많다.
     */
    private suspend fun analyzeChat(context: Context, item: NotifItem): KevOpinion? {
        val messages = MessageCodec.decode(item.messages)
        fun line(sender: String, text: String) = KevFormat.Line(sender.ifBlank { "나" }, text)
        val fallbackWho = listOf(item.sender, item.title).firstOrNull { !it.isNullOrBlank() } ?: item.appLabel
        val last = messages.lastOrNull()?.let { KevFormat.Line(it.sender.ifBlank { fallbackWho }, it.text) }
            ?: KevFormat.Line(fallbackWho, item.text.orEmpty())
        val previous = messages.dropLast(1).takeLast(KevFormat.CHAT_CONTEXT_MAX).map { line(it.sender, it.text) }
        return KevRuntime.analyzeChat(context, item.isGroupConversation, previous, last)
    }
}
