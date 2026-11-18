package cn.yuntong.companion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yuntong.companion.AppController

@Composable
internal fun FlowTitle(title: String, subtitle: String) {
    Column(
        Modifier.semantics {
            paneTitle = title
            isTraversalGroup = true
        },
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            title,
            fontSize = 27.sp,
            lineHeight = 37.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun FlowRow(
    title: String,
    subtitle: String,
    icon: ImageVector = Icons.Outlined.ChevronRight,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(17.dp).heightIn(min = 58.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(27.dp), tint = MaterialTheme.colorScheme.primary)
            Column(
                Modifier.weight(1f).padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun SpeechControls(c: AppController, text: String? = null, source: String = "本机朗读") {
    val context = androidx.compose.ui.platform.LocalContext.current
    val speech by c.speech.state.collectAsStateWithLifecycle()
    val own =
        speech.source == source &&
            speech.content == text &&
            speech.phase in setOf("speaking", "paused")
    if (text != null && !own)
        Action("朗读内容", Icons.Outlined.VolumeUp, primary = true) { c.speak(text, source = source) }
    if (speech.phase != "idle") {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "${speech.source} · ${when(speech.phase){"speaking"->"正在朗读"
"paused"->"朗读已暂停"
"completed"->"朗读已结束"
else->"语音暂不可用"}}",
                    fontWeight = FontWeight.Medium,
                )
                if (speech.phase == "error") {
                    Text(speech.error, style = MaterialTheme.typography.bodySmall)
                    Action("打开系统语音设置", Icons.Outlined.SettingsVoice) {
                        runCatching {
                                context.startActivity(
                                    android.content.Intent("com.android.settings.TTS_SETTINGS")
                                )
                            }
                            .onFailure { c.notice("无法打开语音设置，请从系统设置搜索文字转语音。") }
                    }
                }
                if (speech.phase in setOf("speaking", "paused")) {
                    Text(
                        "第 ${speech.part}/${speech.total} 段 · 暂停朗读不会暂停导航",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (speech.phase == "speaking")
                        Action("暂停朗读", Icons.Outlined.Pause) { c.speech.pause() }
                    else Action("继续朗读", Icons.Outlined.PlayArrow) { c.speech.resume() }
                    Action("停止朗读", Icons.Outlined.Stop) { c.speech.stop() }
                } else TextButton(onClick = { c.speech.stop() }) { Text("关闭语音状态") }
            }
        }
    }
}

@Composable
internal fun FlowBack(label: String, action: () -> Unit) {
    TextButton(onClick = action, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(Icons.Outlined.ArrowBack, null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
internal fun TransientNotice(message: String, screen: String, spoken: Boolean = false) {
    var previous by remember(screen) { mutableStateOf(message) }
    var visible by remember(screen) { mutableStateOf(false) }
    LaunchedEffect(message, screen) {
        if (message != previous) {
            previous = message
            val represented =
                message in
                    setOf(
                        "欢迎使用云瞳",
                        "已连接电脑测试接收端",
                        "设备已断开",
                        "设备已断开；协同提示已暂停",
                        "导航已暂停",
                        "导航已恢复",
                        "步行导航已开始",
                        "本次导航已结束",
                        "路线已更新，可按文字步骤预习",
                    ) || message.startsWith("模拟步骤") || message.startsWith("请核对目的地：")
            visible = message.isNotBlank() && !represented
        }
    }
    if (visible)
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text(
                    message,
                    Modifier.semantics { if (!spoken) liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(
                    onClick = { visible = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("知道了")
                }
            }
        }
}
