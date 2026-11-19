package cn.yuntong.companion.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yuntong.companion.*
import cn.yuntong.companion.data.*
import org.json.JSONObject

private val Fog =
    lightColorScheme(
        primary = Color(0xFF3D607B),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFDCE8F0),
        onPrimaryContainer = Color(0xFF163247),
        secondaryContainer = Color(0xFFDCE8F0),
        onSecondaryContainer = Color(0xFF163247),
        background = Color(0xFFF3F5F6),
        onBackground = Color(0xFF182C3C),
        outlineVariant = Color(0xFFDCE4EB),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF182C3C),
        onSurfaceVariant = Color(0xFF485B6B),
        outline = Color(0xFF6B7D8A),
    )
private val Contrast =
    darkColorScheme(
        primary = Color(0xFFFFE39A),
        onPrimary = Color(0xFF14191F),
        primaryContainer = Color(0xFF293746),
        onPrimaryContainer = Color.White,
        background = Color(0xFF0F151B),
        onBackground = Color.White,
        secondaryContainer = Color(0xFF293746),
        onSecondaryContainer = Color.White,
        surface = Color(0xFF19232D),
        onSurface = Color.White,
        onSurfaceVariant = Color(0xFFE0E8F0),
        outline = Color(0xFFB4C5D4),
    )

