package cn.yuntong.companion

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.PhoneNumberUtils
import android.telecom.TelecomManager
import android.os.Bundle
import android.location.LocationManager
import android.location.Location
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.os.CancellationSignal
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Base64
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.room.Room
import cn.yuntong.companion.data.*
import cn.yuntong.companion.domain.*
import cn.yuntong.companion.navigation.*
import cn.yuntong.companion.platform.*
import cn.yuntong.companion.recognition.*
import cn.yuntong.companion.transport.*
import java.io.File
import java.util.UUID
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

data class ScreenState(
    val assistantTask: String = "",
    val resultKind: String = "",
    val resultFrameId: String = "",
    val planning: Boolean = false,
    val notice: String = "欢迎使用云瞳",
    val noticeSpoken: Boolean = false,
    val connection: String = "未连接",
    val peer: DevicePeer? = null,
    val deviceCommand: String? = null,
    val navigationDelivery: String = "等待导航会话",
    val delivery: String = "尚未发送",
    val device: String = "等待设备状态",
    val perception: String = "尚无感知结果",
    val guidance: String = "",
    val guidanceExpired: Boolean = false,
    val navigationIssue: String = "",
    val navigation: NavigationState = NavigationState(),
    val places: List<Place> = emptyList(),
    val selected: Place? = null,
    val route: String = "",
    val searching: Boolean = false,
    val frame: ImageFrame? = null,
    val findings: List<Finding> = emptyList(),
    val result: String = "",
    val busy: Boolean = false,
    val pendingCloud: JSONObject? = null,
    val frameStatus: String = "尚无图像",
    val recordingStatus: String = "未录制",
    val emergencyLocation: String = "尚未获取当前位置",
    val emergencyLocationAt: Long = 0L,
    val emergencyLocating: Boolean = false,
    val smsStatus: String = "尚未发送求助短信",
    val callStatus: String = "尚未请求求助电话",
    val voiceGuardStatus: String = "语音守护未开启",
    val voiceGuardArmed: Boolean = false,
    val voiceGuardActive: Boolean = false,
    val voicePractice: Boolean = false,
    val voicePracticeDiagnostic: String = "",
    val voiceCountdownLabel: String = "",
    val voiceCountdownSeconds: Int = 0,
    val nearby: Boolean = false,
    val practice: Int = 0,
)

