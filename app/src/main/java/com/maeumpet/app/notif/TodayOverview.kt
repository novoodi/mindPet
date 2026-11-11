package com.maeumpet.app.notif

object TodayOverview {
    fun attention(rows: List<NotifItem>): List<NotifItem> = rows.groupBy { it.packageName to it.key }.values
        .map { it.maxBy { n -> n.postedAt } }
        .filter { !it.clicked && !it.dismissed && BriefingPolicy.eligible(it.kind, "${it.title.orEmpty()} ${it.lastMessageText().orEmpty()}", it.importance, ImportanceLearner.needsReply(it.features), it.kevTimely?.let { t -> t >= ImportanceLearner.NOTIF_YES }) }
        .sortedByDescending { it.importance }

    fun dueTasks(rows: List<TodoItem>, today: Long): List<TodoItem> = rows
        .filter { it.status == "OPEN" && it.dueDay != null && it.dueDay <= today }
        .sortedBy { it.dueDay }

    fun netExpense(rows: List<Expense>): Long = rows.filterNot { it.deleted }.sumOf { if (it.refund) -it.amount else it.amount }
}
