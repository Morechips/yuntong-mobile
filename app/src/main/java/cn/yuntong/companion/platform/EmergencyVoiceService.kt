package cn.yuntong.companion.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import cn.yuntong.companion.MainActivity
import cn.yuntong.companion.YuntongApplication
import cn.yuntong.companion.domain.VoiceSosCommand
import cn.yuntong.companion.domain.VoiceSosConfirmation
import cn.yuntong.companion.domain.ConfirmationStep
import cn.yuntong.companion.domain.VoiceSosMode
import cn.yuntong.companion.domain.SosAction
import cn.yuntong.companion.domain.SOS_CANCEL_SECONDS
import cn.yuntong.companion.domain.sosActions
import cn.yuntong.companion.domain.voiceSosCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/** Explicitly armed, non-sticky microphone guard. Never starts from a background event. */
class EmergencyVoiceService : Service(), RecognitionListener {
    companion object {
        const val START = "cn.yuntong.companion.VOICE_GUARD_START"
        const val STOP = "cn.yuntong.companion.VOICE_GUARD_STOP"
        const val CANCEL = "cn.yuntong.companion.VOICE_SOS_CANCEL"
        private const val CHANNEL = "emergency_voice"
        private const val NOTIFICATION_ID = 21
        private const val MAX_GUARD_MS = 60 * 60 * 1000L
    }

