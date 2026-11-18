package cn.yuntong.companion.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import cn.yuntong.companion.AppController
import cn.yuntong.companion.ScreenState
import cn.yuntong.companion.data.Record
import cn.yuntong.companion.recognition.CameraGuide
import java.util.concurrent.Executors
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun FramesPage(c: AppController, s: ScreenState, photoReading: Boolean = false) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(Unit) {
        onDispose { c.stream(false) }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) c.stream(false)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var camera by remember { mutableStateOf(false) }
    var video by remember { mutableStateOf<Uri?>(null) }
    var pendingPhoneVideo by rememberSaveable { mutableStateOf("") }
    var overlays by remember { mutableStateOf(true) }
    val phoneVideo = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val file = pendingPhoneVideo.takeIf { it.isNotBlank() }?.let(::File)
        pendingPhoneVideo = ""
        if (file != null) {
            if (result.resultCode == Activity.RESULT_OK) c.savePhoneRecording(file)
            else {
                file.delete()
                c.notice("已取消手机录像")
            }
        }
    }
    fun startPhoneVideo() {
        val file = File(context.filesDir, "recordings").apply { mkdirs() }
            .resolve("${UUID.randomUUID()}.mp4")
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            pendingPhoneVideo = file.absolutePath
            phoneVideo.launch(Intent(MediaStore.ACTION_VIDEO_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .apply {
                    clipData = ClipData.newUri(context.contentResolver, "手机录像", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                })
        }.onFailure {
            pendingPhoneVideo = ""
            file.delete()
            c.notice("无法打开系统录像应用")
        }
    }
    val phoneVideoPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startPhoneVideo()
        else c.notice("相机权限未允许，仍可选择本地视频")
    }
    val imagePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
            it?.let { uri ->
                camera = false
                video = null
                c.importImage(uri)
            }
        }
    val videoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
            it?.let { uri ->
                camera = false
                c.stream(false)
                video = uri
            }
        }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            camera = granted
            if (!granted) c.notice("相机权限未允许，仍可选择本地图片")
        }
    LaunchedEffect(photoReading) {
        if (photoReading) {
            c.stream(false)
            if (
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
            )
                camera = true
            else permission.launch(Manifest.permission.CAMERA)
        }
    }
    if (camera && photoReading)
        CameraCapture(c, captured = { c.recognize("ocr") }, textGuidance = true) { camera = false }
    Section("图像来源", "预览画面默认仅在内存中；主动保存画面或录像才会写入本机。") {
        Action("使用手机相机", Icons.Outlined.CameraAlt, primary = true) {
            video = null
            c.stream(false)
            if (
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
            )
                camera = true
            else permission.launch(Manifest.permission.CAMERA)
        }
        Action("选择本地图片", Icons.Outlined.Image) { imagePicker.launch("image/*") }
        Action("选择本地视频回放", Icons.Outlined.VideoLibrary) { videoPicker.launch("video/*") }
        Action("使用手机录像", Icons.Outlined.FiberManualRecord) {
            camera = false
            c.stream(false)
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                startPhoneVideo()
            else phoneVideoPermission.launch(Manifest.permission.CAMERA)
        }
        if (s.peer == cn.yuntong.companion.transport.DevicePeer.RDK)
            Action("拍摄眼镜新画面", Icons.Outlined.PhotoCamera) {
                camera = false
                video = null
                c.captureFromGlasses()
            }
        Action("继续接收设备图传", Icons.Outlined.Videocam, enabled = s.peer != null) {
            camera = false
            video = null
            c.stream(true)
        }
        Action("暂停接收并保留当前画面", Icons.Outlined.Pause) { c.stream(false) }
    }
    if (camera && !photoReading) CameraCapture(c) { camera = false }
    video?.let { VideoPlayback(it, c) }
    Section("当前画面") {
        FramePreview(s)
        Action("保存当前画面", Icons.Outlined.Save, enabled = s.frame != null, onClick = c::saveFrame)
        Action("识别当前画面文字", Icons.Outlined.TextFields, enabled = s.frame != null && !s.busy) {
            c.recognize("ocr")
        }
        if (s.result.isNotBlank()) {
            Text(s.result)
            SpeechControls(c, s.result, "助手 · 读文字")
        }
    }
    if (s.peer == cn.yuntong.companion.transport.DevicePeer.RDK) {
        Section("眼镜录像", s.recordingStatus) {
            Action("开始录制", Icons.Outlined.FiberManualRecord,
                enabled = s.recordingStatus !in setOf("正在录制眼镜画面", "正在保存录像")) { c.startRecording() }
            Action("停止并保存", Icons.Outlined.Stop,
                enabled = s.recordingStatus == "正在录制眼镜画面") { c.stopRecording() }
        }
    }
}

