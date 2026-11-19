package cn.yuntong.companion.ui

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import cn.yuntong.companion.*
import cn.yuntong.companion.data.*
import cn.yuntong.companion.domain.homeGuidance
import java.time.LocalDate
import kotlinx.coroutines.delay

@Composable
internal fun HomeScreen(
    c: AppController,
    s: ScreenState,
    p: Preferences,
    records: List<Record>,
    modifier: Modifier = Modifier,
    open: (String) -> Unit,
    navigate: () -> Unit,
    stop: () -> Unit,
) {
    val active = s.navigation.phase in setOf("active", "paused")
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { reply ->
        val phrase = reply.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        when {
            "求助" in phrase -> open("紧急求助")
            "读字" in phrase || "读文字" in phrase -> {
                if (s.peer == cn.yuntong.companion.transport.DevicePeer.RDK) {
                    c.captureFromGlasses("ocr")
                    open("图像与回放")
                } else open("拍照识字")
            }
            "描述场景" in phrase || "场景问答" in phrase -> {
                c.chooseAssistant("cloud")
                if (s.peer == cn.yuntong.companion.transport.DevicePeer.RDK) c.captureFromGlasses()
                open("场景问答")
            }
            "看前面" in phrase -> {
                if (s.peer == cn.yuntong.companion.transport.DevicePeer.RDK) c.captureFromGlasses()
                open("图像与回放")
            }
            phrase.isNotBlank() -> c.notice("未识别这项操作，可说读文字、描述场景、看前面或求助")
        }
    }
    val snackbar = remember { SnackbarHostState() }
    // Do not replay a notice left by another tab when returning home.
    var previousNotice by remember { mutableStateOf(s.notice) }
    LaunchedEffect(s.notice) {
        val represented =
            s.notice.startsWith("模拟步骤") ||
                s.notice in
                    setOf(
                        "欢迎使用云瞳",
                        "导航已暂停",
                        "导航已恢复",
                        "步行导航已开始",
                        "本次导航已结束",
                        "设备已断开；协同提示已暂停",
                        "已连接电脑测试接收端",
                        "操作已提交，执行状态以回执为准",
                    )
        if (s.notice != previousNotice && !represented && !s.noticeSpoken) {
            previousNotice = s.notice
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(s.notice, withDismissAction = true)
        }
    }
    var modeDialog by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val stack = LocalDensity.current.fontScale > 1.25f || maxWidth < 360.dp
        val scrollActions = stack || maxHeight < 620.dp
        Column(Modifier.fillMaxSize()) {
            if (active && !scrollActions) {
                Column(
                    Modifier.weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                ) {
                    HomeHeader(p, stack) { modeDialog = true }
                    SnackbarHost(snackbar)
                    ActiveHome(s, p, stack, open, navigate)
                    HomeButton("紧急求助", Icons.Outlined.Sos, true, Modifier.fillMaxWidth()) {
                        open("紧急求助")
                    }
                    HomeButton("说出操作", Icons.Outlined.Mic, false, Modifier.fillMaxWidth()) {
                        runCatching {
                            voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN"))
                        }.onFailure { c.notice("手机未安装语音输入服务") }
                    }
                    SpeechControls(c)
                }
                HomeActions(c, s, stack, stop, Modifier.padding(horizontal = 24.dp))
            } else {
                Column(
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                ) {
                    HomeHeader(p, stack) { modeDialog = true }
                    SnackbarHost(snackbar)
                    if (active) {
                        ActiveHome(s, p, stack, open, navigate)
                        HomeButton("紧急求助", Icons.Outlined.Sos, true, Modifier.fillMaxWidth()) {
                            open("紧急求助")
                        }
                        SpeechControls(c)
                        HomeActions(c, s, stack, stop, Modifier.padding(top = 20.dp))
                    } else ReadyHome(c, s, p, records, stack, open, navigate)
                    HomeButton("说出操作", Icons.Outlined.Mic, false, Modifier.fillMaxWidth()) {
                        runCatching {
                            voice.launch(
                                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                            )
                        }.onFailure { c.notice("手机未安装语音输入服务") }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
    if (modeDialog)
        AlertDialog(
            onDismissRequest = { modeDialog = false },
            title = { Text("使用方式") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (active) "请先结束本次导航，再切换使用方式。" else "手机独立使用地图导航；眼镜协同时由设备返回助行提示。")
                    listOf(false to "手机独立使用", true to "眼镜协同使用").forEach { (value, label) ->
                        OutlinedButton(
                            onClick = {
                                c.mode(value)
                                modeDialog = false
                            },
                            enabled = !active,
                            modifier =
                                Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics {
                                    selected = p.cooperative == value
                                },
                        ) {
                            Text(if (p.cooperative == value) "$label（当前）" else label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { modeDialog = false }) { Text("关闭") } },
        )
}

@Composable
private fun HomeHeader(p: Preferences, stack: Boolean, mode: () -> Unit) {
    AdaptivePair(
        stack,
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 28.dp),
        first = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painterResource(cn.yuntong.companion.R.drawable.ic_brand),
                    null,
                    Modifier.size(38.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "云瞳",
                    fontSize = 25.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
            }
        },
        second = {
            FilledTonalButton(
                onClick = mode,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) {
                Text(if (p.cooperative) "眼镜协同" else "手机独立", fontSize = 15.sp)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Outlined.ExpandMore, "切换使用方式", Modifier.size(18.dp))
            }
        },
    )
}

@Composable
private fun ReadyHome(
    c: AppController,
    s: ScreenState,
    p: Preferences,
    records: List<Record>,
    stack: Boolean,
    open: (String) -> Unit,
    navigate: () -> Unit,
) {
    Text(
        "今天想去哪里？",
        fontSize = 30.sp,
        lineHeight = 42.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        "选择目的地，查看步行路线",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 7.dp, bottom = 24.dp),
        fontSize = 16.sp,
    )
    HomeButton("选择目的地", Icons.Outlined.NearMe, true, Modifier.fillMaxWidth(), navigate)
    Spacer(Modifier.height(12.dp))
    val favorites = records.filter { it.kind == "地点" }.distinctBy { it.content }.take(2)
    if (favorites.isEmpty()) {
        HomeButton(
            "添加常用地点",
            Icons.Outlined.AddLocationAlt,
            false,
            Modifier.fillMaxWidth(),
            navigate,
        )
    } else
        AdaptivePair(
            stack,
            first = {
                HomeButton(
                    favorites[0].title,
                    Icons.Outlined.StarOutline,
                    false,
                    Modifier.fillMaxWidth(),
                ) {
                    c.favoriteSelect(favorites[0])
                    navigate()
                }
            },
            second = {
                if (favorites.size > 1)
                    HomeButton(
                        favorites[1].title,
                        Icons.Outlined.StarOutline,
                        false,
                        Modifier.fillMaxWidth(),
                    ) {
                        c.favoriteSelect(favorites[1])
                        navigate()
                    }
                else
                    HomeButton(
                        "更多地点",
                        Icons.Outlined.AddLocationAlt,
                        false,
                        Modifier.fillMaxWidth(),
                        navigate,
                    )
            },
        )
    HomeHeading("出发准备", Modifier.padding(top = 28.dp, bottom = 12.dp))
    var today by remember { mutableStateOf(LocalDate.now().toString()) }
    LaunchedEffect(Unit) {
        while (true) {
            today = LocalDate.now().toString()
            delay(1000)
        }
    }
    val checked = if (p.checkDay == today) todayChecks(p).size else 0
    HomeRow("出发检查", "手动检查 · 已完成 $checked/4 项", Icons.Outlined.FactCheck, true) { open("出发检查") }
    HomeRow(
        if (p.cooperative) "设备与连接" else "连接我的眼镜",
        if (p.cooperative) "${s.connection} · 点此查看准备状态" else "连接后可使用眼镜协同",
        Icons.Outlined.Link,
        false,
    ) {
        open("设备与连接")
    }
    if (s.peer == cn.yuntong.companion.transport.DevicePeer.RDK)
        HomeRow("开始眼镜观察", "开始后自动进行静置准备", Icons.Outlined.PlayArrow, true) {
            c.startGlassesPreparation()
            open("佩戴与准备")
        }
    HomeHeading("常用工具", Modifier.padding(top = 18.dp, bottom = 12.dp))
    HomeRow("紧急求助", "联系已保存的紧急联系人", Icons.Outlined.Sos, true) { open("紧急求助") }
    AdaptivePair(
        stack,
        first = { ToolTile("拍照识字", "读出眼前的文字", Icons.Outlined.TextFields) { open("拍照识字") } },
        second = { ToolTile("查看画面", "相机与设备图像", Icons.Outlined.CameraAlt) { open("图像与回放") } },
    )
}

@Composable
private fun ActiveHome(
    s: ScreenState,
    p: Preferences,
    stack: Boolean,
    open: (String) -> Unit,
    navigate: () -> Unit,
) {
    val n = s.navigation
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "前往 ${n.destination}",
            Modifier.weight(1f),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                if (n.source == "simulation") "模拟演练" else "步行导航",
                Modifier.padding(8.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
    val guidance =
        homeGuidance(
            n,
            p.cooperative,
            s.peer != null,
            s.guidance,
            s.guidanceExpired,
            s.navigationIssue,
                s.peer == cn.yuntong.companion.transport.DevicePeer.RDK,
        )
    Surface(
        modifier = Modifier.padding(top = 18.dp).fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color =
            if (guidance.available) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.primaryContainer,
        contentColor =
            if (guidance.available) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                if (p.cooperative) "当前助行提示" else "当前导航提示",
                fontSize = 15.sp,
                modifier = Modifier.semantics { heading() },
            )
            // Keep the source's wording intact; no inferred direction arrow or synthesized movement
            // instruction.
            Text(
                guidance.text,
                fontSize = if (guidance.text.length <= 12) 34.sp else 26.sp,
                lineHeight = if (guidance.text.length <= 12) 46.sp else 38.sp,
                fontWeight = FontWeight.SemiBold,
            )
            HorizontalDivider(color = LocalContentColor.current.copy(alpha = .25f))
            Text(guidance.detail, fontSize = 14.sp, lineHeight = 21.sp)
        }
    }
    if (p.cooperative && s.peer == null) {
        Spacer(Modifier.height(10.dp))
        HomeButton("重新连接设备", Icons.Outlined.Link, false, Modifier.fillMaxWidth()) { open("设备与连接") }
    }
    Surface(
        onClick = navigate,
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Column(Modifier.padding(vertical = 16.dp)) {
            AdaptivePair(
                stack,
                first = { HomeMetric("剩余距离", n.remainingMeters?.let { "$it 米" } ?: "等待更新") },
                second = {
                    HomeMetric("预计还需", n.remainingSeconds?.let { "${(it + 59) / 60} 分钟" } ?: "等待更新")
                },
            )
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("查看路线详情", color = MaterialTheme.colorScheme.primary, fontSize = 15.sp)
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp))
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    if (p.cooperative) {
        if (stack) {
            HomeStatus(
                "导航",
                if (n.phase == "paused") "已暂停"
                else if (s.navigationIssue.isNotBlank()) "等待恢复" else "进行中",
            )
            HomeStatus("通信", s.connection)
            HomeStatus("感知", s.perception)
        } else
            Row(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    HomeStatus(
                        "导航",
                        if (n.phase == "paused") "已暂停"
                        else if (s.navigationIssue.isNotBlank()) "等待恢复" else "进行中",
                    )
                }
                Column(Modifier.weight(1f)) {
                    HomeStatus("通信", if (s.connection == "已连接测试接收端") "测试端已连接" else s.connection)
                }
                Column(Modifier.weight(1f)) {
                    HomeStatus("感知", if (s.perception.startsWith("模拟感知")) "模拟结果" else s.perception)
                }
            }
        Text(if (s.peer == cn.yuntong.companion.transport.DevicePeer.RDK) s.navigationDelivery else s.delivery,
            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { open("设备与连接") }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("查看设备与接收状态")
        }
    } else
        HomeStatus(
            "导航状态",
            if (n.phase == "paused") "已暂停"
            else if (s.navigationIssue.isNotBlank()) "等待恢复" else "进行中",
        )
    if (s.nearby) Text("已接近目的地，请确认实际入口后结束导航。", Modifier.padding(vertical = 12.dp))
}

