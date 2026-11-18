package cn.yuntong.companion.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cn.yuntong.companion.*
import cn.yuntong.companion.data.*
import cn.yuntong.companion.domain.homeGuidance
import org.json.JSONObject

@Composable
internal fun NavigationScreen(
    c: AppController,
    s: ScreenState,
    p: Preferences,
    records: List<Record>,
    open: (String) -> Unit,
    stop: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var city by rememberSaveable { mutableStateOf("") }
    var steps by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val active = s.navigation.phase in setOf("active", "paused")
    val preview = !active && s.route.isNotBlank() && s.selected != null
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            granted ->
            if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) c.plan()
            else c.notice("需要精确定位才能规划当前起点，请在系统权限设置中允许")
        }
    val voice =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                query = it
            }
        }
    BackHandler(!active && s.selected != null) {
        if (preview) c.discardPlan(false) else c.discardPlan(true)
    }
    if (active) {
        val n = s.navigation
        FlowTitle(
            n.destination,
            if (n.source == "simulation") "模拟演练 · ${if(n.phase=="paused")"已暂停" else "进行中"}"
            else "步行导航 · ${if(n.phase=="paused")"已暂停" else "进行中"}",
        )
        val g =
            homeGuidance(
                n,
                p.cooperative,
                s.peer != null,
                s.guidance,
                s.guidanceExpired,
                s.navigationIssue,
                s.peer == cn.yuntong.companion.transport.DevicePeer.RDK,
            )
        Section(if (p.cooperative) "当前助行提示" else "当前导航提示", g.detail) {
            Text(g.text, style = MaterialTheme.typography.headlineSmall)
        }
        Text(
            "剩余 ${n.remainingMeters?.let { "$it 米" } ?: "等待更新"} · ${n.remainingSeconds?.let { "约 ${(it+59)/60} 分钟" } ?: "时间待更新"}"
        )
        if (p.cooperative)
            Text(if (s.peer == cn.yuntong.companion.transport.DevicePeer.RDK) s.navigationDelivery else s.delivery,
                style = MaterialTheme.typography.bodyMedium)
        Action("重复提示", Icons.Outlined.VolumeUp, primary = true, onClick = c::repeat)
        SpeechControls(c)
        Action(
            if (n.phase == "paused") "恢复导航" else "暂停导航",
            Icons.Outlined.Pause,
            onClick = c::pauseNavigation,
        )
        if (n.source == "simulation")
            Action("下一演练步骤", Icons.Outlined.SkipNext, enabled = n.phase == "active") {
                c.demoStep(s.practice + 1)
            }
        if (s.nearby) Action("确认已到达入口", Icons.Outlined.CheckCircle, onClick = stop)
        TextButton(onClick = { steps = !steps }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (steps) "收起路线步骤" else "查看路线步骤")
        }
        if (steps) RouteSteps(c, s.route)
        Action("结束导航", Icons.Outlined.Stop, onClick = stop)
        return
    }
    if (preview) {
        FlowBack("返回目的地确认") { c.discardPlan(false) }
        FlowTitle("预习这条路线", s.selected!!.name)
        RouteSteps(c, s.route)
        Action("开始导航", Icons.Outlined.NearMe, primary = true, onClick = c::startNavigation)
        if (p.cooperative && s.peer == null)
            FlowRow("连接设备后出发", "当前使用眼镜协同", Icons.Outlined.Link) { open("设备与连接") }
        return
    }
    s.selected?.let { place ->
        FlowBack("返回地点搜索") { c.discardPlan(true) }
        FlowTitle("确认目的地", "请核对名称与地址，再规划步行路线。")
        Section(place.name, place.address) {
            Text("坐标系：GCJ-02", style = MaterialTheme.typography.bodySmall)
        }
        Action(
            if (s.planning) "正在规划路线" else "确认并规划步行路线",
            Icons.Outlined.Route,
            enabled = !s.planning,
            primary = true,
        ) {
            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
            )
                c.plan()
            else
                permission.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )
                )
        }
        if (s.planning) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Action("取消路线规划", Icons.Outlined.Close) { c.discardPlan(false) }
        }
        Action("收藏这个地点", Icons.Outlined.StarOutline, onClick = c::favorite)
        if (!p.mapConsent || BuildConfig.AMAP_KEY.isBlank())
            FlowRow("地图服务设置", "查看服务启用状态", Icons.Outlined.Settings) { open("地图服务") }
        return
    }
    FlowTitle("去想去的地方", "先找到地点，再确认路线。")
    OutlinedTextField(
        query,
        { query = it },
        Modifier.fillMaxWidth(),
        label = { Text("地点名称或地址") },
        singleLine = true,
    )
    OutlinedTextField(
        city,
        { city = it },
        Modifier.fillMaxWidth(),
        label = { Text("城市（选填）") },
        singleLine = true,
    )
    Action(
        if (s.searching) "正在搜索" else "搜索地点",
        Icons.Outlined.Search,
        enabled = !s.searching && query.isNotBlank(),
        primary = true,
    ) {
        c.search(query, city)
    }
    TextButton(
        onClick = {
            runCatching {
                    voice.launch(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                            )
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                    )
                }
                .onFailure { c.notice("手机未安装语音输入服务，请使用文字输入") }
        },
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Icon(Icons.Outlined.Mic, null)
        Text("语音输入目的地")
    }
    if (!p.mapConsent || BuildConfig.AMAP_KEY.isBlank())
        FlowRow(
            "地图服务待启用",
            if (BuildConfig.AMAP_KEY.isBlank()) "此安装包尚未配置地图密钥" else "阅读服务说明后启用搜索",
            Icons.Outlined.Info,
        ) {
            open("地图服务")
        }
    if (s.places.isNotEmpty()) {
        Text(
            "搜索结果",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        s.places.forEach { place ->
            FlowRow(place.name, place.address, Icons.Outlined.Place) { c.select(place) }
        }
    } else {
        Text(
            "常用地点",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        val favorites = records.filter { it.kind == "地点" }.distinctBy { it.content }
        if (favorites.isEmpty())
            Text("搜索并确认地点后，可收藏到这里。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        favorites.forEach { r ->
            FlowRow(
                r.title,
                runCatching { JSONObject(r.content).optString("address") }.getOrDefault("已保存地点"),
                Icons.Outlined.StarOutline,
            ) {
                c.favoriteSelect(r)
            }
        }
    }
    FlowRow("先练习一次出行", "明确标注的模拟路线，不使用真实位置", Icons.Outlined.School) { open("使用教学") }
}

@Composable
private fun RouteSteps(c: AppController, raw: String) {
    val route = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    val meters = if (route.has("meters")) "${route.optInt("meters")} 米" else "距离待更新"
    val duration = if (route.has("seconds")) "${(route.optInt("seconds")+59)/60} 分钟" else "时间待更新"
    Text("$meters · $duration", style = MaterialTheme.typography.headlineSmall)
    val list = route.optJSONArray("steps")
    val labels =
        if (list != null)
            (0 until list.length()).mapNotNull { i ->
                val step = list.optJSONObject(i) ?: return@mapNotNull null
                val links = step.optJSONArray("links")
                val road =
                    if (links != null)
                        (0 until links.length())
                            .mapNotNull { links.optJSONObject(it)?.optString("road") }
                            .filter { it.isNotBlank() }
                            .distinct()
                            .joinToString("、")
                    else ""
                "${i+1}. ${step.optString("text").ifBlank { road.ifBlank { "按地图路线步行" } }}，${step.optInt("length")}米"
            }
        else emptyList()
    labels.forEach { Text(it) }
    if (labels.isEmpty()) Text("暂无详细步骤，等待地图更新。")
    SpeechControls(c, "路线约$meters，预计$duration。\n" + labels.joinToString("\n"), "路线预习")
}

@Composable
internal fun MapServicePage(c: AppController, p: Preferences) {
    val context = LocalContext.current
    FlowTitle("地图服务", "地点搜索和步行导航由高德提供。")
    Text("服务会向高德提供查询、定位及必要设备信息。你可以先使用本地识字和路线演练。")
    Action("查看高德隐私政策", Icons.Outlined.OpenInNew) {
        runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://lbs.amap.com/pages/privacy/"))
                )
            }
            .onFailure { c.notice("无法打开浏览器") }
    }
    if (!p.mapConsent)
        Action("同意并启用地图服务", Icons.Outlined.Check, primary = true, onClick = c::consentMap)
    else Text("已同意地图服务说明")
    if (BuildConfig.AMAP_KEY.isBlank()) Text("此安装包未配置高德密钥。请由构建者按安装说明配置 Android 服务密钥后更新安装包。")
    else Text("地图密钥已配置，实际可用性以搜索与导航结果为准。")
}
