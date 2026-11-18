package cn.yuntong.companion.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cn.yuntong.companion.*
import cn.yuntong.companion.recognition.Labels

@Composable
internal fun AssistantScreen(c: AppController, s: ScreenState, open: (String) -> Unit) {
    var prompt by rememberSaveable { mutableStateOf("请描述当前场景") }
    var target by rememberSaveable { mutableStateOf("") }
    var picking by rememberSaveable { mutableStateOf(false) }
    var upload by remember { mutableStateOf<Pair<String, String>?>(null) }
    val task = s.assistantTask
    val back = { c.chooseAssistant("") }
    BackHandler(task.isNotBlank() && s.pendingCloud == null, back)
    if (s.pendingCloud != null) {
        Section("设备请求识别", "仅在确认后上传关联画面") {
            Text(s.pendingCloud.optString("question"))
            Text("画面来源：${s.frame?.source ?: "等待画面"}")
            Action("检查并确认上传", Icons.Outlined.CloudUpload, primary = true) {
                prompt = s.pendingCloud.optString("question", "请描述画面")
                upload = (s.frame?.id.orEmpty()) to s.pendingCloud.optString("requestId")
            }
            Action("拒绝本次请求", Icons.Outlined.Close, onClick = c::cancelRecognition)
        }
    }
    if (upload != null) {
        val current =
            s.frame?.id == upload!!.first &&
                (s.pendingCloud?.optString("requestId") ?: "") == upload!!.second
        AlertDialog(
            onDismissRequest = { upload = null },
            title = { Text("上传当前画面") },
            text = {
                Text(if (current) "将向已配置的 HTTPS 服务发送这一张画面和问题：$prompt" else "画面或请求已变化，请返回重新确认。")
            },
            confirmButton = {
                TextButton(
                    enabled = current,
                    onClick = {
                        upload = null
                        c.recognize("cloud", prompt)
                    },
                ) {
                    Text("确认上传")
                }
            },
            dismissButton = { TextButton(onClick = { upload = null }) { Text("取消") } },
        )
    }
    if (task.isBlank()) {
        FlowTitle("需要我帮你看什么？", "读文字、找物品，也可以问问眼前的场景。")
        FlowRow("读文字", "本地中文识字 · 可分段朗读", Icons.Outlined.TextFields) { c.chooseAssistant("ocr") }
        FlowRow("找物品", "选择支持的物品，在当前画面中查找", Icons.Outlined.Search) { c.chooseAssistant("find") }
        FlowRow("场景问答", "描述画面或提出问题 · 确认后联网", Icons.Outlined.AutoAwesome) {
            c.chooseAssistant("cloud")
        }
        SpeechControls(c)
        return
    }
    FlowBack("返回助手功能", back)
    val title =
        when (task) {
            "ocr" -> "读文字"
            "find" -> "找物品"
            else -> "场景问答"
        }
    if (s.result.isNotBlank() && s.resultKind == task && s.resultFrameId == s.frame?.id) {
        FlowTitle("识别结果", "${s.frame?.source} · 对应本次定格画面")
        Text(s.result, style = MaterialTheme.typography.bodyLarge)
        SpeechControls(c, s.result, "助手 · $title")
        Action("收藏结果", Icons.Outlined.BookmarkBorder) {
            c.save("收藏", s.result.take(30), s.result, s.frame?.source ?: "手机")
            c.notice("已加入资料收藏")
        }
        FlowRow("重新取景", "换一张画面再识别", Icons.Outlined.AddAPhoto) {
            c.resetRecognitionResult()
            open("助手取图")
        }
        TextButton(onClick = c::resetRecognitionResult, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("用当前画面重新识别")
        }
        return
    }
    FlowTitle(
        title,
        when (task) {
            "ocr" -> "选好画面后，一次读清眼前的文字。"
            "find" -> "画面左右仅指图像位置，不代表行走方向。"
            else -> "每次只上传你确认的这一张画面。"
        },
    )
    if (s.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("正在识别所选画面", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Action("取消识别", Icons.Outlined.Close, onClick = c::cancelRecognitionByUser)
        return
    }
    s.frame?.let {
        Image(
            it.bitmap.asImageBitmap(),
            "${it.source}的待识别画面",
            Modifier.fillMaxWidth().heightIn(max = 200.dp),
        )
        Text("画面来源：${it.source}", style = MaterialTheme.typography.labelLarge)
    }
    Action(
        if (s.frame == null) "拍照或选择图片" else "更换画面",
        Icons.Outlined.AddAPhoto,
        primary = s.frame == null,
    ) {
        open("助手取图")
    }
    if (task == "find") {
        FlowRow(
            "查找目标：${if(target.isBlank()) "全部支持类别" else Labels.chinese(target)}",
            "点击选择或搜索支持的物品",
            Icons.Outlined.Category,
        ) {
            picking = true
        }
    }
    if (task == "cloud") {
        OutlinedTextField(
            prompt,
            { prompt = it },
            Modifier.fillMaxWidth(),
            label = { Text("想了解画面的什么信息") },
        )
        if (c.secrets.get("apiBase").isBlank() || c.secrets.get("apiKey").isBlank()) {
            FlowRow("配置联网服务", "首次使用需要配置服务地址与密钥", Icons.Outlined.Cloud) { open("联网识别服务") }
        }
    }
    Action(
        when (task) {
            "ocr" -> "识别中文文字"
            "find" -> "开始查找"
            else -> "确认画面并提问"
        },
        Icons.Outlined.PlayArrow,
        enabled = s.frame != null && (task != "cloud" || prompt.isNotBlank()),
        primary = true,
    ) {
        if (task == "cloud") upload = s.frame!!.id to "" else c.recognize(task, target = target)
    }
    if (picking)
        TargetPicker(
            target,
            {
                target = it
                picking = false
            },
            { picking = false },
        )
}

@Composable
private fun TargetPicker(current: String, select: (String) -> Unit, close: () -> Unit) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("选择支持的物品") },
        text = {
            Column {
                OutlinedTextField(
                    query,
                    { query = it },
                    label = { Text("搜索物品类别") },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 350.dp)) {
                    item {
                        TextButton(
                            onClick = { select("") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text("全部支持类别")
                        }
                    }
                    items(
                        Labels.options
                            .filter { it.value.contains(query) || it.key.contains(query, true) }
                            .toList()
                    ) { (id, name) ->
                        TextButton(
                            onClick = { select(id) },
                            modifier =
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                                    selected = id == current
                                },
                        ) {
                            Text(name)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("关闭") } },
    )
}

