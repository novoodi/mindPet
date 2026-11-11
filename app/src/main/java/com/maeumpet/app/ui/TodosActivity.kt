package com.maeumpet.app.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.maeumpet.app.databinding.ItemNotifBinding
import com.maeumpet.app.notif.*
import kotlinx.coroutines.*
import java.time.LocalDate

class TodosActivity : AppCompatActivity() {
    private val db by lazy { NotifDatabase.get(this) }
    private var rows = emptyList<TodoItem>()
    private var selected = "OPEN"
    private lateinit var empty: TextView
    private lateinit var tabs: RadioGroup
    private lateinit var adapter: TodoAdapter
    private val states = listOf("OPEN", "CANDIDATE", "DONE")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "할 일"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        selected = savedInstanceState?.getString("tab") ?: if (intent.getBooleanExtra("candidates", false)) "CANDIDATE" else "OPEN"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        root.addView(TextView(this).apply { text = "알림에서 요청을 찾아 추천해. 내용을 확인하고 저장하면 내 할 일이 돼. 마감일은 수정할 수 있어."; setPadding(8, 12, 8, 12) })
        tabs = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        listOf("할 일", "추천", "완료").forEachIndexed { index, label ->
            tabs.addView(RadioButton(this).apply { id = View.generateViewId(); text = label; tag = states[index] }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(tabs)
        tabs.check(tabs.getChildAt(states.indexOf(selected).coerceAtLeast(0)).id)
        tabs.setOnCheckedChangeListener { group, id -> selected = group.findViewById<RadioButton>(id).tag as String; render() }
        empty = TextView(this).apply { setPadding(12, 40, 12, 40) }
        root.addView(empty)
        adapter = TodoAdapter(::edit, ::action, ::dismiss)
        root.addView(RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@TodosActivity); adapter = this@TodosActivity.adapter }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { db.todos().observeAll().collect { rows = it; render() } }
        }
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { db.notifs().since(0).forEach { n -> TodoExtractor.candidates(n).forEach { db.todos().insert(it) } } } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { Toast.makeText(this@TodosActivity, "추천을 불러오지 못했어. 다시 열어 줘.", Toast.LENGTH_LONG).show() }
        }
    }

    private fun render() {
        val filtered = rows.filter { it.status == selected }.let { items ->
            if (selected == "OPEN") items.sortedWith(compareBy<TodoItem> { it.dueDay ?: Long.MAX_VALUE }.thenByDescending { it.createdAt }) else items
        }
        adapter.submit(filtered)
        empty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when (selected) {
            "CANDIDATE" -> "아직 추천할 요청이 없어.\n알림 접근과 알림 브리핑이 켜져 있으면 새 요청을 찾아둘게."
            "DONE" -> "완료한 할 일이 없어."
            else -> "저장한 할 일이 없어.\n‘추천’에서 알림 속 요청을 확인해 봐."
        }
        listOf("할 일", "추천", "완료").forEachIndexed { i, label ->
            (tabs.getChildAt(i) as RadioButton).text = "$label ${rows.count { it.status == states[i] }}"
        }
    }

    private fun action(item: TodoItem) {
        if (item.status == "CANDIDATE") edit(item)
        else write { db.todos().setStatus(item.id, if (item.status == "DONE") "OPEN" else "DONE") }
    }

    private fun dismiss(item: TodoItem) {
        AlertDialog.Builder(this).setTitle(if (item.status == "CANDIDATE") "추천 제외" else "할 일 삭제")
            .setMessage("이 항목을 목록에서 지울까? 같은 원본 알림은 다시 추천하지 않아.")
            .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ -> write { db.todos().dismiss(item.id) } }.show()
    }

    private fun edit(item: TodoItem) {
        var due = item.dueDay?.let(LocalDate::ofEpochDay)
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 12, 32, 12) }
        val titleInput = EditText(this).apply { hint = "할 일 내용"; setText(item.title); maxLines = 5 }
        form.addView(titleInput)
        form.addView(TextView(this).apply { text = "마감일은 알림에서 추정했어. 저장 전에 확인해 줘. 날짜만 기록하며 자동 알람은 울리지 않아." })
        val dateButton = Button(this)
        fun refreshDate() { dateButton.text = due?.let { "마감일: $it · 변경" } ?: "마감일 없음 · 날짜 선택" }
        refreshDate()
        dateButton.setOnClickListener {
            val date = due ?: LocalDate.now()
            DatePickerDialog(this, { _, year, month, day -> due = LocalDate.of(year, month + 1, day); refreshDate() }, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }
        form.addView(dateButton)
        form.addView(Button(this).apply { text = "마감일 지우기"; setOnClickListener { due = null; refreshDate() } })
        form.addView(TextView(this).apply { text = "${item.sourceLabel}\n\n원문\n${item.sourceText}"; setTextIsSelectable(true); setPadding(0, 16, 0, 8) })
        val dialog = AlertDialog.Builder(this).setTitle(if (item.status == "CANDIDATE") "할 일로 저장" else "할 일 수정")
            .setView(ScrollView(this).apply { addView(form) }).setNegativeButton("취소", null).setPositiveButton("저장", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = titleInput.text.toString().trim()
                if (value.isBlank() || value.length > 200) { titleInput.error = "할 일을 1~200자로 입력해 줘."; return@setOnClickListener }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                lifecycleScope.launch {
                    try {
                        db.todos().save(item.id, value, due?.toEpochDay())
                        dialog.dismiss()
                        if (item.status == "CANDIDATE") { tabs.check(tabs.getChildAt(0).id); Toast.makeText(this@TodosActivity, "할 일에 저장했어.", Toast.LENGTH_SHORT).show() }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true; Toast.makeText(this@TodosActivity, "저장하지 못했어. 다시 시도해 줘.", Toast.LENGTH_LONG).show() }
                }
            }
        }
        dialog.show()
    }

    private fun write(block: suspend () -> Unit) {
        lifecycleScope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { Toast.makeText(this@TodosActivity, "변경하지 못했어. 다시 시도해 줘.", Toast.LENGTH_LONG).show() }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("tab", selected); super.onSaveInstanceState(outState) }
    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}

