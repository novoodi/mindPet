package com.maeumpet.app.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.maeumpet.app.databinding.ActivitySettingsBinding
import com.maeumpet.app.kev.KevRuntime
import com.maeumpet.app.notif.KevRescore
import com.maeumpet.app.notif.NanoSummarizer
import com.maeumpet.app.personal.Prefs
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        title = "설정"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)

        // 알림 브리핑
        b.switchBriefing.isChecked = prefs.briefingEnabled
        b.switchBriefing.setOnCheckedChangeListener { _, checked -> prefs.briefingEnabled = checked }
        b.seekRetention.progress = prefs.notifRetentionDays - 1
        updateRetentionLabel(prefs.notifRetentionDays)
        b.seekRetention.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) = updateRetentionLabel(p + 1)
            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) { prefs.notifRetentionDays = sb.progress + 1 }
        })

        // 알림 치우기
        b.switchAutoClear.isChecked = prefs.autoClearEnabled
        b.switchAutoClear.setOnCheckedChangeListener { _, checked -> prefs.autoClearEnabled = checked; b.switchAutoDefer.isEnabled = checked }
        b.switchAutoDefer.isChecked = prefs.autoDeferEnabled
        b.switchAutoDefer.isEnabled = prefs.autoClearEnabled
        b.switchAutoDefer.setOnCheckedChangeListener { _, checked -> prefs.autoDeferEnabled = checked }
        b.tvClearExempt.setOnClickListener {
            if (prefs.clearExemptPackages.isNotEmpty()) { prefs.clearExemptPackages = emptySet(); refreshStatus() }
        }

        // 홈 화면 팝업
        b.switchHomePopup.isChecked = prefs.homePopupEnabled
        b.switchHomePopup.setOnCheckedChangeListener { _, checked -> prefs.homePopupEnabled = checked }
        b.btnPreviewPopup.setOnClickListener {
            if (!MainActivity.isServiceEnabled(this)) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                prefs.homePopupPreview = true
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            }
        }

        // 펫 상태: 누르면 폰 안 Gemini Nano 지원 여부를 바로 확인(필요하면 내려받기)
        b.tvNano.setOnClickListener { checkNano() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val listening = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        val access = if (MainActivity.isServiceEnabled(this)) "켜짐" else "꺼짐"
        b.tvAccess.text = "알림 접근 ${if (listening) "켜짐" else "꺼짐"} · 접근성 $access"
        val night = prefs.lastNightRunAt
        b.tvNight.text = if (night > 0)
            "마지막 밤 정리 " + Instant.ofEpochMilli(night).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M/d HH:mm"))
        else "밤 정리는 충전 중에 처음 돌아"
        b.tvNano.text = when (prefs.nanoStatus) {
            "AVAILABLE" -> "Gemini Nano 요약: 사용 중"
            "DOWNLOADABLE", "DOWNLOADING" -> "Gemini Nano 요약: 충전 중에 내려받을 예정"
            "UNKNOWN" -> "Gemini Nano 요약: 아직 확인 전"
            else -> "Gemini Nano 요약: 이 폰은 미지원, 규칙 정리로 대체"
        }
        val exempt = prefs.clearExemptPackages.size
        b.tvClearExempt.text = "Kev가 90% 이상 확신한 광고·유도만 치워. 오늘 안 봐도 되는 소식은 낮 12시·저녁 7시까지 숨겼다가 " +
            "알림창에 그대로 다시 띄워. 카톡·문자·결제·배송·일정·보안 알림과 사람이 보낸 메일은 건드리지 않고, 치운 알림은 첫 화면에서 다시 볼 수 있어." +
            if (exempt > 0) "\n건드리지 않을 앱 ${exempt}개 · 눌러서 초기화" else ""
        b.tvKev.text = when {
            !KevRuntime.isInstalled(this) -> "판단 모델(Kev): 없음, 규칙으로만 판단해"
            !KevRescore.isDone(this) -> "판단 모델(Kev): 사용 중 · 최근 알림을 새 모델로 다시 보는 중"
            else -> "판단 모델(Kev): 사용 중 · 카톡 질문·요청, 알림 광고·급함"
        }
        b.tvLearning.text = if (prefs.importanceModelN > 0)
            "중요도 학습 ${prefs.importanceModelN}개 · 정확도 ${(prefs.importanceModelAcc * 100).toInt()}%"
        else "중요도 학습: 알림을 열고 지운 기록이 쌓이면 시작해"
    }

    private fun checkNano() {
        b.tvNano.text = "Gemini Nano 확인 중…"
        lifecycleScope.launch {
            val status = runCatching { NanoSummarizer(this@SettingsActivity).ensureDownloaded() }.getOrElse { "UNAVAILABLE (${it.message})" }
            prefs.nanoStatus = status.substringBefore(' ')
            android.util.Log.d("MaeumPet.Nano", "manual check: $status")
            b.tvNano.text = "Gemini Nano: $status"
        }
    }

    private fun updateRetentionLabel(days: Int) {
        b.tvRetention.text = "알림 원문 ${days}일 보관"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