@Composable
internal fun AssistantCapture(c: AppController, s: ScreenState, done: () -> Unit) {
    val context = LocalContext.current
    var camera by remember { mutableStateOf(false) }
    var glassesRequestBefore by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.frame?.id, glassesRequestBefore) {
        if (glassesRequestBefore != null && s.frame?.id != glassesRequestBefore &&
            s.frame?.source == "眼镜新画面") {
            glassesRequestBefore = null
            c.stream(false)
            done()
        }
    }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { c.importImage(it, done) }
        }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            camera = granted
            if (!granted) c.notice("相机权限未允许，仍可选择本地图片")
        }
    if (camera)
        CameraCapture(c, captured = done, textGuidance = s.assistantTask == "ocr") {
            camera = false
        }
    else {
        FlowTitle("选取画面", "画面确认后，回到刚才的识别任务。")
        Action("打开相机", Icons.Outlined.CameraAlt, primary = true) {
            c.stream(false)
            if (
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
            )
                camera = true
            else permission.launch(Manifest.permission.CAMERA)
        }
        Action("选择本地图片", Icons.Outlined.Image) { picker.launch("image/*") }
        if (s.peer == cn.yuntong.companion.transport.DevicePeer.RDK)
            Action("拍摄眼镜新画面", Icons.Outlined.Videocam) {
                glassesRequestBefore = s.frame?.id ?: ""
                c.captureFromGlasses()
            }
        if (s.frame != null) {
            FramePreview(s)
            Action("使用当前画面", Icons.Outlined.Check) {
                c.stream(false)
                done()
            }
        }
    }
}