@Composable
internal fun GlassesRecordingsPage(c: AppController, records: List<Record>) {
    var playing by remember { mutableStateOf<Uri?>(null) }
    var deleting by remember { mutableStateOf<Record?>(null) }
    val recordings = records.filter { it.kind == "录像" }
    FlowTitle("本机录像", "手机或眼镜画面录制后，都可在这里回放。")
    if (recordings.isEmpty()) Text("还没有保存录像。可在查看画面页使用手机录像。")
    recordings.forEach { record ->
        Section(record.title, record.source) {
            Action("播放录像", Icons.Outlined.PlayArrow) {
                val file = File(record.content)
                if (file.isFile) playing = Uri.fromFile(file)
                else c.notice("录像文件已不存在")
            }
            Action("删除录像", Icons.Outlined.DeleteOutline) {
                deleting = record
            }
        }
    }
    deleting?.let { record ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text("删除这段录像？") },
            text = { Text("删除后无法从本机录像中恢复。") },
            confirmButton = { TextButton(onClick = {
                if (playing?.path == record.content) playing = null
                c.delete(record.id)
                deleting = null
            }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
    playing?.let { VideoPlayback(it, c) }
}

@Composable
internal fun CameraCapture(
    c: AppController,
    captured: () -> Unit = {},
    textGuidance: Boolean = false,
    close: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val preview = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }
    val capture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    var ready by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    val mounted = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(Unit) { onDispose { mounted.set(false) } }
    var enabled by rememberSaveable { mutableStateOf(textGuidance) }
    var hint by remember { mutableStateOf("正在检查取景。请将镜头朝向文字并保持稳定。") }
    val latestCaptured by rememberUpdatedState(captured)
    val latestClose by rememberUpdatedState(close)
    DisposableEffect(lifecycle, enabled) {
        var active = true
        ready = false
        var provider: ProcessCameraProvider? = null
        val worker = Executors.newSingleThreadExecutor()
        val guide = CameraGuide { value, shouldAnnounce ->
            if (
                active &&
                    enabled &&
                    lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) {
                if (shouldAnnounce || value.code == "error") hint = value.text
                if (shouldAnnounce && !capturing)
                    c.speak(value.text, automatic = true, source = "取景引导")
            }
        }
        val analysis =
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
        if (enabled) analysis.setAnalyzer(worker, guide)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && c.speech.state.value.source == "取景引导")
                c.speech.stop()
        }
        lifecycle.lifecycle.addObserver(observer)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                if (active)
                    runCatching {
                            provider = future.get()
                            val p =
                                Preview.Builder().build().also {
                                    it.setSurfaceProvider(preview.surfaceProvider)
                                }
                            val cases =
                                if (enabled) arrayOf<UseCase>(p, capture, analysis)
                                else arrayOf<UseCase>(p, capture)
                            provider?.bindToLifecycle(
                                lifecycle,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                *cases,
                            )
                            ready = true
                        }
                        .onFailure { c.notice("相机无法打开：${it.message}") }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            active = false
            analysis.clearAnalyzer()
            provider?.unbind(capture, analysis)
            provider?.unbindAll()
            guide.close()
            worker.shutdown()
            lifecycle.lifecycle.removeObserver(observer)
            if (c.speech.state.value.source == "取景引导") c.speech.stop()
        }
    }
    Section("手机取景", "镜头对准文字或物品，保持稳定后拍照。") {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("文字取景引导", Modifier.weight(1f))
            Switch(enabled, { enabled = it }, Modifier.semantics { contentDescription = "文字取景引导" })
        }
        if (enabled) {
            Text(hint, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Action("重复取景提示", Icons.Outlined.VolumeUp) { c.speak(hint, source = "取景引导") }
            Text(
                "根据当前画面的亮度和检测到的文字给出提示，不保证整页完整或清晰。引导只在本机处理，不上传画面。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Action(
            if (capturing) "正在拍照" else "拍照并选用画面",
            Icons.Outlined.CameraAlt,
            enabled = ready && !capturing,
            primary = true,
        ) {
            capturing = true
            if (c.speech.state.value.source == "取景引导") c.speech.stop()
            capture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            if (
                                !mounted.get() ||
                                    !lifecycle.lifecycle.currentState.isAtLeast(
                                        Lifecycle.State.RESUMED
                                    )
                            )
                                return
                            val source = image.toBitmap()
                            val angle = image.imageInfo.rotationDegrees.toFloat()
                            val bitmap =
                                if (angle == 0f) source
                                else
                                    Bitmap.createBitmap(
                                        source,
                                        0,
                                        0,
                                        source.width,
                                        source.height,
                                        Matrix().apply { postRotate(angle) },
                                        true,
                                    )
                            c.setImage(bitmap, "手机拍照")
                            c.notice("拍照完成，已选用本次画面。")
                            latestClose()
                            latestCaptured()
                        } catch (e: Exception) {
                            c.notice("拍照失败：${e.message}")
                        } finally {
                            capturing = false
                            image.close()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        capturing = false
                        c.notice("拍照失败，请重试")
                    }
                },
            )
        }
        Action("关闭相机", Icons.Outlined.Close, onClick = close)
        Box(Modifier.fillMaxWidth().height(280.dp).clipToBounds()) {
            AndroidView({ preview }, Modifier.matchParentSize())
        }
    }
}

