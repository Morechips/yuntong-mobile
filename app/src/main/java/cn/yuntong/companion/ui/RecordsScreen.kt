package cn.yuntong.companion.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.core.content.FileProvider
import cn.yuntong.companion.AppController
import cn.yuntong.companion.data.Record
import java.text.SimpleDateFormat
import java.io.File
import java.util.*

@Composable
internal fun RecordsScreen(
    c: AppController,
    records: List<Record>,
    open: (String) -> Unit,
    stage: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("全部") }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var filtering by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var limit by remember(query, kind) { mutableIntStateOf(30) }
    val record = records.firstOrNull { it.id == selectedId }
    LaunchedEffect(selectedId) { stage(selectedId) }
    BackHandler(selectedId.isNotBlank()) { selectedId = "" }
    if (record != null) {
        FlowBack("返回记录列表") { selectedId = "" }
        FlowTitle(record.title, "${record.kind} · ${record.source} · ${recordDate(record)}")
        if (record.kind == "关键帧") {
            val bitmap = remember(record.id) { BitmapFactory.decodeFile(record.content) }
            if (bitmap != null) Image(bitmap.asImageBitmap(), "保存的关键帧", Modifier.fillMaxWidth())
            else Text("图像文件已不存在")
        } else if (record.kind == "录像") {
            val file = File(record.content)
            if (file.isFile) VideoPlayback(Uri.fromFile(file), c)
            else Text("录像文件已不存在")
        } else Text(record.content)
        SpeechControls(
            c,
            record.title + "。" + when (record.kind) {
                "关键帧" -> "保存的图像"
                "录像" -> "本机录像，来源${record.source}"
                else -> record.content
            },
            "资料朗读",
        )
        if (record.kind == "地点") Text("使用收藏地点，请从导航页的常用地点进入。")
        TextButton(onClick = { deleting = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.DeleteOutline, null)
            Text("删除这条记录")
        }
        if (deleting)
            AlertDialog(
                onDismissRequest = { deleting = false },
                title = { Text("删除这条记录？") },
                text = { Text(record.title) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            c.delete(record.id)
                            c.speech.stop()
                            deleting = false
                            selectedId = ""
                        }
                    ) {
                        Text("删除")
                    }
                },
                dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } },
            )
        return
    }
    FlowTitle("找到需要的记录", "最近的出行、识字与收藏，都留在本机。")
    OutlinedTextField(
        query,
        { query = it },
        Modifier.fillMaxWidth(),
        label = { Text("搜索记录内容") },
        singleLine = true,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { filtering = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("分类：$kind")
            Icon(Icons.Outlined.ExpandMore, null)
        }
        TextButton(onClick = { open("资料管理") }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("管理资料")
        }
    }
    val visible =
        records.filter {
            (kind == "全部" || it.kind == kind) &&
                (it.title.contains(query, true) || it.content.contains(query, true))
        }
    Text("${visible.size} 条记录 · 最近优先", style = MaterialTheme.typography.labelLarge)
    if (visible.isEmpty())
        Text(
            if (query.isBlank() && kind == "全部") "还没有记录。识字、收藏和出行后会显示在这里。"
            else "没有符合条件的记录，可更换关键词或分类。"
        )
    visible.take(limit).forEach { r ->
        FlowRow(
            r.title,
            "${r.kind} · ${recordDate(r)}",
            when (r.kind) {
                "导航" -> Icons.Outlined.NearMe
                "收藏" -> Icons.Outlined.BookmarkBorder
                "关键帧" -> Icons.Outlined.Image
                else -> Icons.Outlined.Description
            },
        ) {
            selectedId = r.id
        }
    }
    if (visible.size > limit) Action("显示更多记录") { limit += 30 }
    if (filtering)
        AlertDialog(
            onDismissRequest = { filtering = false },
            title = { Text("记录分类") },
            text = {
                LazyColumn(Modifier.heightIn(max = 350.dp)) {
                    items((listOf("全部", "导航", "识字", "收藏") + records.map { it.kind }).distinct()) {
                        type ->
                        TextButton(
                            onClick = {
                                kind = type
                                filtering = false
                            },
                            modifier =
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                                    selected = kind == type
                                },
                        ) {
                            Text(type)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { filtering = false }) { Text("关闭") } },
        )
}

private fun recordDate(record: Record) =
    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(record.createdAt))

@Composable
internal fun RecordsManagement(c: AppController, count: Int, clear: () -> Unit) {
    val context = LocalContext.current
    FlowTitle("管理资料", "当前共 $count 条本机记录。")
    Text("导出文件包含记录文字、来源及时间。保存的关键帧图片不会随 JSON 打包。")
    Action("导出并分享记录", Icons.Outlined.IosShare, primary = true) {
        runCatching {
                val file = c.exportRecords()
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND)
                            .setType("application/json")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        "分享云瞳记录",
                    )
                )
            }
            .onFailure { c.notice("无法导出或打开分享，请检查存储空间和系统分享功能") }
    }
    Section("清理本机资料", "导出的副本由你自行管理。") {
        Text("清空会删除本机记录、收藏、联系人和已保存画面。此操作需要再次确认。")
        Action("清空全部记录", Icons.Outlined.DeleteOutline, onClick = clear)
    }
}