@Composable
private fun HomeActions(
    c: AppController,
    s: ScreenState,
    stack: Boolean,
    stop: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 10.dp)
    ) {
        AdaptivePair(
            stack,
            first = {
                HomeButton(
                    "重复提示",
                    Icons.Outlined.VolumeUp,
                    true,
                    Modifier.fillMaxWidth(),
                    c::repeat,
                )
            },
            second = {
                HomeButton(
                    if (s.navigation.phase == "paused") "恢复导航" else "暂停导航",
                    if (s.navigation.phase == "paused") Icons.Outlined.PlayArrow
                    else Icons.Outlined.Pause,
                    false,
                    Modifier.fillMaxWidth(),
                    c::pauseNavigation,
                )
            },
        )
        SpeechControls(c)
        TextButton(onClick = stop, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Icon(Icons.Outlined.Stop, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("结束本次导航", fontSize = 16.sp)
        }
    }
}

@Composable
private fun HomeMetric(label: String, value: String) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontSize = 27.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun HomeStatus(label: String, value: String) {
    Column(Modifier.padding(vertical = 5.dp).semantics(mergeDescendants = true) {}) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun HomeHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { heading() }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun HomeButton(
    label: String,
    icon: ImageVector,
    primary: Boolean,
    modifier: Modifier,
    click: () -> Unit,
) {
    Button(
        onClick = click,
        modifier = modifier.heightIn(min = 60.dp),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
        colors =
            if (primary) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
    ) {
        Icon(icon, null, Modifier.size(23.dp))
        Spacer(Modifier.width(9.dp))
        Text(
            label,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
private fun HomeRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    card: Boolean,
    click: () -> Unit,
) {
    Surface(
        onClick = click,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color =
            if (card) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background,
        border = if (card) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
    ) {
        Row(
            Modifier.padding(16.dp).heightIn(min = 60.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
            Column(
                Modifier.weight(1f).padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ToolTile(title: String, subtitle: String, icon: ImageVector, click: () -> Unit) {
    Surface(
        onClick = click,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.padding(16.dp).heightIn(min = 84.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(icon, null, Modifier.size(25.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AdaptivePair(
    stack: Boolean,
    modifier: Modifier = Modifier,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    if (stack)
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            first()
            second()
        }
    else
        Row(
            modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { first() }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { second() }
        }
}

private fun todayChecks(p: Preferences): Set<String> =
    if (p.checkDay == LocalDate.now().toString())
        p.checks.split(',').filter { it in setOf("0", "1", "2", "3") }.toSet()
    else emptySet()

@Composable
internal fun DepartureChecklist(c: AppController, p: Preferences) {
    var today by remember { mutableStateOf(LocalDate.now().toString()) }
    LaunchedEffect(Unit) {
        while (true) {
            today = LocalDate.now().toString()
            delay(1000)
        }
    }
    val checked = if (p.checkDay == today) todayChecks(p) else emptySet()
    Section("出发前的小检查", "这是手动准备清单，不代表设备已自检通过。每天重新确认。") {
        Text("已完成 ${checked.size}/4 项")
        listOf("辅助用具准备好", "手机电量与网络已检查", "已确认周围环境", "眼镜佩戴稳固（协同时）；独立使用可确认不需要").forEachIndexed {
            i,
            label ->
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .toggleable(i.toString() in checked, role = Role.Checkbox) { on ->
                        val next = checked.toMutableSet()
                        if (on) next.add(i.toString()) else next.remove(i.toString())
                        c.saveChecks(next, today)
                    }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(i.toString() in checked, null)
                Text(label, Modifier.weight(1f))
            }
        }
    }
}
