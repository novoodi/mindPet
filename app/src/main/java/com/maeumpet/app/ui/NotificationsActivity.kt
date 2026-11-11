package com.maeumpet.app.ui

import android.os.Bundle
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.maeumpet.app.databinding.ActivityNotificationsBinding
import com.maeumpet.app.R
import com.maeumpet.app.databinding.ItemNotifBinding
import com.maeumpet.app.notif.NotifDatabase
import com.maeumpet.app.notif.NotifItem
import com.maeumpet.app.notif.NotifRules
import com.maeumpet.app.notif.MessageCodec
import com.maeumpet.app.notif.NotificationSummary
import com.maeumpet.app.notif.TodoExtractor
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 펫이 실제로 어떤 알림을 저장했는지 확인하는 화면 */
class NotificationsActivity : AppCompatActivity() {

    private lateinit var b: ActivityNotificationsBinding
    private val adapter = NotifAdapter()
    private var rows = emptyList<NotifItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityNotificationsBinding.inflate(layoutInflater)
        setContentView(b.root)
        title = "받은 알림"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.tvEmpty.text = "아직 저장된 알림이 없어.\n알림 접근을 켜 두면 여기에 쌓일 거야."

        b.mode.check(if (savedInstanceState?.getBoolean("summary", true) != false) R.id.summary else R.id.original)
        b.mode.setOnCheckedChangeListener { _, _ -> render() }
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        render()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NotifDatabase.get(this@NotificationsActivity).notifs().observeRecent(300).collect { list ->
                    rows = list
                    render()
                    b.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun render() {
        adapter.summary = b.mode.checkedRadioButtonId == R.id.summary
        b.description.text = if (adapter.summary) "최근 300개 알림을 묶어 핵심 문장 최대 2개로 요약해. 알림을 누르면 저장된 원문을 볼 수 있어."
            else "저장된 원문이야. 알림에 포함되지 않은 내용은 볼 수 없어."
        val display = if (!adapter.summary) rows else rows.groupBy { it.packageName to it.key }.values.map { group ->
            val latest = group.maxBy { it.postedAt }
            val messages = group.sortedBy { it.postedAt }.flatMap { n ->
                MessageCodec.decode(n.messages).ifEmpty { listOf(MessageCodec.Message(n.sender.orEmpty(), n.text.orEmpty(), n.postedAt)) }
            }.distinctBy { Triple(it.sender, it.text, it.timestamp) }.sortedBy { it.timestamp }
            latest.copy(messages = MessageCodec.encode(messages))
        }.sortedByDescending { it.postedAt }
        adapter.submitList(display) { adapter.notifyDataSetChanged() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("summary", b.mode.checkedRadioButtonId == R.id.summary)
        super.onSaveInstanceState(outState)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

class NotifAdapter : ListAdapter<NotifItem, NotifAdapter.VH>(DIFF) {
    var summary = true
    class VH(val b: ItemNotifBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemNotifBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val n = getItem(position)
        val b = holder.b
        val time = Instant.ofEpochMilli(n.postedAt).atZone(ZoneId.systemDefault()).format(FMT)
        val state = when {
            n.clicked -> "열어봄"
            n.dismissed -> "지움"
            n.removedAt != null -> "사라짐"
            else -> "대기"
        }
        b.meta.text = "$time · ${n.appLabel} · ${NotifRules.kindLabel(n.kind)} · 중요 ${n.importance} · $state"
        val who = n.sender ?: n.title ?: ""
        val room = if (n.isGroupConversation && n.conversationTitle != null) " (${n.conversationTitle})" else ""
        b.who.text = who + room
        b.who.visibility = if (who.isBlank()) View.GONE else View.VISIBLE
        val original = MessageCodec.decode(n.messages).takeIf { it.isNotEmpty() }?.joinToString("\n") {
            if (it.sender.isBlank()) it.text else "${it.sender}: ${it.text}"
        } ?: n.text.orEmpty()
        b.body.text = if (summary) NotificationSummary.summarize(n) else original
        b.root.setOnClickListener {
            val dialog = AlertDialog.Builder(b.root.context).setTitle("${n.appLabel} · ${n.title.orEmpty()}")
                .setMessage(original).setPositiveButton("닫기", null)
            if (TodoExtractor.candidates(n).isNotEmpty()) dialog.setNeutralButton("할 일 추천 보기") { _, _ ->
                b.root.context.startActivity(Intent(b.root.context, TodosActivity::class.java).putExtra("candidates", true))
            }
            dialog.show()
        }
        b.flags.text = listOfNotNull(
            if (n.isQuestion) "질문·요청" else null,
            n.amount?.let { "${it}원" },
        ).joinToString(" · ")
        b.flags.visibility = if (b.flags.text.isBlank()) View.GONE else View.VISIBLE
    }

    companion object {
        private val FMT = DateTimeFormatter.ofPattern("M/d HH:mm")
        val DIFF = object : DiffUtil.ItemCallback<NotifItem>() {
            override fun areItemsTheSame(a: NotifItem, b: NotifItem) = a.id == b.id
            override fun areContentsTheSame(a: NotifItem, b: NotifItem) = a == b
        }
    }
}
