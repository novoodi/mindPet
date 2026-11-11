package com.maeumpet.app.ui

import android.os.Bundle
import android.text.InputType
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
import java.text.NumberFormat
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

class ExpensesActivity : AppCompatActivity() {
    private val db by lazy { NotifDatabase.get(this) }
    private var month = YearMonth.now()
    private var observeJob: Job? = null
    private var rows = emptyList<Expense>()
    private lateinit var monthLabel: TextView
    private lateinit var totals: TextView
    private lateinit var empty: TextView
    private lateinit var adapter: ExpenseAdapter
    private val money = NumberFormat.getIntegerInstance(Locale.KOREA)
    private fun won(value: Long) = "${money.format(value)}원"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        month = savedInstanceState?.getString("month")?.let(YearMonth::parse) ?: month
        title = "지출 장부"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        val nav = LinearLayout(this)
        nav.addView(Button(this).apply { text = "이전"; setOnClickListener { month = month.minusMonths(1); observe() } })
        monthLabel = TextView(this).apply { textSize = 18f; gravity = android.view.Gravity.CENTER }
        nav.addView(monthLabel, LinearLayout.LayoutParams(0, -1, 1f))
        nav.addView(Button(this).apply { text = "다음"; setOnClickListener { month = month.plusMonths(1); observe() } })
        root.addView(nav)
        totals = TextView(this).apply { textSize = 18f; setPadding(8, 16, 8, 16) }
        root.addView(totals)
        root.addView(Button(this).apply { text = "사용처별 합계"; setOnClickListener { showMerchants() } })
        root.addView(TextView(this).apply {
            text = "원화 결제·환불 알림을 기기에 저장해. 알림 수신일 기준이며 원문 보관 기간이 지나도 남아. 항목을 눌러 수정·삭제할 수 있어. 중복 알림이나 사용처 미확인은 확인해 줘."
            textSize = 13f; setPadding(8, 12, 8, 12)
        })
        empty = TextView(this).apply { text = "이번 달 저장된 지출이 없어.\n알림 접근과 알림 브리핑이 켜져 있어야 자동 저장돼."; setPadding(8, 24, 8, 24) }
        root.addView(empty)
        adapter = ExpenseAdapter(::edit, ::won)
        root.addView(RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@ExpensesActivity); adapter = this@ExpensesActivity.adapter }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        observe()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    db.notifs().since(0).forEach { item -> ExpenseExtractor.record(item)?.let { db.expenses().insert(it) } }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { Toast.makeText(this@ExpensesActivity, "기존 알림을 가져오지 못했어. 장부를 다시 열어 줘.", Toast.LENGTH_LONG).show() }
        }
    }

    private fun observe() {
        observeJob?.cancel()
        monthLabel.text = "${month.year}년 ${month.monthValue}월"
        rows = emptyList(); adapter.submit(rows); totals.text = "불러오는 중…"; empty.visibility = View.GONE
        val zone = ZoneId.systemDefault()
        val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val until = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        observeJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                db.expenses().observeMonth(from, until).collect {
                    rows = it; adapter.submit(it)
                    val paid = it.filterNot { e -> e.refund }.sumOf { e -> e.amount }
                    val refunded = it.filter { e -> e.refund }.sumOf { e -> e.amount }
                    totals.text = "순지출 ${won(paid - refunded)}\n결제 ${won(paid)} · 환불 ${won(refunded)}"
                    empty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun showMerchants() {
        val text = rows.groupBy { it.merchant }.map { (name, entries) ->
            name to entries.sumOf { if (it.refund) -it.amount else it.amount }
        }.sortedByDescending { it.second }.joinToString("\n\n") { (name, amount) -> "$name   ${won(amount)}" }
        AlertDialog.Builder(this).setTitle("${month.monthValue}월 사용처별 순지출")
            .setMessage(text.ifBlank { "저장된 지출이 없어." }).setPositiveButton("닫기", null).show()
    }

    private fun edit(expense: Expense) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 8) }
        val merchant = EditText(this).apply { hint = "사용처"; setText(expense.merchant); inputType = InputType.TYPE_CLASS_TEXT }
        val amount = EditText(this).apply { hint = "금액 (원)"; inputType = InputType.TYPE_CLASS_NUMBER; setText(expense.amount.toString()) }
        form.addView(merchant); form.addView(amount)
        val dialog = AlertDialog.Builder(this).setTitle(if (expense.refund) "환불 내역 수정" else "지출 내역 수정")
            .setView(form).setPositiveButton("저장", null).setNegativeButton("닫기", null)
            .setNeutralButton("삭제") { _, _ ->
                AlertDialog.Builder(this).setMessage("이 내역을 지출 장부에서 삭제할까?")
                    .setPositiveButton("삭제") { _, _ -> save { db.expenses().delete(expense.id) } }
                    .setNegativeButton("취소", null).show()
            }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = amount.text.toString().toLongOrNull()
                val name = merchant.text.toString().trim()
                if (name.isEmpty() || name.length > 80) { merchant.error = "사용처를 1~80자로 입력해 줘."; return@setOnClickListener }
                if (value == null || value <= 0 || value > 999_999_999_999L) { amount.error = "올바른 금액을 입력해 줘."; return@setOnClickListener }
                save { db.expenses().edit(expense.id, name, value) }; dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun save(action: suspend () -> Unit) {
        lifecycleScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { Toast.makeText(this@ExpensesActivity, "저장하지 못했어. 다시 시도해 줘.", Toast.LENGTH_LONG).show() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) { outState.putString("month", month.toString()); super.onSaveInstanceState(outState) }
    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}

private class ExpenseAdapter(private val onClick: (Expense) -> Unit, private val won: (Long) -> String) : RecyclerView.Adapter<ExpenseAdapter.VH>() {
    private var rows = emptyList<Expense>()
    class VH(val b: ItemNotifBinding) : RecyclerView.ViewHolder(b.root)
    fun submit(items: List<Expense>) { rows = items; notifyDataSetChanged() }
    override fun getItemCount() = rows.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(ItemNotifBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = rows[position]
        holder.b.apply {
            meta.text = Instant.ofEpochMilli(e.postedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M/d HH:mm")) + " · ${e.appLabel}"
            who.text = e.merchant
            body.text = "${if (e.refund) "환불 −" else "결제 "}${won(e.amount)}"
            flags.text = if (e.merchant == "사용처 미확인") "사용처를 입력해 줘 · 눌러서 수정" else "눌러서 수정·삭제"
            root.setOnClickListener { onClick(e) }
        }
    }
}