class AppController(val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = Settings(context)
    val prefs = settings.flow.stateIn(scope, SharingStarted.Eagerly, Preferences())
    val secrets = SecretStore(context)
    private val db = Room.databaseBuilder(context, AppDatabase::class.java, "yuntong-v1.db").build()
    val records = db.records().observe().stateIn(scope, SharingStarted.Eagerly, emptyList())
    private val mutable = MutableStateFlow(ScreenState())
    val state = mutable.asStateFlow()
    val speech = SpeechOutput(context) { message -> scope.launch { notice(message) } }
    private val speechPolicy = SpeechPolicy()
    private val publisher = NavigationPublisher()
    private val local = LocalRecognition(context)
    private val cloud = CloudRecognition(context)
    private var recognition: Job? = null
    private var recognitionEpoch = 0
    private var remoteRequestEpoch = 0
    private var demo: Job? = null
    private var streamEnabled = true
    private var frameEpoch = 0
    private var pendingCaptureId: String? = null
    private var captureTask: String? = null
    @Volatile private var recordingChannel: Channel<Bitmap>? = null
    @Volatile private var clearingRecords = false
    private var recordingJob: Job? = null
    private var pendingManualSmsId: String? = null
    private var lastManualSmsId: String? = null
    private var pendingManualSmsAt = 0L
    private var lastDeviceSequence = 0L
    private var lastAckSequence = 0L
    private var connectionReady = false
    private var planned = false
    private var navigating = false
    private var planning = false
    private var replanning = false
    private var guidanceAt = 0L
    private var guidanceExpiresAt = 0L
    private var statusAt = 0L
    private var pendingDeviceId: String? = null
    private var prepareAfterStart = false
    private val rdkSentAt = linkedMapOf<Long, Long>()
    private var amap: AmapNavigation? = null
    private val link =
        WebSocketDeviceLink(
            scope,
            { status ->
                val peer = DevicePeer.entries.firstOrNull { it.connectedLabel == status }
                val lostConnection = connectionReady && peer == null
                connectionReady = peer != null
                if (!connectionReady) {
                    stopRecording()
                    media.pause()
                    pendingCaptureId = null
                    publisher.forgetReplies()
                    pendingDeviceId = null
                    prepareAfterStart = false
                    rdkSentAt.clear()
                    guidanceAt = 0L
                    frameEpoch++
                    speech.stop()
                    if (state.value.pendingCloud != null) cancelRecognition()
                    update {
                        copy(
                            guidance = "",
                            device = "等待设备状态",
                            perception = "尚无感知结果",
                            delivery = "连接不可用，未确认接收",
                            deviceCommand = null,
                            navigationDelivery = "连接不可用",
                        )
                    }
                }
                update { copy(connection = status, peer = peer) }
                if (peer == DevicePeer.RDK) media.resume()
                if (
                    lostConnection &&
                        prefs.value.cooperative &&
                        state.value.navigation.phase == "active"
                )
                    notice("设备连接已中断，协同提示暂停。请停下确认，可重连或结束导航。", important = true)
            },
            ::receive,
        )

    init {
        scope.launch { prefs.collect { speech.rate = it.rate } }
        scope.launch {
            while (isActive) {
                delay(200)
                val now = System.currentTimeMillis()
                val monotonicNow = SystemClock.elapsedRealtime()
                val f = state.value.frame
                if (streamEnabled && f?.source in setOf("设备测试流", "眼镜实时画面") && now - (f?.receivedAt ?: now) > 4000)
                    update { copy(frameStatus = "图传中断：最后图像已过期") }
                if (state.value.peer == DevicePeer.RDK && statusAt != 0L &&
                    monotonicNow - statusAt > 3000
                ) {
                    statusAt = 0L
                    update { copy(device = "眼镜状态已过期", perception = "等待新的感知状态") }
                }
                if (state.value.guidance.isNotEmpty() && monotonicNow >= guidanceExpiresAt) {
                    update { copy(guidance = "", guidanceExpired = true) }
                    if (speech.state.value.source == "设备助行") speech.stop()
                    if (state.value.peer == DevicePeer.TEST && prefs.value.cooperative &&
                        state.value.navigation.phase == "active")
                        notice("设备提示已过期，正在等待新结果。", important = true)
                }
            }
        }
    }

    private val media = RdkMediaLink(scope, ::receiveMedia)

    private fun receiveMedia(msg: JSONObject) {
        when (msg.optString("type")) {
            "frame" -> {
                val requestId = msg.optString("requestId")
                if (requestId.isNotEmpty()) {
                    if (requestId != pendingCaptureId) return
                    pendingCaptureId = null
                    decodeFrame(msg, "眼镜新画面", captureTask)
                    captureTask = null
                } else if (streamEnabled && state.value.peer == DevicePeer.RDK) {
                    decodeFrame(msg, "眼镜实时画面")
                }
            }
            "media.unavailable" -> if (msg.optString("requestId") == pendingCaptureId) {
                pendingCaptureId = null
                captureTask = null
                notice("眼镜暂未送来新画面，请检查观察状态或使用手机相机")
            }
        }
    }

    private fun update(block: ScreenState.() -> ScreenState) {
        mutable.update { block(it).copy(planning = planning) }
    }

    fun notice(text: String, important: Boolean = false) {
        update { copy(notice = text, noticeSpoken = important) }
        if (important) {
            speak(text, automatic = true, source = "状态提醒")
        }
    }

    fun save(kind: String, title: String, content: String, source: String = "手机") {
        scope.launch {
            db.records().put(Record(UUID.randomUUID().toString(), kind, title, content, source))
        }
    }

    fun savePhoneRecording(file: File) {
        scope.launch {
            val valid = withContext(Dispatchers.IO) {
                runCatching {
                    file.canonicalFile.parentFile == File(context.filesDir, "recordings").canonicalFile &&
                        file.isFile && file.length() > 0 &&
                        android.media.MediaMetadataRetriever().let { retriever ->
                            try {
                            retriever.setDataSource(file.absolutePath)
                            (retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                                ?.toLongOrNull() ?: 0L) > 0L
                            } finally { retriever.release() }
                        }
                }.getOrDefault(false)
            }
            if (!valid) {
                file.delete()
                notice("录像未保存成功，请重试")
                return@launch
            }
            save("录像", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(java.util.Date()),
                file.absolutePath, "手机相机")
            notice("手机录像已保存，可在本机录像中回放")
        }
    }

    fun delete(id: String) {
        scope.launch {
            val record = records.value.firstOrNull { it.id == id }
            if (record != null && record.kind in setOf("关键帧", "录像"))
                withContext(Dispatchers.IO) {
                    val file = File(record.content)
                    if (
                        file.canonicalFile.parentFile ==
                            File(context.filesDir, if (record.kind == "录像") "recordings" else "frames").canonicalFile
                    )
                        file.delete()
                }
            db.records().delete(id)
        }
    }

    fun migrate(data: JSONObject) {
        scope.launch {
            val marker = context.getSharedPreferences("migration", Context.MODE_PRIVATE)
            if (marker.getBoolean("v1", false)) return@launch
            val old = runCatching { JSONObject(data.optString("preferences")) }.getOrNull()
            if (old != null) {
                settings.bool("contrast", old.optBoolean("contrast"))
                settings.bool("feedback", old.optBoolean("feedback", true))
                settings.number(
                    "large",
                    when (old.optString("large")) {
                        "large" -> 1.2f
                        "larger" -> 1.4f
                        else -> 1f
                    },
                )
                settings.number(
                    "rate",
                    when (old.optString("rate")) {
                        "slow" -> .8f
                        "fast" -> 1.2f
                        else -> 1f
                    },
                )
            }
            val history =
                runCatching { JSONObject(data.optString("tools")).optJSONArray("history") }
                    .getOrNull()
            var count = 0
            if (history != null)
                for (i in 0 until minOf(60, history.length())) {
                    val r = history.optJSONObject(i) ?: continue
                    val text = r.optString("text")
                    val at = r.optLong("at")
                    if (
                        text.length !in 1..800 ||
                            at <= 0 ||
                            at > System.currentTimeMillis() + 86400000
                    )
                        continue
                    db.records()
                        .put(
                            Record(
                                "legacy-${r.optString("id",i.toString())}",
                                "提示",
                                text.take(40),
                                text,
                                "0.2.0：${r.optString("source")}",
                                at,
                            )
                        )
                    count++
                }
            // The old HTTP endpoint isn't a WebSocket endpoint. Preserve it without converting
            // protocols.
            val connection = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
            connection.getString("endpoint", null)?.let { secrets.put("legacyEndpoint", it) }
            connection.getString("token", null)?.let { secrets.put("legacyToken", it) }
            marker.edit().putBoolean("v1", true).apply()
            if (old != null || count > 0) notice("已迁移旧版偏好和 $count 条提示记录；旧连接地址已保留")
        }
    }

    fun clearRecords() {
        scope.launch {
            clearingRecords = true
            stopRecording()
            recordingJob?.join()
            db.records().clear()
            settings.bool("autoFallSms", false)
            settings.text("emergencyContactId", "")
            withContext(Dispatchers.IO) {
                File(context.filesDir, "frames")
                    .listFiles()
                    ?.filter { it.isFile && it.extension == "jpg" }
                    ?.forEach { it.delete() }
                File(context.filesDir, "recordings")
                    .listFiles()
                    ?.filter { it.isFile && it.extension == "mp4" }
                    ?.forEach { it.delete() }
            }
            clearingRecords = false
            notice("本机记录、画面和录像已清空")
        }
    }

    fun bool(key: String, value: Boolean) {
        scope.launch { settings.bool(key, value) }
    }

    fun number(key: String, value: Float) {
        scope.launch { settings.number(key, value) }
    }

    fun text(key: String, value: String) {
        scope.launch { settings.text(key, value) }
    }

    fun saveChecks(values: Set<String>, day: String) {
        scope.launch { settings.checks(values, day) }
    }

    fun mode(cooperative: Boolean) {
        if (navigating) {
            notice("请先结束导航，再切换使用方式")
            return
        }
        bool("cooperative", cooperative)
        speech.stop()
    }

    fun speak(text: String, automatic: Boolean = false, source: String = "本机朗读") {
        if (
            automatic &&
                source == "取景引导" &&
                speech.state.value.phase == "speaking" &&
                speech.state.value.source != source
        )
            return
        val reader =
            (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
                .isTouchExplorationEnabled
        if (
            !speechPolicy.allow(
                text,
                source,
                automatic,
                reader,
                android.os.SystemClock.elapsedRealtime(),
            )
        )
            return
        speech.speak(text, source)
    }

    fun vibrate() {
        if (prefs.value.feedback)
            context
                .getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun service(location: Boolean = false) {
        runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, SessionService::class.java).putExtra("location", location),
                )
            }
            .onFailure { notice("后台任务启动失败：${it.message}") }
    }

    fun connect(address: String, token: String) {
        runCatching {
                cancelRecognition()
                streamEnabled = true
                frameEpoch++
                update {
                    copy(frame = null, findings = emptyList(), result = "", frameStatus = "等待设备图像")
                }
                link.connect(address.trim(), token.trim())
                if (DevicePeer.forAddress(address.trim()) == DevicePeer.RDK)
                    media.connect(address.trim(), token.trim())
                secrets.put("deviceAddress", address.trim())
                secrets.put("deviceToken", token.trim())
                service(navigating && publisher.state.source == "amap")
            }
            .onFailure { notice(it.message ?: "连接失败") }
    }

    fun disconnect() {
        stopRecording()
        media.close()
        link.close()
        cancelRecognition()
        if (!navigating) context.stopService(Intent(context, SessionService::class.java))
        notice(if (prefs.value.cooperative) "设备已断开；协同提示已暂停" else "设备已断开")
    }

    private fun transmit(msg: JSONObject?) {
        update { copy(navigation = publisher.state) }
        if (msg == null) return
        val peer = state.value.peer
        if ((prefs.value.cooperative || publisher.state.source == "simulation") &&
            !(peer == DevicePeer.RDK && publisher.state.source == "simulation")
        ) {
            val ok = link.send(msg)
            if (ok && peer == DevicePeer.RDK) {
                rdkSentAt[msg.getLong("sequence")] = SystemClock.elapsedRealtime()
                if (rdkSentAt.size > 64) rdkSentAt.remove(rdkSentAt.keys.first())
                if (msg.optString("type") in setOf("session.start", "route.replace", "navigation.snapshot", "session.ended"))
                    update { copy(navigationDelivery = "导航信息已发送，等待眼镜接收") }
            }
            if (!ok && peer == DevicePeer.RDK)
                update { copy(navigationDelivery = "本次导航更新未发送") }
            if (peer != DevicePeer.RDK)
                update { copy(delivery = if (ok) "已发送，等待接收回执" else "未发送：等待连接恢复") }
        }
    }

    private fun receive(msg: JSONObject) {
        when (msg.optString("type")) {
            "hello.accepted" -> {
                lastDeviceSequence = 0
                lastAckSequence = 0
                publisher.forgetReplies()
                statusAt = SystemClock.elapsedRealtime()
                if (state.value.peer == DevicePeer.RDK)
                    update { copy(frameStatus = "眼镜已连接，可打开实时画面") }
                val snapshot = publisher.snapshot()
                val locationAge = System.currentTimeMillis() - (publisher.state.locationAt ?: 0L)
                if (state.value.peer == DevicePeer.RDK &&
                    (state.value.navigationIssue.isNotBlank() || locationAge !in 0L..5000L)
                ) snapshot?.put("location", JSONObject.NULL)
                transmit(snapshot)
                notice(if (state.value.peer == DevicePeer.RDK) "已连接云瞳眼镜"
                    else "已连接电脑测试接收端", important = true)
            }
            "rejected" -> {
                if (state.value.peer == DevicePeer.RDK && publisher.acceptsReply(msg))
                    update { copy(navigationDelivery = "眼镜拒绝导航更新：${msg.optString("reason")}") }
                if (state.value.peer == DevicePeer.TEST && msg.optString("sessionId") == publisher.state.sessionId)
                    update { copy(delivery = "测试端拒绝更新：${msg.optString("reason")}") }
            }
            "ack" -> {
                val seq = msg.optLong("sequence")
                if (state.value.peer == DevicePeer.RDK) {
                    if (msg.optString("stage") == "received" && seq > lastAckSequence &&
                        publisher.acceptsReply(msg)
                    ) {
                        lastAckSequence = seq
                        update { copy(navigationDelivery = "眼镜已接收当前导航信息") }
                    }
                    return
                }
                if (
                    publisher.acceptsReply(msg) &&
                        (seq > lastAckSequence ||
                            seq == lastAckSequence && msg.optString("stage") == "executed")
                ) {
                    lastAckSequence = seq
                    update {
                        copy(
                            delivery =
                                if (msg.optString("stage") == "executed") "测试端报告操作已执行" else "测试端已接收"
                        )
                    }
                }
            }
            "guidance" -> {
                val peer = state.value.peer ?: return
                val now = SystemClock.elapsedRealtime()
                val expiry = if (peer == DevicePeer.RDK) {
                    val reported = msg.optLong("validForMs").takeIf { it in 1..500 } ?: return
                    val sentAt = rdkSentAt[msg.optLong("sequence")] ?: return
                    (reported - (now - sentAt)).takeIf { it > 0 } ?: return
                } else 5000L
                if (
                    msg.opt("testOnly") == (peer == DevicePeer.TEST) &&
                    (peer != DevicePeer.RDK || prefs.value.cooperative && publisher.state.source == "amap" &&
                        state.value.deviceCommand !in setOf("stop", "prepare", "front.confirm")) &&
                    !replanning &&
                        state.value.navigationIssue.isBlank() &&
                        publisher.acceptsReply(msg, true)
                ) {
                    guidanceExpiresAt = now + expiry
                    val output = msg.optString("text").take(2000)
                    if (output.isBlank()) return
                    update { copy(guidance = output, guidanceExpired = false) }
                    if (peer == DevicePeer.TEST)
                        save("提示", output, "会话 ${publisher.state.sessionId}；路线 ${publisher.state.routeVersion}", "电脑模拟结果")
                    if (prefs.value.cooperative && prefs.value.phoneOutput &&
                        (peer == DevicePeer.TEST || guidanceAt == 0L || now - guidanceAt >= 6000)
                    ) {
                        guidanceAt = now
                        speak(output, true, "设备助行")
                    }
                }
            }
            "device.command.ack" -> {
                if (state.value.peer != DevicePeer.RDK ||
                    msg.optString("requestId") != pendingDeviceId || pendingDeviceId == null
                ) return
                val stage = msg.optString("stage")
                if (stage !in setOf("received", "executed", "failed")) return
                val completedAction = state.value.deviceCommand
                val summary = msg.optString("text").take(180)
                if (stage != "received") pendingDeviceId = null
                update { copy(delivery = summary, deviceCommand = deviceCommand.takeIf { stage == "received" }) }
                if (stage != "received") notice(summary, important = true)
                if (stage != "received" && completedAction == "start" && prepareAfterStart) {
                    prepareAfterStart = false
                    if (stage == "executed") deviceAction("prepare")
                }
            }
            "device.status" -> {
                val seq = msg.optLong("deviceSequence")
                if (seq > lastDeviceSequence) {
                    lastDeviceSequence = seq
                    statusAt = SystemClock.elapsedRealtime()
                    update {
                        copy(
                            device = msg.optString("status", "未知"),
                            perception = msg.optString("perception", "未提供"),
                        )
                    }
                }
            }
            "fall.confirmed" -> handleConfirmedFall(msg)
            "frame" -> {
                if (streamEnabled && state.value.peer == DevicePeer.TEST) decodeFrame(msg, "设备测试流")
            }
            "recognition.request" -> {
                if (state.value.peer != DevicePeer.TEST) return
                val epoch = ++remoteRequestEpoch
                scope.launch {
                    val f =
                        withTimeoutOrNull(1200) {
                            state
                                .map { it.frame }
                                .filterNotNull()
                                .first { it.id == msg.optString("frameId") }
                        }
                    if (epoch != remoteRequestEpoch) return@launch
                    if (
                        f != null &&
                            connectionReady &&
                            System.currentTimeMillis() - f.receivedAt < 5000
                    ) {
                        cancelRecognition()
                        streamEnabled = false
                        frameEpoch++
                        update {
                            copy(
                                pendingCloud = msg,
                                frameStatus = "已定格 · 等待确认联网识别",
                                notice = "测试端请求联网识别，请在助手页确认上传",
                            )
                        }
                    } else
                        link.send(
                            JSONObject()
                                .put("schema", "yuntong-link-test-v1")
                                .put("type", "recognition.result")
                                .put("requestId", msg.optString("requestId"))
                                .put("frameId", msg.optString("frameId"))
                                .put("status", "frame_expired")
                        )
                }
            }
            "recognition.cancel" -> {
                remoteRequestEpoch++
                if (state.value.pendingCloud?.optString("requestId") == msg.optString("requestId"))
                    cancelRecognition()
            }
        }
    }

    private fun handleConfirmedFall(msg: JSONObject) {
        if (state.value.peer != DevicePeer.RDK || !connectionReady || !prefs.value.autoFallSms) return
        val eventId = msg.optString("eventId")
        if (!eventId.matches(Regex("[A-Za-z0-9_-]{1,100}")) || msg.optLong("validForMs") !in 1..10_000) return
        val contact = records.value.firstOrNull {
            it.kind == "联系人" && it.id == prefs.value.emergencyContactId
        } ?: run { notice("收到跌倒确认，请先设置紧急联系人", important = true); return }
        if (!canSendSms()) {
            notice("收到跌倒确认，手机暂不能自动发送短信，请打开求助页", important = true)
            return
        }
        val history = context.getSharedPreferences("fall_sms", Context.MODE_PRIVATE)
        if (history.getBoolean(eventId, false)) return
        history.edit().putBoolean(eventId, true).apply()
        val coordinates = shareLocationText()
            ?.substringAfter("云瞳位置：", "")
            ?.substringBefore("（")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val body = fallEmergencyMessage(coordinates)
        runCatching {
            sendSms(contact.content, body, eventId, "fall", contact.title)
            notice("已请求发送跌倒求助短信，等待发送回执", important = true)
        }.onFailure {
            notice("跌倒求助短信发送失败，请打开求助页", important = true)
            save("求助", "发送失败", "事件 $eventId", "手机短信")
        }
    }

    fun manualEmergencyMessage(): String = manualEmergencyMessage(shareLocationText())

    private fun smsApprovalKey(contactId: String, number: String) = "$contactId:$number"

    fun manualSmsApproved(contactId: String, number: String): Boolean =
        context.getSharedPreferences("sms_approval", Context.MODE_PRIVATE)
            .getString("manualContact", "") == smsApprovalKey(contactId, number)

    fun canArmVoiceGuard(contactId: String, number: String): Boolean =
        prefs.value.emergencyContactId == contactId &&
            records.value.any { it.kind == "联系人" && it.id == contactId && it.content == number } &&
            canPlaceEmergencyCall()

    fun canPlaceEmergencyCall(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING)

    fun placeEmergencyCall(contactId: String, number: String): Boolean {
        val contact = records.value.firstOrNull { it.kind == "联系人" && it.id == contactId && it.content == number }
        if (contact == null || !canPlaceEmergencyCall()) {
            update { copy(callStatus = "未发起电话：联系人或拨号权限不可用") }
            return false
        }
        val destination = PhoneNumberUtils.stripSeparators(number)
        if (!destination.matches(Regex("\\+?[0-9]{5,20}"))) {
            update { copy(callStatus = "未发起电话：号码格式无效") }
            return false
        }
        return runCatching {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
                throw SecurityException("电话权限已撤销")
            context.getSystemService(TelecomManager::class.java).placeCall(
                Uri.fromParts("tel", destination, null),
                Bundle().apply { putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true) },
            )
            update { copy(callStatus = "已请求拨打${contact.title}；请以电话界面或对方接听为准") }
            save("求助", "已请求拨号", "预设联系人 · ${contact.title}", "手机电话")
            true
        }.getOrElse {
            update { copy(callStatus = "拨号请求失败：${it.message ?: "系统未允许"}") }
            false
        }
    }

    fun armVoiceGuard(contactId: String, number: String): Boolean {
        if (!canArmVoiceGuard(contactId, number)) {
            notice("请先设置紧急联系人，并允许电话权限")
            return false
        }
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            notice("请先允许麦克风权限")
            return false
        }
        return runCatching {
            voicePracticeDiagnostic("")
            ContextCompat.startForegroundService(context, Intent(context, EmergencyVoiceService::class.java)
                .setAction(EmergencyVoiceService.START)
                .putExtra("mode", "live")
                .putExtra("contactId", contactId).putExtra("number", number))
            voiceGuardStatus("正在启动语音守护", false, false, true)
            true
        }.getOrElse {
            voiceGuardStatus("语音守护启动失败，请保持云瞳在前台重试", false)
            false
        }
    }

    fun startVoicePractice(): Boolean {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            notice("请先允许麦克风权限")
            return false
        }
        return runCatching {
            voicePracticeDiagnostic("等待麦克风识别回调")
            ContextCompat.startForegroundService(context, Intent(context, EmergencyVoiceService::class.java)
                .setAction(EmergencyVoiceService.START).putExtra("mode", "practice"))
            voiceGuardStatus("正在启动口令练习", false, true, true)
            true
        }.getOrElse {
            voiceGuardStatus("口令练习启动失败，请保持云瞳在前台重试", false)
            false
        }
    }

    fun stopVoiceGuard() {
        context.stopService(Intent(context, EmergencyVoiceService::class.java))
    }

    fun voiceGuardStatus(message: String, armed: Boolean, practice: Boolean = false, active: Boolean = armed) {
        update { copy(voiceGuardStatus = message, voiceGuardArmed = armed,
            voicePractice = practice, voiceGuardActive = active,
            voiceCountdownLabel = "", voiceCountdownSeconds = 0) }
    }

    fun voiceGuardCountdown(label: String, seconds: Int) {
        update { copy(voiceCountdownLabel = label, voiceCountdownSeconds = seconds) }
    }

    fun voicePracticeDiagnostic(message: String) {
        update { copy(voicePracticeDiagnostic = message) }
    }

    fun sendVoiceEmergencySms(contactId: String, number: String): Boolean {
        if (!canArmVoiceGuard(contactId, number) || !canSendSms()) {
            update { copy(smsStatus = "语音求助未发送：联系人或短信条件已变化") }
            notice("求助短信未发送，请保持电话联系")
            return false
        }
        if (pendingManualSmsId != null && SystemClock.elapsedRealtime() - pendingManualSmsAt < 30_000) {
            update { copy(smsStatus = "已有求助短信在等待发送回执") }
            return false
        }
        val contact = records.value.first { it.kind == "联系人" && it.id == contactId }
        val eventId = UUID.randomUUID().toString()
        return runCatching {
            sendSms(number, manualEmergencyMessage(), eventId, "voice", contact.title)
            pendingManualSmsId = eventId
            lastManualSmsId = eventId
            pendingManualSmsAt = SystemClock.elapsedRealtime()
            update { copy(smsStatus = "语音求助已请求发送给${contact.title}，等待发送回执") }
            watchSmsReceipt(eventId)
            notice("已请求发送求助短信，等待回执")
            true
        }.getOrElse {
            update { copy(smsStatus = "语音求助短信请求失败，请拨号联系") }
            notice("求助短信请求失败，请保持电话联系")
            false
        }
    }

    fun sendManualEmergencySms(contactId: String, expectedNumber: String, previewBody: String): Boolean {
        val contact = records.value.firstOrNull { it.kind == "联系人" && it.id == contactId }
        if (contact == null || contact.content != expectedNumber) {
            notice("联系人已变化，请重新核对收件人")
            return false
        }
        if (manualEmergencyMessage() != previewBody) {
            notice("位置已更新或过期，请重新核对求助短信")
            return false
        }
        if (!canSendSms()) {
            notice("手机暂不能直接发短信，请检查短信权限和默认短信卡")
            return false
        }
        if (pendingManualSmsId != null && SystemClock.elapsedRealtime() - pendingManualSmsAt < 30_000) {
            notice("上一条求助短信仍在等待发送回执，可先拨号联系")
            return false
        }
        val eventId = UUID.randomUUID().toString()
        return runCatching {
            sendSms(contact.content, previewBody, eventId, "manual", contact.title)
            pendingManualSmsId = eventId
            lastManualSmsId = eventId
            pendingManualSmsAt = SystemClock.elapsedRealtime()
            watchSmsReceipt(eventId)
            context.getSharedPreferences("sms_approval", Context.MODE_PRIVATE).edit()
                .putString("manualContact", smsApprovalKey(contact.id, contact.content)).apply()
            update { copy(smsStatus = "已请求发送给${contact.title}，等待发送回执") }
            notice("已请求直接发送求助短信，等待发送回执", important = true)
            true
        }.getOrElse {
            update { copy(smsStatus = "短信请求失败，请改用拨号") }
            notice("短信请求失败，请改用拨号", important = true)
            false
        }
    }

    fun onSmsReceipt(eventId: String, stage: String, success: Boolean, source: String, contactName: String) {
        val text = when (stage) {
            "sent" -> if (success) "求助短信已发送" else "求助短信发送失败，请拨号联系"
            "delivered" -> if (success) "求助短信已送达" else "短信送达状态未确认，请联系收件人"
            else -> return
        }
        if (source in setOf("manual", "voice") && eventId == lastManualSmsId) {
            update { copy(smsStatus = text) }
            if (stage == "sent") pendingManualSmsId = null
        }
        notice(text, important = true)
        save("求助", text, when (source) {
            "manual" -> "手动求助 · $contactName"
            "voice" -> "语音求助 · $contactName"
            else -> "跌倒事件 $eventId"
        }, "手机短信")
    }

    private fun watchSmsReceipt(eventId: String) {
        scope.launch {
            delay(20_000)
            if (pendingManualSmsId == eventId)
                update { copy(smsStatus = "20 秒仍无发送回执；请查看系统是否弹出短信确认。若电话已接通，请先保持通话。") }
        }
    }

    private fun canSendSms(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING) &&
            SmsManager.getDefaultSmsSubscriptionId() != SubscriptionManager.INVALID_SUBSCRIPTION_ID

    private fun sendSms(number: String, body: String, eventId: String, source: String, contactName: String) {
        val destination = PhoneNumberUtils.stripSeparators(number)
        require(destination.matches(Regex("\\+?[0-9]{5,20}"))) { "invalid SMS destination" }
        val manager = context.getSystemService(SmsManager::class.java)
        val parts = manager.divideMessage(body)
        require(parts.isNotEmpty() && parts.size <= 10) { "invalid SMS body" }
        fun receipt(stage: String, part: Int): PendingIntent {
            val intent = Intent(context, cn.yuntong.companion.platform.SmsReceiptReceiver::class.java)
                .putExtra("eventId", eventId).putExtra("stage", stage)
                .putExtra("source", source).putExtra("contactName", contactName)
                .putExtra("part", part).putExtra("partCount", parts.size)
                .setData(Uri.parse("yuntong-sms://receipt/$stage/$eventId/$part"))
            return PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        if (parts.size == 1) manager.sendTextMessage(destination, null, body, receipt("sent", 0), receipt("delivered", 0))
        else manager.sendMultipartTextMessage(destination, null, parts,
            ArrayList(parts.indices.map { receipt("sent", it) }),
            ArrayList(parts.indices.map { receipt("delivered", it) }))
    }

    private fun decodeFrame(msg: JSONObject, source: String, afterCapture: String? = null) {
        val encoded = msg.optString("jpeg")
        if (encoded.length > 2_000_000) return
        val epoch = ++frameEpoch
        scope.launch {
            val bitmap =
                withContext(Dispatchers.Default) {
                    runCatching {
                            val bytes = Base64.decode(encoded, Base64.DEFAULT)
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                            require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                        .getOrNull()
                }
            if (bitmap != null && streamEnabled && epoch == frameEpoch) {
                update {
                    copy(
                        frame =
                            ImageFrame(
                                msg.optString("frameId"),
                                bitmap,
                                source,
                                System.currentTimeMillis(),
                                msg.optJSONObject("analysis")?.toString(2) ?: "",
                            ),
                        frameStatus = "正在接收 · $source",
                        findings = emptyList(),
                    )
                }
                if (afterCapture != null) recognize(afterCapture)
                if (source == "眼镜实时画面") recordingChannel?.trySend(bitmap)
            }
        }
    }

    fun stream(enabled: Boolean) {
        if (!enabled) stopRecording()
        streamEnabled = enabled
        if (state.value.peer == DevicePeer.RDK) media.subscribe(enabled)
        frameEpoch++
        cancelRecognition()
        notice(if (enabled) "已切换到设备图传" else "已暂停接收画面")
    }

    fun startRecording() {
        if (recordingChannel != null || state.value.peer != DevicePeer.RDK) {
            notice("请先连接眼镜，且一次只录制一段")
            return
        }
        stream(true)
        val channel = Channel<Bitmap>(Channel.CONFLATED)
        recordingChannel = channel
        update { copy(recordingStatus = "正在录制眼镜画面") }
        recordingJob = scope.launch(Dispatchers.IO) {
            val folder = File(context.filesDir, "recordings").apply { mkdirs() }
            val file = File(folder, "${UUID.randomUUID()}.mp4")
            val saved = runCatching {
                val recorder = GlassesRecorder(file)
                var completed = false
                try {
                    withTimeoutOrNull(300_000) { for (frame in channel) recorder.append(frame) }
                } finally {
                    completed = recorder.finish()
                }
                completed
            }.getOrDefault(false)
            if (recordingChannel === channel) recordingChannel = null
            if (saved && !clearingRecords) {
                save("录像", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date()),
                    file.absolutePath, "眼镜实时画面")
                withContext(Dispatchers.Main) { update { copy(recordingStatus = "录像已保存") } }
            } else {
                file.delete()
                withContext(Dispatchers.Main) { update { copy(recordingStatus = "录像失败，请重试") } }
            }
        }
    }

    fun stopRecording() {
        recordingChannel?.close()
        if (recordingChannel != null) update { copy(recordingStatus = "正在保存录像") }
    }

    fun captureFromGlasses(task: String? = null) {
        if (state.value.peer != DevicePeer.RDK) {
            notice("请先连接眼镜")
            return
        }
        val id = UUID.randomUUID().toString()
        media.subscribe(false)
        streamEnabled = true
        frameEpoch++
        pendingCaptureId = id
        captureTask = task
        if (!media.capture(id)) {
            pendingCaptureId = null
            captureTask = null
            notice("眼镜画面连接中，请稍后重试")
        } else notice("已请求眼镜拍摄新画面")
    }

    fun setImage(bitmap: Bitmap, source: String) {
        streamEnabled = false
        frameEpoch++
        cancelRecognition()
        update {
            copy(
                frame =
                    ImageFrame(
                        UUID.randomUUID().toString(),
                        bitmap,
                        source,
                        System.currentTimeMillis(),
                    ),
                frameStatus = source,
                findings = emptyList(),
                result = "",
            )
        }
    }

    fun importImage(uri: Uri, ready: () -> Unit = {}) {
        scope.launch {
            runCatching {
                    withContext(Dispatchers.IO) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        context.contentResolver.openInputStream(uri)?.use {
                            BitmapFactory.decodeStream(it, null, bounds)
                        }
                        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片格式无法读取" }
                        var sample = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
                        context.contentResolver.openInputStream(uri)?.use {
                            BitmapFactory.decodeStream(
                                it,
                                null,
                                BitmapFactory.Options().apply { inSampleSize = sample },
                            )
                        } ?: error("无法打开图片")
                    }
                }
                .onSuccess {
                    setImage(it, "本地图片")
                    ready()
                }
                .onFailure { notice("图片读取失败：${it.message}") }
        }
    }

    fun saveFrame() {
        val f = state.value.frame ?: return
        scope.launch {
            val file =
                withContext(Dispatchers.IO) {
                    File(context.filesDir, "frames")
                        .apply { mkdirs() }
                        .resolve("${UUID.randomUUID()}.jpg")
                        .also { file ->
                            file.outputStream().use {
                                f.bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
                            }
                        }
                }
            save("关键帧", "保存的画面", file.absolutePath, f.source)
            notice("当前画面已保存到本机资料")
        }
    }

    fun cancelRecognition() {
        remoteRequestEpoch++
        state.value.pendingCloud?.let {
            link.send(
                JSONObject()
                    .put("schema", "yuntong-link-test-v1")
                    .put("type", "recognition.result")
                    .put("requestId", it.optString("requestId"))
                    .put("frameId", it.optString("frameId"))
                    .put("status", "cancelled")
            )
        }
        recognitionEpoch++
        recognition?.cancel()
        recognition = null
        update { copy(busy = false, pendingCloud = null) }
        speech.stop()
    }

    fun cancelRecognitionByUser() {
        cancelRecognition()
        notice("识别已取消，可以重新取景或返回助手功能。")
    }

    fun recognize(kind: String, prompt: String = "请描述当前场景", target: String = "") {
        val f =
            state.value.frame
                ?: run {
                    notice("请先拍照、选择图片或连接图传")
                    return
                }
        if (f.source in setOf("设备测试流", "眼镜实时画面", "眼镜新画面") && System.currentTimeMillis() - f.receivedAt > 5000) {
            notice("画面已过期，请重新取景")
            return
        }
        val pending = state.value.pendingCloud
        if (pending != null && pending.optString("frameId") != f.id) {
            cancelRecognition()
            notice("请求对应的图像已更新，请重新请求识别")
            return
        }
        update { copy(pendingCloud = null) }
        cancelRecognition()
        val epoch = recognitionEpoch
        // Freeze the selected frame while a result is being produced and reviewed.
        streamEnabled = false
        if (state.value.peer == DevicePeer.RDK) media.subscribe(false)
        frameEpoch++
        update {
            copy(
                busy = true,
                assistantTask = kind,
                resultKind = kind,
                resultFrameId = f.id,
                result = "",
                findings = emptyList(),
                frameStatus = "已定格 · ${f.source}",
                pendingCloud = pending,
            )
        }
        recognition =
            scope.launch {
                try {
                    val findings =
                        if (kind == "find")
                            local.find(f.bitmap).filter { target.isBlank() || it.label == target }
                        else emptyList()
                    val result =
                        when (kind) {
                            "ocr" -> local.read(f.bitmap)
                            "find" ->
                                if (findings.isEmpty())
                                    "当前画面未发现${if (target.isBlank()) "模型支持的物品" else Labels.chinese(target)}，请调整距离和光线再试。"
                                else
                                    findings.joinToString("\n") {
                                        "${Labels.chinese(it.label)}，画面${if((it.left+it.right)/2<.33)"左侧" else if((it.left+it.right)/2>.67)"右侧" else "中央"}，置信度 ${(it.confidence*100).toInt()}%"
                                    }
                            else ->
                                cloud.ask(
                                    f.bitmap,
                                    prompt,
                                    secrets.get("apiBase"),
                                    secrets.get("apiModel"),
                                    secrets.get("apiKey"),
                                    prefs.value.mobileData,
                                )
                        }
                    ensureActive()
                    if (epoch != recognitionEpoch || state.value.frame?.id != f.id) return@launch
                    update {
                        copy(
                            result = result,
                            findings = findings,
                            busy = false,
                            pendingCloud = null,
                        )
                    }
                    notice("识别完成。结果已显示，可选择朗读内容、收藏或重新取景。")
                    save(
                        if (kind == "ocr") "识字" else "识别",
                        result.take(40),
                        result + "\n图像：${f.id}",
                        f.source,
                    )
                    if (pending != null) {
                        link.send(
                            JSONObject()
                                .put("schema", "yuntong-link-test-v1")
                                .put("type", "recognition.result")
                                .put("requestId", pending.optString("requestId"))
                                .put("frameId", f.id)
                                .put("status", "completed")
                                .put("text", result)
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (epoch == recognitionEpoch) {
                        if (pending != null)
                            link.send(
                                JSONObject()
                                    .put("schema", "yuntong-link-test-v1")
                                    .put("type", "recognition.result")
                                    .put("requestId", pending.optString("requestId"))
                                    .put("frameId", f.id)
                                    .put("status", "failed")
                            )
                        update { copy(busy = false, pendingCloud = null) }
                        notice(e.message ?: "识别失败，请重试")
                    }
                }
            }
    }

    fun chooseAssistant(task: String) {
        cancelRecognition()
        update {
            copy(
                assistantTask = task,
                result = "",
                findings = emptyList(),
                resultKind = "",
                resultFrameId = "",
            )
        }
    }

    fun resetRecognitionResult() {
        cancelRecognition()
        update { copy(result = "", findings = emptyList(), resultKind = "", resultFrameId = "") }
    }

    fun saveConnection(name: String, address: String, token: String): Boolean =
        runCatching {
                WebSocketDeviceLink.validate(address.trim(), token.trim())
                require(name.isNotBlank()) { "请填写设备名称" }
                secrets.put("deviceName", name.trim().take(80))
                secrets.put("deviceAddress", address.trim())
                secrets.put("deviceToken", token.trim())
                notice("连接设置已加密保存，可返回设备页连接")
                true
            }
            .getOrElse {
                notice(it.message ?: "连接设置无法保存")
                false
            }

    fun discardPlan(clearDestination: Boolean) {
        if (navigating) return
        amap?.stop()
        planning = false
        planned = false
        speech.stop()
        update { copy(route = "", selected = if (clearDestination) null else selected) }
    }

    fun configureCloud(base: String, model: String, key: String) {
        secrets.put("apiBase", base.trim())
        secrets.put("apiModel", model.trim())
        secrets.put("apiKey", key.trim())
        notice("联网服务配置已加密保存在本机")
    }

    fun consentMap() {
        bool("mapConsent", true)
        notice("已确认地图服务说明")
    }

    private fun map(): AmapNavigation {
        check(BuildConfig.AMAP_KEY.isNotBlank()) { "高德密钥未配置，请按安装说明配置后重新构建" }
        check(prefs.value.mapConsent) { "请先阅读并确认地图服务说明" }
        return amap
            ?: AmapNavigation(context) { type, data -> scope.launch { mapEvent(type, data) } }
                .also {
                    it.consent()
                    amap = it
                }
    }

    fun search(query: String, city: String) {
        if (query.isBlank()) {
            notice("请输入目的地")
            return
        }
        runCatching {
                update { copy(searching = true) }
                map().search(query, city) { places, error ->
                    update { copy(searching = false, places = places ?: emptyList()) }
                    notice(error ?: "找到 ${places?.size?:0} 个地点，请核对地址")
                }
            }
            .onFailure {
                update { copy(searching = false) }
                notice(it.message ?: "搜索失败")
            }
    }

    fun select(place: Place) {
        if (navigating) stopNavigation()
        if (planning) {
            amap?.stop()
            planning = false
        }
        planned = false
        update { copy(selected = place, route = "", nearby = false) }
        notice("请核对目的地：${place.name}，${place.address}")
    }

    fun favorite() {
        state.value.selected?.let {
            save(
                "地点",
                it.name,
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("address", it.address)
                    .put("lat", it.latitude)
                    .put("lon", it.longitude)
                    .toString(),
                "高德",
            )
        }
        notice("已保存常用地点")
    }

    fun favoriteSelect(record: Record) {
        runCatching {
                JSONObject(record.content).let {
                    select(
                        Place(
                            it.getString("id"),
                            it.getString("name"),
                            it.getString("address"),
                            it.getDouble("lat"),
                            it.getDouble("lon"),
                        )
                    )
                }
            }
            .onFailure { notice("地点记录无法读取") }
    }

    fun plan() {
        val place =
            state.value.selected
                ?: run {
                    notice("请先选择目的地")
                    return
                }
        if (navigating || planning) {
            notice("请等待当前规划或先结束导航")
            return
        }
        runCatching {
                planning = true
                planned = false
                update { copy(route = "") }
                map().plan(place, null, null)
                notice("正在获取定位并规划步行路线")
            }
            .onFailure {
                planning = false
                notice(it.message ?: "规划失败")
            }
    }

    private fun mapEvent(type: String, data: JSONObject) {
        when (type) {
            "route" -> {
                if (!planning && !navigating) return
                planning = false
                replanning = false
                planned = true
                update {
                    copy(
                        route = data.toString(),
                        guidance = "",
                        guidanceExpired = false,
                        navigationIssue = if (navigationIssue == "定位信号较弱") navigationIssue else "",
                    )
                }
                if (navigating) transmit(publisher.newRoute(data))
                notice("路线已更新，可按文字步骤预习")
            }
            "error" -> {
                planning = false
                if (navigating)
                    update {
                        copy(guidance = "", navigationIssue = data.optString("text", "导航服务暂不可用"))
                    }
                notice(data.optString("text"))
            }
            "progress" -> {
                if (navigating && !replanning)
                    transmit(
                        publisher
                            .progress(
                                publisher.state.instruction,
                                data.optInt("meters"),
                                data.optInt("seconds"),
                            )
                            ?.put("sdkProgress", data)
                    )
            }
            "instruction" -> {
                if (
                    navigating &&
                        !replanning &&
                        state.value.navigationIssue.isBlank() &&
                        publisher.state.phase == "active"
                ) {
                    val instruction = data.optString("text")
                    transmit(
                        publisher.progress(
                            instruction,
                            publisher.state.remainingMeters,
                            publisher.state.remainingSeconds,
                        )
                    )
                    if (!prefs.value.cooperative) speak(instruction, true, "地图导航")
                }
            }
            "location" -> {
                if (navigating) {
                    if (
                        state.value.navigationIssue == "定位信号较弱" &&
                            data.optDouble("lat").isFinite() &&
                            data.optDouble("lon").isFinite() &&
                            data.optDouble("accuracy").isFinite()
                    ) {
                        update { copy(navigationIssue = "") }
                    }
                    transmit(
                        publisher
                            .location(
                                data.optDouble("lat"),
                                data.optDouble("lon"),
                                data.optDouble("accuracy").toFloat(),
                                "GCJ-02",
                            )
                            ?.put("sdkLocation", data)
                    )
                }
            }
            "weak" -> {
                if (navigating && state.value.navigationIssue != "定位信号较弱") {
                    speech.stop()
                    publisher.forgetReplies()
                    transmit(publisher.action("location.unavailable"))
                    transmit(publisher.progress("", null, null))
                    update { copy(guidance = "", navigationIssue = "定位信号较弱") }
                    notice("定位信号较弱，请暂停行走并确认环境", important = true)
                }
            }
            "recalculating" -> {
                if (navigating && !replanning) {
                    replanning = true
                    speech.stop()
                    publisher.forgetReplies()
                    update { copy(guidance = "", navigationIssue = "正在重新规划路线") }
                    transmit(publisher.newRoute(JSONObject().put("status", "recalculating")))
                    transmit(publisher.action("route.recalculating"))
                    notice("偏离路线，地图正在重新规划", important = true)
                }
            }
            "nearby" -> {
                if (navigating && !state.value.nearby) {
                    update { copy(nearby = true) }
                    transmit(publisher.action("destination.nearby"))
                    notice("已接近目的地，请确认实际入口后结束导航", important = true)
                }
            }
        }
    }

    fun startNavigation() {
        if (!planned) {
            notice("请先规划路线")
            return
        }
        if (prefs.value.cooperative && !connectionReady) {
            notice("协同模式需要先连接设备；也可切换手机独立使用")
            return
        }
        runCatching {
                map().start()
                navigating = true
                replanning = false
                update { copy(guidance = "", guidanceExpired = false, navigationIssue = "") }
                lastAckSequence = 0
                val place = state.value.selected!!
                transmit(publisher.begin(place.name, "amap"))
                transmit(
                    publisher.newRoute(
                        JSONObject(state.value.route)
                            .put(
                                "destination",
                                JSONObject()
                                    .put("name", place.name)
                                    .put("placeId", place.id)
                                    .put("latitude", place.latitude)
                                    .put("longitude", place.longitude)
                                    .put("coordinateSystem", "GCJ-02")
                                    .put("confirmed", true),
                            )
                    )
                )
                service(true)
                save("导航", "开始步行导航", place.name, "高德")
                notice("步行导航已开始")
            }
            .onFailure { notice(it.message ?: "启动失败") }
    }

    fun pauseNavigation() {
        if (publisher.state.phase !in setOf("active", "paused")) return
        val pause = publisher.state.phase == "active"
        if (pause) amap?.pause() else amap?.resume()
        speech.stop()
        transmit(publisher.transition(if (pause) "paused" else "active"))
        update { copy(guidance = "", guidanceExpired = false) }
        speechPolicy.reset()
        notice(if (pause) "导航已暂停" else "导航已恢复", important = true)
    }

    fun stopNavigation() {
        demo?.cancel()
        amap?.stop()
        planning = false
        planned = false
        navigating = false
        replanning = false
        speech.stop()
        if (publisher.state.phase in setOf("active", "paused")) {
            transmit(publisher.transition("ended"))
            save("导航", "导航已结束", publisher.state.destination, publisher.state.source)
        }
        update {
            copy(
                guidance = "",
                guidanceExpired = false,
                navigationIssue = "",
                nearby = false,
                route = "",
                selected = null,
            )
        }
        if (!connectionReady) context.stopService(Intent(context, SessionService::class.java))
        notice("本次导航已结束")
    }

    fun stopAll() {
        stopNavigation()
        disconnect()
        cancelRecognition()
        context.stopService(Intent(context, SessionService::class.java))
        context.stopService(Intent(context, EmergencyVoiceService::class.java))
    }

    fun deviceAction(action: String) {
        if (state.value.peer == DevicePeer.RDK) {
            if (pendingDeviceId != null) return
            val id = UUID.randomUUID().toString()
            val queued = link.send(JSONObject().put("type", "device.command")
                .put("action", action).put("requestId", id))
            if (!queued) {
                notice("操作未发送，请检查连接。")
                return
            }
            pendingDeviceId = id
            if (action in setOf("stop", "prepare", "front.confirm")) {
                rdkSentAt.clear()
                if (speech.state.value.source == "设备助行") speech.stop()
            }
            update {
                copy(deviceCommand = action, delivery = "已发送，等待眼镜接收",
                    guidance = if (action in setOf("stop", "prepare", "front.confirm")) "" else guidance)
            }
            scope.launch {
                delay(45_000)
                if (pendingDeviceId != id) return@launch
                pendingDeviceId = null
                update { copy(deviceCommand = null, delivery = "操作结果未确认，请刷新设备状态") }
                notice("操作结果未确认，请刷新设备状态。", important = true)
            }
            return
        }
        val msg = publisher.action(action)
        if (msg == null) {
            notice("请先开始导航或演练会话")
            return
        }
        transmit(msg)
        notice("操作已提交，执行状态以回执为准")
    }

    fun startGlassesPreparation() {
        if (state.value.peer != DevicePeer.RDK || pendingDeviceId != null) {
            notice("请先连接眼镜，等待上一条操作结束")
            return
        }
        prepareAfterStart = true
        deviceAction("start")
    }

    fun repeat() {
        val s = state.value
        val expired = s.peer == DevicePeer.RDK && s.guidance.isNotEmpty() &&
            SystemClock.elapsedRealtime() >= guidanceExpiresAt
        val display =
            homeGuidance(
                s.navigation,
                prefs.value.cooperative,
                connectionReady,
                if (expired) "" else s.guidance,
                s.guidanceExpired || expired,
                s.navigationIssue,
                s.peer == DevicePeer.RDK,
            )
        if (prefs.value.cooperative && s.navigation.phase == "active" && connectionReady) {
            if (s.peer == DevicePeer.TEST) deviceAction("repeat")
            if (prefs.value.phoneOutput)
                speak(display.text, source = if (prefs.value.cooperative) "设备助行" else "地图导航")
            else if (!display.available)
                speak(display.text, source = if (prefs.value.cooperative) "设备助行" else "地图导航")
        } else speak(display.text, source = if (prefs.value.cooperative) "设备助行" else "地图导航")
    }

    fun demoStart() {
        stopNavigation()
        navigating = true
        lastAckSequence = 0
        transmit(publisher.begin("教学楼入口（模拟）", "simulation"))
        transmit(
            publisher.newRoute(
                JSONObject()
                    .put("meters", 350)
                    .put("seconds", 300)
                    .put(
                        "steps",
                        JSONArray()
                            .put(JSONObject().put("length", 200).put("text", "沿示例道路直行"))
                            .put(JSONObject().put("length", 150).put("text", "向左转，确认入口")),
                    )
            )
        )
        update { copy(route = publisher.state.route) }
        service()
        demoStep(0)
    }

    fun demoStep(step: Int) {
        if (publisher.state.source != "simulation" || publisher.state.phase != "active") {
            notice("请先开始路线演练")
            return
        }
        val i = step.coerceIn(0, 4)
        val instructions = listOf("沿示例道路直行200米", "前方50米向左转", "向左转", "路线重新规划，继续直行", "已接近教学楼，请确认入口")
        if (i == 3) {
            update { copy(guidance = "", guidanceExpired = false) }
            transmit(publisher.newRoute(JSONObject(publisher.state.route).put("replanned", true)))
        }
        transmit(publisher.progress(instructions[i], 350 - i * 80, 300 - i * 60))
        transmit(publisher.location(31.2304 + i * .0001, 121.4737, 8f, "GCJ-02"))
        update { copy(practice = i, nearby = i == 4) }
        notice(
            if (prefs.value.cooperative) "模拟步骤 ${i+1}：导航信息已更新，等待设备组织提示"
            else "模拟步骤 ${i+1}：${instructions[i]}"
        )
    }

    fun exportRecords(): File {
        val array = JSONArray()
        records.value.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("kind", it.kind)
                    .put("title", it.title)
                    .put("content", it.content)
                    .put("source", it.source)
                    .put("createdAt", it.createdAt)
            )
        }
        return File(context.cacheDir, "exports")
            .apply { mkdirs() }
            .resolve("云瞳记录.json")
            .also { it.writeText(array.toString(2)) }
    }

    fun shareLocationText(): String? {
        val current = state.value
        if (current.emergencyLocationAt > 0 &&
            System.currentTimeMillis() - current.emergencyLocationAt in 0..60_000)
            return current.emergencyLocation
        val n = publisher.state
        if (
            n.latitude != null &&
            n.longitude != null && n.source != "simulation" &&
                System.currentTimeMillis() - (n.locationAt ?: 0) < 60_000
        )
            return "云瞳位置：${n.latitude}, ${n.longitude}（${n.coordinateSystem}，${if(n.source=="simulation")"模拟数据" else "地图定位"}），目的地：${n.destination}，定位时间：${java.util.Date(n.locationAt!!)}"
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return null
        val location = runCatching {
            val manager = context.getSystemService(LocationManager::class.java)
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { manager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
        }.getOrNull() ?: return null
        if (System.currentTimeMillis() - location.time !in 0..60_000) return null
        return "云瞳位置：${location.latitude}, ${location.longitude}（系统定位），定位时间：${java.util.Date(location.time)}"
    }

    fun refreshEmergencyLocation() {
        if (state.value.emergencyLocating) return
        val fine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            notice("请允许定位权限后重试")
            return
        }
        update { copy(emergencyLocating = true, emergencyLocation = "正在获取当前位置", emergencyLocationAt = 0L) }
        scope.launch {
            val location = runCatching {
                val manager = context.getSystemService(LocationManager::class.java)
                val providers = listOfNotNull(
                    LocationManager.NETWORK_PROVIDER.takeIf { manager.isProviderEnabled(it) },
                    LocationManager.GPS_PROVIDER.takeIf { fine && manager.isProviderEnabled(it) },
                )
                var fix: Location? = null
                for (provider in providers) {
                    fix = withTimeoutOrNull(6_000) {
                        kotlinx.coroutines.suspendCancellableCoroutine<Location?> { continuation ->
                            val signal = CancellationSignal()
                            continuation.invokeOnCancellation { signal.cancel() }
                            LocationManagerCompat.getCurrentLocation(manager, provider, signal,
                                ContextCompat.getMainExecutor(context)) { result ->
                                if (continuation.isActive) continuation.resumeWith(Result.success(result))
                            }
                        }
                    }
                    if (fix != null) break
                }
                fix
            }.getOrNull()
            val fresh = location?.takeIf {
                System.currentTimeMillis() - it.time in 0..60_000 &&
                    it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
            }
            if (fresh == null) {
                update { copy(emergencyLocating = false, emergencyLocation = "暂时无法获取新位置，请到开阔处重试") }
                notice("位置暂不可用；仍可拨号或发送不含位置的求助短信")
            } else {
                val coordinates = String.format(Locale.US, "%.6f, %.6f", fresh.latitude, fresh.longitude)
                val accuracy = if (fresh.hasAccuracy()) "，误差约${fresh.accuracy.toInt()}米" else ""
                update { copy(emergencyLocating = false,
                    emergencyLocation = "云瞳位置：$coordinates（系统定位$accuracy），定位时间：${java.util.Date(fresh.time)}",
                    emergencyLocationAt = fresh.time) }
                notice("当前位置已更新")
            }
        }
    }
}
