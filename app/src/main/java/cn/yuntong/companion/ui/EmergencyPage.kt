package cn.yuntong.companion.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import cn.yuntong.companion.AppController
import cn.yuntong.companion.data.Preferences
import cn.yuntong.companion.data.Record
import cn.yuntong.companion.domain.SOS_CANCEL_SECONDS

@Composable
internal fun EmergencyPage(c: AppController, records: List<Record>, p: Preferences, open: (String) -> Unit) {
    val context = LocalContext.current
    val s by c.state.collectAsState()
    val contacts = records.filter { it.kind == "联系人" }
    var pendingContact by remember { mutableStateOf<Record?>(null) }
    var previewBody by remember { mutableStateOf("") }
    var confirmDirectSms by remember { mutableStateOf(false) }
    var confirmTestCall by remember { mutableStateOf(false) }
    var testCallContact by remember { mutableStateOf<Record?>(null) }
    val directSmsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingContact != null) {
            previewBody = c.manualEmergencyMessage()
            if (c.manualSmsApproved(pendingContact!!.id, pendingContact!!.content))
                c.sendManualEmergencySms(pendingContact!!.id, pendingContact!!.content, previewBody)
            else confirmDirectSms = true
        } else c.notice("短信权限未允许，可改用拨号或短信应用")
    }
    val voicePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.CALL_PHONE] == true && grants[Manifest.permission.RECORD_AUDIO] == true) {
            val contact = contacts.firstOrNull { it.id == p.emergencyContactId }
            if (contact != null) c.armVoiceGuard(contact.id, contact.content)
            else c.notice("请先设定紧急联系人")
        } else c.notice("语音守护需要电话和麦克风权限，现未开启")
    }
    val practicePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) c.startVoicePractice()
        else c.notice("未允许麦克风权限，无法开始口令练习")
    }
    val callPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && testCallContact != null) confirmTestCall = true
        else c.notice("电话权限未允许，不能自动拨号")
    }
    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        c.bool("autoFallSms", granted)
        c.notice(if (granted) "跌倒确认后的自动短信已开启" else "短信权限未授权，自动短信保持关闭")
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) c.refreshEmergencyLocation()
        else c.notice("定位权限未允许，仍可拨号或发送不含位置的短信")
    }
    FlowTitle("紧急求助", "出行前可开启语音守护；也可拨号或直接发短信。")
    Section("求助位置", s.emergencyLocation) {
        Action("获取当前位置", Icons.Outlined.MyLocation, enabled = !s.emergencyLocating) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                c.refreshEmergencyLocation()
            else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        Text("短信仅附上最近一分钟的位置；获取失败时仍可直接求助。")
    }
    if (contacts.isEmpty()) {
        Text("还没有保存联系人。")
        Action("添加联系人", Icons.Outlined.Contacts, primary = true) { open("联系人") }
    }
    contacts.forEach { contact ->
        Section(contact.title, contact.content) {
            Action(if (p.emergencyContactId == contact.id) "紧急联系人（当前）" else "设为紧急联系人", Icons.Outlined.Contacts) {
                if (s.voiceGuardActive && p.emergencyContactId != contact.id) c.stopVoiceGuard()
                c.text("emergencyContactId", contact.id)
            }
            Action("给${contact.title}拨号", Icons.Outlined.Call, primary = true) {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", contact.content, null)))
                    c.notice("已打开拨号界面，请确认拨出")
                }.onFailure { c.notice("此设备没有拨号应用") }
            }
            Action("给${contact.title}直接发求助短信", Icons.Outlined.Sms) {
                pendingContact = contact
                previewBody = c.manualEmergencyMessage()
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED)
                    if (c.manualSmsApproved(contact.id, contact.content))
                        c.sendManualEmergencySms(contact.id, contact.content, previewBody)
                    else confirmDirectSms = true
                else directSmsPermission.launch(Manifest.permission.SEND_SMS)
            }
            Action("用短信应用编辑求助短信", Icons.Outlined.Sms) {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", contact.content, null))
                            .putExtra("sms_body", c.manualEmergencyMessage())
                    )
                    c.notice("已打开短信界面，请确认发送")
                }.onFailure { c.notice("此设备没有短信应用") }
            }
        }
    }
    Section("出行语音求助守护", s.voiceGuardStatus) {
        if (s.voiceCountdownSeconds > 0)
            Text("${s.voiceCountdownLabel}：${s.voiceCountdownSeconds} 秒", style = MaterialTheme.typography.headlineMedium)
        Text("练习时分两次说“紧急求助”，两次之间稍作停顿。第一遍提示重复确认，第二遍进入 ${SOS_CANCEL_SECONDS} 秒取消倒计时。练习只在本机识别和显示文字，不会拨号或发短信。")
        if (s.voicePractice || (!s.voiceGuardActive && s.voicePracticeDiagnostic.isNotBlank()))
            Text("本机练习诊断：${s.voicePracticeDiagnostic}")
        if (s.voiceGuardActive) {
            Action(if (s.voicePractice) "结束口令练习" else "关闭语音守护", Icons.Outlined.MicOff) { c.stopVoiceGuard() }
        } else {
            Action("练习求助口令（不会拨号或发短信）", Icons.Outlined.Mic) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                    c.startVoicePractice()
                else practicePermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        Text("出行前主动开启。10 秒内分两次说“紧急求助”，每遍说完稍作停顿。确认后有 ${SOS_CANCEL_SECONDS} 秒可以说“取消求助”或点通知取消。到时会请求拨打紧急联系人并尝试发短信。守护最长约 1 小时。")
        if (!s.voiceGuardActive) Action("开启正式语音求助", Icons.Outlined.Mic, primary = true) {
            if (p.emergencyContactId.isBlank()) c.notice("请先设定紧急联系人")
            else voicePermission.launch(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.RECORD_AUDIO, Manifest.permission.SEND_SMS))
        }
        Action("测试对紧急联系人的自动拨号", Icons.Outlined.Call) {
            val contact = contacts.firstOrNull { it.id == p.emergencyContactId }
            if (contact == null) c.notice("请先设定紧急联系人")
            else {
                testCallContact = contact
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED)
                    confirmTestCall = true
                else callPermission.launch(Manifest.permission.CALL_PHONE)
            }
        }
        Text("测试拨号会真实呼出，请使用能接电话的测试号码并自行挂断。开始前检查联系人、电话卡和麦克风。语音可能漏听或误识别，紧急时仍可直接拨号。")
    }
    Section("电话状态", s.callStatus) { }
    Section("短信状态", s.smsStatus) { }
    if (confirmTestCall) testCallContact?.let { contact ->
        AlertDialog(
            onDismissRequest = { confirmTestCall = false },
            title = { Text("现在拨打测试电话？") },
            text = { Text("将通过系统电话服务直接拨打 ${contact.title} ${contact.content}，这是一次真实呼叫。请确认号码属于你的测试联系人，并在测试后挂断。") },
            confirmButton = { TextButton(onClick = {
                if (p.emergencyContactId == contact.id) c.placeEmergencyCall(contact.id, contact.content)
                else c.notice("紧急联系人已变化，请重新核对号码")
                confirmTestCall = false
            }) { Text("确认拨打") } },
            dismissButton = { TextButton(onClick = { confirmTestCall = false }) { Text("取消") } },
        )
    }
    if (confirmDirectSms) pendingContact?.let { contact ->
        AlertDialog(
            onDismissRequest = { confirmDirectSms = false },
            title = { Text("直接发送求助短信？") },
            text = {
                Column {
                    Text("收件人：${contact.title} ${contact.content}")
                    Text(previewBody)
                    Text("首次核对后由手机直接发送。此后对同一联系人和号码点击“直接发求助短信”将一按即发；号码变化会重新要求核对。长短信可能分段计费。")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val current = c.manualEmergencyMessage()
                    if (current != previewBody) {
                        previewBody = current
                        c.notice("位置已变化，请重新核对短信内容")
                    } else if (c.sendManualEmergencySms(contact.id, contact.content, previewBody)) {
                        confirmDirectSms = false
                        pendingContact = null
                    }
                }) { Text("直接发送") }
            },
            dismissButton = { TextButton(onClick = { confirmDirectSms = false }) { Text("取消") } },
        )
    }
    Section("跌倒确认后自动短信", "需连接眼镜，由板端确认跌倒后触发。") {
        Toggle("允许自动发送给紧急联系人", "授权后才开启，发送结果会单独提示", p.autoFallSms) { enabled ->
            if (!enabled) c.bool("autoFallSms", false)
            else if (p.emergencyContactId.isBlank()) c.notice("请先设定紧急联系人")
            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED)
                c.bool("autoFallSms", true)
            else smsPermission.launch(Manifest.permission.SEND_SMS)
        }
    }
}