    private val tasks = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller get() = (application as YuntongApplication).controller
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var listener: SpeechService? = null
    private var countdown: Job? = null
    private var confirmTimeout: Job? = null
    private var timeout: Job? = null
    private var armedContactId = ""
    private var armedNumber = ""
    private var started = false
    private var triggered = false
    private var mode = VoiceSosMode.PRACTICE
    private var finalStatus = "语音守护已关闭"
    private val practice get() = mode == VoiceSosMode.PRACTICE
    private var partialCallbacks = 0
    private var lastDiagnosticAt = 0L
    private var lastPartial = ""
    private val confirmation = VoiceSosConfirmation()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> {
                finalStatus = if (practice) "口令练习已手动结束，没有拨号或发短信" else "语音守护已手动关闭"
                stopSelf()
                return START_NOT_STICKY
            }
            CANCEL -> {
                if (countdown?.isActive == true) {
                    countdown?.cancel()
                    countdown = null
                    triggered = true
                    finalStatus = if (practice) "练习取消成功，没有拨号或发短信" else "本次求助已取消，语音守护已关闭"
                    controller.voiceGuardStatus(finalStatus, false)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            START -> Unit
            else -> return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        mode = when (intent.getStringExtra("mode")) {
            "practice" -> VoiceSosMode.PRACTICE
            "live" -> VoiceSosMode.LIVE
            else -> {
                finalStatus = "语音服务未启动：模式无效"
                controller.voiceGuardStatus(finalStatus, false)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        started = true
        armedContactId = intent.getStringExtra("contactId").orEmpty()
        armedNumber = intent.getStringExtra("number").orEmpty()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            (!practice && !controller.canArmVoiceGuard(armedContactId, armedNumber))) {
            finalStatus = if (practice) "口令练习未开启：请允许麦克风权限" else "语音守护未开启：请检查联系人、麦克风和电话权限"
            controller.voiceGuardStatus(finalStatus, false)
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "紧急求助语音守护", NotificationManager.IMPORTANCE_LOW))
        foreground("正在加载离线语音模型", false)
        controller.voiceGuardStatus("正在加载离线语音模型，首次开启需稍候", false, practice, true)
        tasks.launch {
            try {
                val path = withContext(Dispatchers.IO) { OfflineVoiceModel.unpack(this@EmergencyVoiceService) }
                if (!isActive) return@launch
                model = withContext(Dispatchers.Default) { Model(path.absolutePath) }
                if (!isActive) return@launch
                recognizer = Recognizer(model, 16_000f)
                listener = SpeechService(recognizer, 16_000f)
                check(listener?.startListening(this@EmergencyVoiceService) == true) { "麦克风无法开始监听" }
                controller.voiceGuardStatus(if (practice) "口令练习已开启 · 请说两遍“紧急求助”" else "语音守护已开启 · 请说两遍“紧急求助”", !practice, practice, true)
                foreground(if (practice) "练习中，请分两次说“紧急求助”；不拨号不发短信" else "请分两次说“紧急求助”确认", false)
                timeout = tasks.launch {
                    delay(MAX_GUARD_MS)
                    finalStatus = if (practice) "口令练习已运行一小时，请重新开启" else "语音守护已运行一小时，请重新开启"
                    stopSelf()
                }
            } catch (error: Exception) {
                finalStatus = "${if (practice) "口令练习" else "语音守护"}启动失败：${error.message ?: "麦克风或模型不可用"}"
                controller.voiceGuardStatus(finalStatus, false)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun foreground(message: String, cancelable: Boolean) {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).putExtra("page", "紧急求助")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, EmergencyVoiceService::class.java).setAction(if (cancelable) CANCEL else STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (practice) "云瞳求助口令练习" else if (cancelable) "云瞳即将联系紧急联系人" else "云瞳语音求助守护")
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, if (cancelable) "取消" else if (practice) "结束练习" else "关闭守护", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun handle(hypothesis: String) {
        if (!started || triggered) return
        val text = runCatching { JSONObject(hypothesis).optString("text") }.getOrDefault("")
        when (voiceSosCommand(text)) {
            VoiceSosCommand.CANCEL -> if (countdown?.isActive == true) {
                countdown?.cancel()
                countdown = null
                triggered = true
                finalStatus = if (practice) "练习取消成功，没有拨号或发短信" else "本次求助已取消，语音守护已关闭"
                controller.voiceGuardStatus(finalStatus, false)
                stopSelf()
            } else if (confirmTimeout?.isActive == true) {
                confirmTimeout?.cancel()
                confirmTimeout = null
                confirmation.reset()
                foreground(if (practice) "练习中，请分两次说“紧急求助”" else "请分两次说“紧急求助”确认", false)
                controller.voiceGuardStatus("首次口令已取消，继续监听", !practice, practice, true)
            }
            VoiceSosCommand.HELP -> if (countdown == null && !triggered) {
                when (confirmation.offer(SystemClock.elapsedRealtime())) {
                    ConfirmationStep.AWAIT_REPEAT -> {
                        confirmTimeout?.cancel()
                        confirmTimeout = tasks.launch(start = CoroutineStart.LAZY) {
                            visibleCountdown("重复确认剩余", confirmation.windowMs, false)
                            confirmation.reset()
                            confirmTimeout = null
                            foreground(if (practice) "练习中，请分两次说“紧急求助”" else "请分两次说“紧急求助”确认", false)
                            controller.voiceGuardStatus("重复确认超时，已继续监听", !practice, practice, true)
                        }
                        controller.voiceGuardStatus("已听到一次，请在 10 秒内再说一次“紧急求助”确认；尚未拨号或发短信", !practice, practice, true)
                        confirmTimeout?.start()
                        controller.notice("已听到求助，请再重复一次口令确认", important = true)
                    }
                    ConfirmationStep.CONFIRMED -> {
                        confirmTimeout?.cancel()
                        confirmTimeout = null
                        startCountdown()
                    }
                    ConfirmationStep.TOO_SOON -> Unit
                }
            }
            VoiceSosCommand.NONE -> Unit
        }
    }

    private fun startCountdown() {
        countdown = tasks.launch(start = CoroutineStart.LAZY) {
            visibleCountdown(if (practice) "练习取消窗口剩余" else "求助取消窗口剩余", SOS_CANCEL_SECONDS * 1_000L, true)
            triggered = true
            countdown = null
            if (practice) {
                finalStatus = "练习完成，口令与倒计时正常；没有拨号或发短信"
                stopSelf()
                return@launch
            }
            foreground("正在请求拨打紧急联系人，并尝试发送短信", false)
            listener?.cancel()
            listener?.shutdown()
            listener = null
            recognizer?.close()
            recognizer = null
            val actions = sosActions(mode)
            val called = SosAction.CALL_CONTACT in actions && controller.placeEmergencyCall(armedContactId, armedNumber)
            val texted = SosAction.REQUEST_SMS in actions && controller.sendVoiceEmergencySms(armedContactId, armedNumber)
            finalStatus = when {
                called && texted -> "已请求拨号和短信；短信可能仍需系统确认，守护已关闭"
                called -> "已请求拨号，短信请求失败；守护已关闭"
                texted -> "已请求发短信，拨号失败；短信可能需系统确认"
                else -> "电话和短信均未成功请求，请改用其他方式求助"
            }
            stopSelf()
        }
        controller.voiceGuardStatus(if (practice) "已两次确认，练习倒计时 ${SOS_CANCEL_SECONDS} 秒；可说“取消求助”" else "已两次确认，${SOS_CANCEL_SECONDS} 秒后联系紧急联系人；可说“取消求助”", !practice, practice, true)
        countdown?.start()
        controller.notice(if (practice) "两次口令已确认，${SOS_CANCEL_SECONDS} 秒内可说取消；练习不会拨号或发短信" else "两次口令已确认，${SOS_CANCEL_SECONDS} 秒后联系紧急联系人；如需取消请立即说取消", important = true)
    }

    private suspend fun visibleCountdown(label: String, durationMs: Long, cancelable: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (true) {
            val remainingMs = deadline - SystemClock.elapsedRealtime()
            if (remainingMs <= 0) return
            val seconds = ((remainingMs + 999) / 1_000).toInt()
            controller.voiceGuardCountdown(label, seconds)
            foreground("$label：$seconds 秒", cancelable)
            delay(minOf(remainingMs, 1_000L))
        }
    }

    private fun diagnostic(hypothesis: String, key: String, completed: Boolean) {
        if (!practice || !started || triggered) return
        val heard = runCatching { JSONObject(hypothesis).optString(key) }.getOrDefault("").trim().take(24)
        if (!completed) {
            partialCallbacks++
            val now = SystemClock.elapsedRealtime()
            if (heard == lastPartial && now - lastDiagnosticAt < 1_000) return
            lastPartial = heard
            lastDiagnosticAt = now
            controller.voicePracticeDiagnostic("音频回调 $partialCallbacks 次；识别中：${heard.ifBlank { "尚无文字" }}")
        } else {
            controller.voicePracticeDiagnostic("停顿后识别：${heard.ifBlank { "未形成文字" }}")
        }
    }

    override fun onResult(hypothesis: String) {
        diagnostic(hypothesis, "text", true)
        tasks.launch { handle(hypothesis) }
    }
    override fun onFinalResult(hypothesis: String) {
        diagnostic(hypothesis, "text", true)
        tasks.launch { handle(hypothesis) }
    }
    override fun onPartialResult(hypothesis: String) = diagnostic(hypothesis, "partial", false)
    override fun onTimeout() = Unit
    override fun onError(exception: Exception) {
        finalStatus = "${if (practice) "口令练习" else "语音守护"}已停止：${exception.message ?: "录音异常"}"
        stopSelf()
    }

    override fun onDestroy() {
        started = false
        countdown?.cancel()
        confirmTimeout?.cancel()
        timeout?.cancel()
        tasks.cancel()
        listener?.cancel()
        listener?.shutdown()
        recognizer?.close()
        model?.close()
        controller.voiceGuardStatus(finalStatus, false)
        super.onDestroy()
    }
}