@Composable
fun YuntongApp(c: AppController, requestedPage: String? = null, onPageOpened: () -> Unit = {}) {
    val s by c.state.collectAsStateWithLifecycle()
    val p by c.prefs.collectAsStateWithLifecycle()
    val records by c.records.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableStateOf("") }
    var backStack by rememberSaveable { mutableStateOf(listOf<String>()) }
    var scrollPositions by rememberSaveable { mutableStateOf(mapOf<String, Int>()) }
    var recordStage by remember { mutableStateOf("") }
    LaunchedEffect(requestedPage) {
        if (requestedPage == "紧急求助") {
            tab = 0
            page = requestedPage
            backStack = listOf("")
            onPageOpened()
        }
    }
    val savedPages = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    fun openPage(next: String) {
        backStack = backStack + page
        page = next
    }
    fun goBack() {
        page = backStack.lastOrNull() ?: ""
        backStack = backStack.dropLast(1)
    }
    val contentStage =
        when {
            page == "记录与资料" -> "$page:$recordStage"
            page == "场景问答" -> "$page:${s.assistantTask}:${s.busy}:${s.result.isNotBlank()}"
            page.isNotEmpty() -> page
            tab == 2 -> "${s.assistantTask}:${s.busy}:${s.result.isNotBlank()}"
            tab == 1 -> "${s.navigation.phase}:${s.selected?.id}:${s.route.isNotBlank()}"
            else -> "root"
        }
    var confirm by remember { mutableStateOf("") }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * p.large)
    ) {
        MaterialTheme(
            colorScheme = if (p.contrast) Contrast else Fog,
            typography =
                Typography(
                    bodyLarge =
                        MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 18.sp,
                            lineHeight = 28.sp,
                        ),
                    titleLarge =
                        MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                ),
        ) {
            val context = LocalContext.current
            val notifications =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            LaunchedEffect(Unit) {
                if (
                    Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                )
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            BackHandler(page.isNotEmpty()) { goBack() }
            val scrollKey = "$tab:$page:$contentStage"
            val pageScroll = remember(scrollKey) { ScrollState(scrollPositions[scrollKey] ?: 0) }
            DisposableEffect(scrollKey) {
                onDispose { scrollPositions = scrollPositions + (scrollKey to pageScroll.value) }
            }
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    NavigationBar(
                        modifier =
                            Modifier.semantics {
                                isTraversalGroup = true
                                traversalIndex = 1f
                            },
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        listOf(
                                "助行" to Icons.Outlined.AccessibilityNew,
                                "导航" to Icons.Outlined.NearMe,
                                "助手" to Icons.Outlined.AutoAwesome,
                                "我的" to Icons.Outlined.PersonOutline,
                            )
                            .forEachIndexed { i, (label, icon) ->
                                NavigationBarItem(
                                    selected = tab == i,
                                    onClick = {
                                        tab = i
                                        page = ""
                                        backStack = emptyList()
                                    },
                                    icon = { Icon(icon, null) },
                                    label = { Text(label) },
                                    modifier = Modifier.heightIn(min = 64.dp),
                                )
                            }
                    }
                },
            ) { padding ->
                if (tab == 0 && page.isEmpty()) {
                    HomeScreen(
                        c,
                        s,
                        p,
                        records,
                        Modifier.padding(padding),
                        { openPage(it) },
                        { tab = 1 },
                        { confirm = "结束本次导航" },
                    )
                } else
                    Column(
                        Modifier.fillMaxSize()
                            .padding(padding)
                            .verticalScroll(pageScroll)
                            .semantics {
                                isTraversalGroup = true
                                traversalIndex = 0f
                            }
                            .padding(horizontal = 22.dp)
                            .padding(top = 18.dp, bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (page.isNotEmpty())
                                IconButton(
                                    onClick = { goBack() },
                                    modifier = Modifier.size(56.dp),
                                ) {
                                    Icon(Icons.Outlined.ArrowBack, "返回上一页")
                                }
                            else
                                Image(
                                    painterResource(cn.yuntong.companion.R.drawable.ic_brand),
                                    "云瞳",
                                    Modifier.size(44.dp),
                                )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (page.isEmpty()) listOf("安心出发", "路线规划", "视觉助手", "我的云瞳")[tab]
                                    else page,
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.semantics { heading() },
                                )
                                Text(
                                    if (p.cooperative) "眼镜协同 · 手机提供导航" else "手机独立使用",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        TransientNotice(s.notice, "$tab:$page", s.noticeSpoken)
                        savedPages.SaveableStateProvider("$tab:$page") {
                            when (page) {
                                "出发检查" -> DepartureChecklist(c, p)
                                "拍照识字" -> FramesPage(c, s, photoReading = true)
                                "设备与连接" -> DeviceScreen(c, s, ::openPage)
                                "连接设置" -> ConnectionSettings(c, ::goBack)
                                "佩戴与准备" -> PreparationPage(c, s)
                                "地图服务" -> MapServicePage(c, p)
                                "助手取图" -> AssistantCapture(c, s, ::goBack)
                                "场景问答" -> AssistantScreen(c, s, ::openPage)
                                "资料管理" ->
                                    RecordsManagement(c, records.size) { confirm = "清空全部本机记录" }
                                "图像与回放" -> FramesPage(c, s)
                                "本机录像" -> GlassesRecordingsPage(c, records)
                                "记录与资料" ->
                                    RecordsScreen(c, records, ::openPage) { recordStage = it }
                                "无障碍设置" -> AccessibilityPage(c, p)
                                "联网识别服务" -> CloudPage(c, p)
                                "联系人" -> ContactsPage(c, records)
                                "紧急求助" -> EmergencyPage(c, records, p, ::openPage)
                                "使用教学" -> TutorialPage(c, s)
                                "隐私与存储" -> PrivacyPage(c) { confirm = "清空全部本机记录" }
                                "关于云瞳" -> AboutPage()
                                else ->
                                    when (tab) {
                                        1 ->
                                            NavigationScreen(c, s, p, records, ::openPage) {
                                                confirm = "结束本次导航"
                                            }
                                        2 -> AssistantScreen(c, s, ::openPage)
                                        3 -> MinePage(c, p, records) { openPage(it) }
                                    }
                            }
                        }
                    }
            }
            if (confirm.isNotEmpty())
                AlertDialog(
                    onDismissRequest = { confirm = "" },
                    title = { Text(confirm) },
                    text = {
                        Text(
                            if (confirm.startsWith("清空")) "此操作会删除本机记录、收藏、联系人、已保存画面和录像。导出的文件由你自行管理。"
                            else "结束手机导航，并通知已连接的接收端。眼镜观察可在设备页停止。"
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (confirm.startsWith("清空")) c.clearRecords()
                                else c.stopNavigation()
                                confirm = ""
                            }
                        ) {
                            Text("确认")
                        }
                    },
                    dismissButton = { TextButton(onClick = { confirm = "" }) { Text("取消") } },
                )
        }
    }
}