private class TodoAdapter(private val edit: (TodoItem) -> Unit, private val action: (TodoItem) -> Unit, private val dismiss: (TodoItem) -> Unit) : RecyclerView.Adapter<TodoAdapter.VH>() {
    private var rows = emptyList<TodoItem>()
    class VH(val container: LinearLayout, val b: ItemNotifBinding, val action: Button, val remove: Button) : RecyclerView.ViewHolder(container)
    fun submit(items: List<TodoItem>) { rows = items; notifyDataSetChanged() }
    override fun getItemCount() = rows.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val container = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL; layoutParams = RecyclerView.LayoutParams(-1, -2) }
        val b = ItemNotifBinding.inflate(LayoutInflater.from(parent.context), container, false)
        container.addView(b.root)
        val actions = LinearLayout(parent.context)
        val action = Button(parent.context); val remove = Button(parent.context)
        actions.addView(action, LinearLayout.LayoutParams(0, -2, 1f)); actions.addView(remove, LinearLayout.LayoutParams(0, -2, 1f))
        container.addView(actions)
        return VH(container, b, action, remove)
    }
    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = rows[position]
        holder.b.apply {
            meta.text = t.sourceLabel
            who.text = t.title
            body.text = t.dueDay?.let { day -> "마감 ${LocalDate.ofEpochDay(day)}" + if (t.status == "OPEN" && day < LocalDate.now().toEpochDay()) " · 기한 지남" else "" } ?: "마감일 없음"
            flags.text = "눌러서 원문 확인·수정"
            root.setOnClickListener { edit(t) }
        }
        holder.action.text = when (t.status) { "CANDIDATE" -> "할 일로 저장"; "DONE" -> "되돌리기"; else -> "완료" }
        holder.remove.text = if (t.status == "CANDIDATE") "추천 제외" else "삭제"
        holder.action.setOnClickListener { action(t) }; holder.remove.setOnClickListener { dismiss(t) }
    }
}