@Composable
internal fun VideoPlayback(uri: Uri, c: AppController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player =
        remember(uri) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
            }
        }
    var playing by remember(uri) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                player.pause()
                playing = false
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            player.release()
        }
    }
    Section("本地视频回放", "暂停后可提取当前时间附近的一帧进行识字或找物。") {
        AndroidView(
            {
                PlayerView(context).apply {
                    this.player = player
                    useController = true
                }
            },
            Modifier.fillMaxWidth().height(260.dp),
            update = { it.player = player },
        )
        Action(
            if (playing) "暂停视频" else "播放视频",
            if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
        ) {
            if (playing) player.pause() else player.play()
            playing = !playing
        }
        Action("后退10秒", Icons.Outlined.Replay10) {
            player.seekTo((player.currentPosition - 10000).coerceAtLeast(0))
        }
        Action("前进10秒", Icons.Outlined.Forward10) {
            player.seekTo(
                (player.currentPosition + 10000).coerceAtMost(player.duration.coerceAtLeast(0))
            )
        }
        Action("提取当前画面", Icons.Outlined.PhotoCamera) {
            player.pause()
            playing = false
            val time = player.currentPosition
            scope.launch {
                runCatching {
                        withContext(Dispatchers.IO) {
                            val r = MediaMetadataRetriever()
                            try {
                                r.setDataSource(context, uri)
                                r.getFrameAtTime(time * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                                    ?: error("没有可用帧")
                            } finally {
                                r.release()
                            }
                        }
                    }
                    .onSuccess { c.setImage(it, "本地视频 ${time/1000}秒") }
                    .onFailure { c.notice("视频帧无法提取，请选择其他时间") }
            }
        }
    }
}