@Composable
internal fun Section(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            content()
        }
    }
}

@Composable
internal fun Action(
    label: String,
    icon: ImageVector = Icons.Outlined.ChevronRight,
    enabled: Boolean = true,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    if (primary)
        Button(
            onClick,
            Modifier.fillMaxWidth().heightIn(min = 58.dp),
            enabled = enabled,
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            Icon(icon, null)
            Spacer(Modifier.width(10.dp))
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        }
    else
        OutlinedButton(
            onClick,
            Modifier.fillMaxWidth().heightIn(min = 56.dp),
            enabled = enabled,
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(14.dp),
        ) {
            Icon(icon, null)
            Spacer(Modifier.width(10.dp))
            Text(label, Modifier.weight(1f))
        }
}

@Composable
internal fun Toggle(title: String, subtitle: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(checked, role = Role.Switch, onValueChange = change)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
private fun Status(label: String, value: String) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun phase(value: String) =
    when (value) {
        "active" -> "进行中"
        "paused" -> "已暂停"
        "ended" -> "已结束"
        else -> "尚未开始"
    }

@Composable
internal fun FramePreview(s: ScreenState) {
    val f = s.frame
    if (f == null)
        Box(
            Modifier.fillMaxWidth()
                .height(160.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(s.frameStatus)
        }
    else
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(f.bitmap.width.toFloat() / f.bitmap.height)) {
                Image(f.bitmap.asImageBitmap(), "${f.source}的当前画面", Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
                    s.findings.forEach {
                        drawRect(
                            Color(0xFFCC6A00),
                            Offset(it.left * size.width, it.top * size.height),
                            Size(
                                (it.right - it.left) * size.width,
                                (it.bottom - it.top) * size.height,
                            ),
                            style = Stroke(3.dp.toPx()),
                        )
                    }
                    val analysis = runCatching { JSONObject(f.analysis) }.getOrNull()
                    if (analysis?.optString("frameId") == f.id) {
                        val boxes = analysis.optJSONArray("boxes")
                        if (boxes != null)
                            for (i in 0 until boxes.length()) {
                                val b = boxes.optJSONObject(i) ?: continue
                                val l = b.optDouble("left").toFloat()
                                val t = b.optDouble("top").toFloat()
                                val r = b.optDouble("right").toFloat()
                                val bottom = b.optDouble("bottom").toFloat()
                                if (l in 0f..1f && t in 0f..1f && r in l..1f && bottom in t..1f)
                                    drawRect(
                                        Color(0xFF3877A0),
                                        Offset(l * size.width, t * size.height),
                                        Size((r - l) * size.width, (bottom - t) * size.height),
                                        style = Stroke(3.dp.toPx()),
                                    )
                            }
                    }
                }
            }
            Text(s.frameStatus, style = MaterialTheme.typography.labelLarge)
            s.previewFps?.let { Text("手机预览约 $it 帧/秒", style = MaterialTheme.typography.bodySmall) }
            if (f.analysis.isNotBlank())
                Text(
                    "同帧分析：${runCatching{JSONObject(f.analysis).optString("text")}.getOrDefault("")}",
                    style = MaterialTheme.typography.bodySmall,
                )
        }
}

@Composable
private fun MinePage(
    c: AppController,
    p: Preferences,
    records: List<Record>,
    open: (String) -> Unit,
) {
    Section("云瞳", "V1.0 原生候选版 · 雾灰蓝") {
        Toggle("眼镜协同使用", "开启后由设备组织助行提示", p.cooperative, c::mode)
        Text("已保存 ${records.size} 条本机资料", style = MaterialTheme.typography.bodyMedium)
    }
    Section("我的工具") {
        listOf(
                "记录与资料" to Icons.Outlined.History,
                "本机录像" to Icons.Outlined.VideoLibrary,
                "联系人" to Icons.Outlined.Contacts,
                "紧急求助" to Icons.Outlined.Sos,
                "无障碍设置" to Icons.Outlined.AccessibilityNew,
                "联网识别服务" to Icons.Outlined.Cloud,
                "设备与连接" to Icons.Outlined.Link,
                "使用教学" to Icons.Outlined.School,
                "隐私与存储" to Icons.Outlined.PrivacyTip,
                "关于云瞳" to Icons.Outlined.Info,
            )
            .forEach { (label, icon) ->
                FlowRow(
                    label,
                    when (label) {
                        "记录与资料" -> "搜索、收藏与出行记录"
                        "无障碍设置" -> "字号、对比度与语音"
                        "设备与连接" -> "已保存设备与准备状态"
                        else -> "查看与管理"
                    },
                    icon,
                ) {
                    open(label)
                }
            }
    }
}

