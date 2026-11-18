package cn.yuntong.companion.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import cn.yuntong.companion.*
import cn.yuntong.companion.domain.ConnectionProfile
import cn.yuntong.companion.transport.DevicePeer
import kotlinx.coroutines.*

@Composable
internal fun DeviceScreen(c: AppController, s: ScreenState, open: (String) -> Unit) {
    val address = c.secrets.get("deviceAddress")
    val name = c.secrets.get("deviceName").ifBlank { "电脑测试端" }
    val connected = s.peer != null
    FlowTitle("我的设备", "连接、准备和操作回执分别查看。")
    Section(if (address.isBlank()) "添加连接设备" else name, "云瞳眼镜与电脑演练使用独立连接") {
        Text(s.connection, style = MaterialTheme.typography.headlineSmall)
        if (address.isBlank()) {
            Text("首次填写设备地址和连接密钥，保存后可一键重连。")
            Action("设置连接", Icons.Outlined.AddLink, primary = true) { open("连接设置") }
        } else {
            Action(if (connected) "重新连接" else "连接已保存设备", Icons.Outlined.Link, primary = true) {
                c.connect(address, c.secrets.get("deviceToken"))
            }
            if (connected || s.connection != "未连接")
                Action("断开连接", Icons.Outlined.LinkOff, onClick = c::disconnect)
        }
    }
    FlowRow("佩戴与准备", "自检、正前方确认、语音和振动测试", Icons.Outlined.AccessibilityNew) { open("佩戴与准备") }
    Section("设备状态") {
        Text("设备：${s.device}")
        Text("感知：${s.perception}")
        Text("最近操作：${s.delivery}")
        val available = connected && s.deviceCommand == null
        Action("查询设备状态", Icons.Outlined.Refresh, enabled = available) { c.deviceAction("status") }
        if (s.peer == DevicePeer.RDK) {
            Action("朗读眼镜状态", Icons.Outlined.VolumeUp) {
                c.speak("${s.device}。${s.perception}", source = "眼镜状态")
            }
        }
        if (s.peer == DevicePeer.TEST && s.navigation.phase !in setOf("active", "paused"))
            Text("测试端的操作查询需要先开始导航或路线演练。", style = MaterialTheme.typography.bodySmall)
    }
    FlowRow("连接设置", if (address.isBlank()) "首次配置" else "修改设备名称、地址或密钥", Icons.Outlined.Settings) {
        open("连接设置")
    }
    SpeechControls(c)
}

@Composable
internal fun ConnectionSettings(c: AppController, done: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf(c.secrets.get("deviceAddress")) }
    var name by rememberSaveable { mutableStateOf(c.secrets.get("deviceName").ifBlank { "电脑测试端" }) }
    // Credentials stay in memory and encrypted storage, not saved instance state.
    var token by remember { mutableStateOf(c.secrets.get("deviceToken")) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null)
                scope.launch {
                    importing = true
                    try {
                        val profile =
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    val bytes = ByteArray(16385)
                                    var size = 0
                                    while (size < bytes.size) {
                                        val n = input.read(bytes, size, bytes.size - size)
                                        if (n < 0) break
                                        size += n
                                    }
                                    require(size <= 16384) { "连接文件过大，请选择云瞳连接文件" }
                                    ConnectionProfile.parse(String(bytes, 0, size, Charsets.UTF_8))
                                } ?: error("无法打开连接文件")
                            }
                        name = profile.name
                        address = profile.address
                        token = profile.token
                        c.notice("已导入${profile.name}。请核对名称和地址，再保存连接设置。尚未连接。")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        c.notice("连接文件无法导入，请确认是有效的云瞳连接文件。")
                    } finally {
                        importing = false
                    }
                }
        }
    FlowTitle("连接设置", "配置一次，之后可从设备页直接连接。")
    Action(if (importing) "正在导入" else "导入连接文件", Icons.Outlined.FileOpen, enabled = !importing) {
        picker.launch("application/json")
    }
    Text(
        "可导入眼镜或电脑演练端生成的连接文件。核对设备名称和地址后再保存。文件含密钥，请只使用可信设备提供的文件。",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
        name,
        { name = it },
        Modifier.fillMaxWidth(),
        label = { Text("设备名称") },
        singleLine = true,
    )
    OutlinedTextField(
        address,
        { address = it },
        Modifier.fillMaxWidth(),
        label = { Text("连接地址") },
        placeholder = { Text("ws://眼镜IP:8767/rdk") },
        singleLine = true,
    )
    OutlinedTextField(
        token,
        { token = it },
        Modifier.fillMaxWidth(),
        label = { Text("连接密钥") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
    )
    Text(
        "请将手机与眼镜接入同一可信局域网。眼镜地址以 /rdk 结尾，电脑演练地址以 /link 结尾。",
        style = MaterialTheme.typography.bodyMedium,
    )
    Action("保存连接设置", Icons.Outlined.Save, primary = true) {
        if (c.saveConnection(name, address, token)) done()
    }
}

@Composable
internal fun PreparationPage(c: AppController, s: ScreenState) {
    FlowTitle("佩戴与准备", "手动清单不替代设备准备结果。")
    Section("设备准备", s.device) {
        val rdk = s.peer == DevicePeer.RDK
        val enabled = s.deviceCommand == null &&
            (rdk || s.peer == DevicePeer.TEST && s.navigation.phase in setOf("active", "paused"))
        if (!enabled && !rdk) Text("请连接眼镜，或连接电脑测试端并开始路线演练。")
        Action("请求设备自检", Icons.Outlined.FactCheck, enabled = enabled) {
            c.deviceAction("self_check")
        }
        if (rdk) {
            Text("静默观察用于台架联调。启动后可做静置与前向确认。")
            Action("启动眼镜静默观察", Icons.Outlined.PlayArrow, enabled = enabled) {
                c.deviceAction("start")
            }
            Action("停止眼镜观察", Icons.Outlined.Stop, enabled = enabled) {
                c.deviceAction("stop")
            }
        }
        Action("开始静置准备", Icons.Outlined.AccessibilityNew, enabled = enabled) {
            c.deviceAction("prepare")
        }
        Action("确认眼镜正前方", Icons.Outlined.CenterFocusStrong, enabled = enabled) {
            c.deviceAction("front.confirm")
        }
        Text("操作回执：${s.delivery}")
    }
    Section("手机反馈测试") {
        Action("测试手机语音", Icons.Outlined.VolumeUp) {
            c.speak("云瞳语音测试。请确认能清楚听见这句话。", source = "手机语音测试")
        }
        Action("测试手机振动", Icons.Outlined.Vibration, onClick = c::vibrate)
        SpeechControls(c)
    }
}
