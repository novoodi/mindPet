package com.maeumpet.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.maeumpet.app.databinding.ItemNotifBinding
import com.maeumpet.app.notif.DeferSchedule
import com.maeumpet.app.notif.NotifDatabase
import com.maeumpet.app.notif.NotifItem
import com.maeumpet.app.personal.Prefs
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 펫이 알림창에서 치운 광고·유도 알림과 나중으로 미룬 알림. 원래 앱을 열거나, 그 앱을 다시는 건드리지 않게 할 수 있다. */
class ClearedActivity : AppCompatActivity() {
    private val adapter = ClearedAdapter(::onPick)
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "펫이 정리한 알림"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        root.addView(TextView(this).apply {
            text = "광고·참여 유도로 확신한 알림은 치우고, 오늘 안 봐도 되는 소식은 낮 12시·저녁 7시에 알림창으로 다시 띄워. 카톡·문자·결제·배송·일정·보안 알림은 건드리지 않아. 최근 7일 기록이야."
            setPadding(0, pad / 2, 0, pad / 2)
        })
        empty = TextView(this).apply { text = "아직 치운 알림이 없어."; setPadding(0, pad, 0, pad); visibility = View.GONE }
        root.addView(empty)
        root.addView(RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@ClearedActivity); adapter = this@ClearedActivity.adapter },
            LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        val since = System.currentTimeMillis() - 7 * 24 * 3600_000L
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NotifDatabase.get(this@ClearedActivity).notifs().observeTidied(since).collect {
                    adapter.submit(it)
                    empty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun onPick(n: NotifItem) {
        val prefs = Prefs(this)
        val exempt = n.packageName in prefs.clearExemptPackages
        AlertDialog.Builder(this)
            .setTitle("${n.appLabel} · ${n.title.orEmpty()}")
            .setMessage(n.text.orEmpty())
            .setPositiveButton("앱 열기") { _, _ ->
                val launch = packageManager.getLaunchIntentForPackage(n.packageName)
                if (launch == null || runCatching { startActivity(launch) }.isFailure)
                    Toast.makeText(this, "${n.appLabel}을(를) 열 수 없어.", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(if (exempt) "이 앱도 다시 정리하기" else "이 앱은 건드리지 마") { _, _ ->
                prefs.clearExemptPackages = if (exempt) prefs.clearExemptPackages - n.packageName else prefs.clearExemptPackages + n.packageName
                Toast.makeText(this, if (exempt) "${n.appLabel} 알림도 다시 정리할게." else "앞으로 ${n.appLabel} 알림은 건드리지 않을게.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}

private class ClearedAdapter(private val onPick: (NotifItem) -> Unit) : RecyclerView.Adapter<ClearedAdapter.VH>() {
    private var rows = emptyList<NotifItem>()
    class VH(val b: ItemNotifBinding) : RecyclerView.ViewHolder(b.root)

    fun submit(items: List<NotifItem>) { rows = items; notifyDataSetChanged() }
    override fun getItemCount() = rows.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(ItemNotifBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: VH, position: Int) {
        val n = rows[position]
        val now = System.currentTimeMillis()
        val time = Instant.ofEpochMilli(n.postedAt).atZone(ZoneId.systemDefault()).format(FMT)
        val what = when (val until = n.deferredUntil) {
            null -> "치움"
            in now + 1..Long.MAX_VALUE -> "${DeferSchedule.label(until, now)}에 다시"
            else -> "다시 띄움"
        }
        holder.b.meta.text = "$what · $time · ${n.appLabel}"
        holder.b.who.text = n.title.orEmpty()
        holder.b.who.visibility = if (n.title.isNullOrBlank()) View.GONE else View.VISIBLE
        holder.b.body.text = n.text.orEmpty()
        holder.b.flags.visibility = View.GONE
        holder.b.root.setOnClickListener { onPick(n) }
    }

    companion object { private val FMT = DateTimeFormatter.ofPattern("M/d HH:mm") }
}