@Composable
private fun AccessibilityPage(c: AppController, p: Preferences) {
    val context = LocalContext.current
    Section("看得清、操作稳") {
        Toggle("高对比度", "深色背景与明亮文字", p.contrast) { c.bool("contrast", it) }
        Text("附加字号：${(p.large*100).toInt()}%（同时跟随系统字号）")
        listOf("标准字号" to 1f, "大字号" to 1.2f, "特大字号" to 1.4f).forEach { (label, value) ->
            Action(label) { c.number("large", value) }
        }
        Action("打开系统无障碍设置", Icons.Outlined.OpenInNew) {
            context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }
    Section("语音与触觉") {
        Text("朗读速度 ${p.rate}")
        Slider(
            p.rate,
            { c.number("rate", it) },
            valueRange = .6f..1.5f,
            steps = 8,
            modifier = Modifier.semantics { contentDescription = "朗读速度" },
        )
        Toggle("手机振动反馈", "用于确认等本机操作", p.feedback) { c.bool("feedback", it) }
        Toggle("手机播放协同提示", "播放设备返回的提示；读屏开启时仍保留关键导航语音", p.phoneOutput) { c.bool("phoneOutput", it) }
        Text("读屏负责按钮与页面；地图或设备提示单独播报。重复提示会合并，暂停朗读不会暂停导航。连接中断等状态提醒仍由手机告知。")
        Action("试听当前语速", Icons.Outlined.VolumeUp) { c.speak("欢迎使用云瞳。你可以根据习惯调整语速。") }
        Text("界面不使用自动轮播。图像和定位更新不会主动移动读屏焦点。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CloudPage(c: AppController, p: Preferences) {
    var base by rememberSaveable { mutableStateOf(c.secrets.get("apiBase")) }
    var model by rememberSaveable { mutableStateOf(c.secrets.get("apiModel")) }
    var key by remember { mutableStateOf(c.secrets.get("apiKey")) }
    Section("联网识别服务", "填写兼容 Chat Completions 图像输入的服务。密钥使用 Android Keystore 加密保存。") {
        OutlinedTextField(
            base,
            { base = it },
            Modifier.fillMaxWidth(),
            label = { Text("HTTPS 基础地址，例如 https://服务域名/v1") },
        )
        OutlinedTextField(
            model,
            { model = it },
            Modifier.fillMaxWidth(),
            label = { Text("视觉模型名称") },
        )
        OutlinedTextField(
            key,
            { key = it },
            Modifier.fillMaxWidth(),
            label = { Text("API 密钥") },
            visualTransformation = PasswordVisualTransformation(),
        )
        Toggle("允许使用移动数据", "手机连接眼镜局域网时，可通过可用移动网络识别", p.mobileData) { c.bool("mobileData", it) }
        Action("保存配置", Icons.Outlined.Save, primary = true) { c.configureCloud(base, model, key) }
    }
}

@Composable
private fun ContactsPage(c: AppController, records: List<Record>) {
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var dial by remember { mutableStateOf<Record?>(null) }
    val context = LocalContext.current
    Section("主动联系") {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("联系人姓名") })
        OutlinedTextField(phone, { phone = it }, Modifier.fillMaxWidth(), label = { Text("电话号码") })
        Action("保存联系人", Icons.Outlined.PersonAdd) {
            if (name.isBlank() || !phone.matches(Regex("[+0-9 -]{3,25}"))) c.notice("请填写姓名和有效号码")
            else {
                c.save("联系人", name, phone)
                name = ""
                phone = ""
            }
        }
        Action("主动分享当前位置", Icons.Outlined.Share) {
            val text = c.shareLocationText()
            if (text == null) c.notice("暂无位置，请先开始导航")
            else
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, text),
                        "分享位置",
                    )
                )
        }
    }
    records
        .filter { it.kind == "联系人" }
        .forEach { r ->
            Section(r.title) {
                Text(r.content)
                Action("打开拨号界面", Icons.Outlined.Call) { dial = r }
                Action("删除联系人", Icons.Outlined.DeleteOutline) { c.delete(r.id) }
            }
        }
    dial?.let { r ->
        AlertDialog(
            onDismissRequest = { dial = null },
            title = { Text("联系${r.title}") },
            text = { Text("将打开系统拨号界面：${r.content}，由你决定是否拨出。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        dial = null
                        runCatching {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_DIAL,
                                        Uri.fromParts("tel", r.content, null),
                                    )
                                )
                            }
                            .onFailure { c.notice("此设备没有拨号应用") }
                    }
                ) {
                    Text("打开拨号")
                }
            },
            dismissButton = { TextButton(onClick = { dial = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun TutorialPage(c: AppController, s: ScreenState) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    Section("首次操作练习", "开启 TalkBack 后，左右滑动浏览，双击选中，使用系统返回返回上一页。") {
        Text(listOf("1. 找到下面的按钮并双击，体验语音。", "2. 选择暂停朗读，再继续。", "3. 点击确认，完成这次练习。")[step])
        Action("练习朗读", Icons.Outlined.VolumeUp) {
            c.speak("这是云瞳操作练习。你已找到按钮。可以随时暂停朗读。")
            step = 1
        }
        Action("练习暂停", Icons.Outlined.Pause) {
            c.speech.pause()
            step = 2
        }
        Action("继续朗读", Icons.Outlined.PlayArrow) { c.speech.resume() }
        Action("确认完成练习", Icons.Outlined.Check) {
            if (step == 2) {
                c.save("教学", "首次操作练习完成", "完成朗读、暂停与确认", "手动操作")
                c.notice("练习完成，可以开始使用本机工具")
            } else c.notice("请先完成朗读与暂停练习")
        }
    }
    Section("路线与协同演练", "所有位置、目的地与返回结果都明确标记为模拟。连接电脑接收端后可观察实际数据收发。") {
        Action("开始模拟路线", Icons.Outlined.PlayCircle, primary = true, onClick = c::demoStart)
        if (
            s.navigation.source == "simulation" && s.navigation.phase in listOf("active", "paused")
        ) {
            Text("当前步骤 ${s.practice+1}/5：${s.navigation.instruction}")
            Action("下一步骤", Icons.Outlined.SkipNext) { c.demoStep(s.practice + 1) }
            Action("暂停或恢复", Icons.Outlined.Pause, onClick = c::pauseNavigation)
            Action("结束演练", Icons.Outlined.Stop, onClick = c::stopNavigation)
        }
    }
}

@Composable
private fun PrivacyPage(c: AppController, clear: () -> Unit) {
    Section("你的数据由你选择") {
        Text("默认不保存完整图传，不自动上传画面。截图、收藏和记录保存在本机；联网问答每次由你确认。地图服务在同意后启用。")
        Text("使用系统分享时，由你选择接收应用和接收人。卸载应用会删除本机资料和加密密钥，导出副本不受影响。")
        Action("停止全部任务", Icons.Outlined.Stop, onClick = c::stopAll)
        Action("清空本机记录", Icons.Outlined.DeleteOutline, onClick = clear)
    }
}

@Composable
private fun AboutPage() {
    Section("云瞳 V1.0", "为视障使用者设计的安卓眼镜伴侣") {
        Text("手机获取导航、展示图像、提供识字与联网工具；RDK 组织导航信息并结合设备感知形成助行提示。")
        Text("当前为开发候选版。真实地图能力需要高德密钥；正式 RDK 接口、安卓真机读屏和视障使用者试用分别验收。")
        Text(
            "无障碍设计参考 Soundscape Android、TalkBack、Android Compose 语义规范及 Accessibility Test Framework。使用开源依赖不代表已通过无障碍使用验收。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
