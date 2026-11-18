package cn.yuntong.companion

import android.Manifest
import android.graphics.*
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import cn.yuntong.companion.recognition.CameraGuide
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class AccessibilityFlowTest {
    @get:Rule
    val permission =
        GrantPermissionRule.grant(
            Manifest.permission.CAMERA,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val c
        get() = (ui.activity.application as YuntongApplication).controller

    @Before
    fun setup() {
        ui.runOnIdle {
            c.stopAll()
            c.chooseAssistant("")
            c.bool("contrast", false)
            c.number("large", 1f)
        }
    }

    @After
    fun finish() {
        ui.runOnIdle {
            c.stopAll()
            c.chooseAssistant("")
        }
    }

    private fun click(text: String) = ui.onNodeWithText(text).performScrollTo().performClick()

    private fun tab(text: String) = ui.onNodeWithText(text, useUnmergedTree = true).performClick()

    private fun shot(name: String) {
        val f = File(ui.activity.getExternalFilesDir("qa-accessibility"), "$name.png")
        f.parentFile?.mkdirs()
        f.outputStream().use {
            ui.onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun textImage(): Bitmap =
        Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).apply {
                drawColor(Color.WHITE)
                drawText(
                    "云瞳 图书馆入口",
                    180f,
                    350f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = 90f
                    },
                )
            }
        }

    @Test
    fun realOcrFramingAndDarkScene() = runBlocking {
        val guide = CameraGuide { _, _ -> }
        try {
            val text = guide.inspect(textImage())
            assertEquals(text.text, "text", text.code)
            val dark =
                Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.BLACK)
                }
            assertEquals("dark", guide.inspect(dark).code)
        } finally {
            guide.close()
        }
    }

    @Test
    fun resultPaneAndCompletionFeedback() {
        tab("助手")
        click("读文字")
        ui.runOnIdle { c.setImage(textImage(), "取景测试文字图") }
        click("识别中文文字")
        ui.waitUntil(40000) { !c.state.value.busy && c.state.value.result.isNotBlank() }
        ui.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "识别结果"))
            .assertExists()
        assertTrue(c.state.value.notice.contains("识别完成"))
        ui.onNodeWithText("识别结果").performScrollTo()
        shot("01-result-feedback")
        click("返回助手功能")
        assertEquals("", c.state.value.assistantTask)
    }

    @Test
    fun cameraGuidanceControlsAndReturn() {
        tab("助手")
        click("读文字")
        click(if (c.state.value.frame == null) "拍照或选择图片" else "更换画面")
        click("打开相机")
        ui.onNodeWithContentDescription("文字取景引导").performScrollTo().assertIsOn()
        ui.onNodeWithText("重复取景提示").performScrollTo().assertHasClickAction()
        ui.waitUntil(30000) {
            ui.onAllNodes(hasText("拍照并选用画面") and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        val observedHints = listOf(
            "画面偏暗", "画面很亮且未检测到文字", "暂未检测到文字",
            "文字靠近画面边缘", "检测到的文字较小", "已检测到文字",
        )
        ui.waitUntil(40000) {
            observedHints.any { prefix ->
                ui.onAllNodes(hasText(prefix, substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
        }
        ui.onNodeWithText("关闭相机").performScrollTo().assertIsDisplayed()
        shot("02-camera-guide")
        File(ui.activity.getExternalFilesDir("qa-accessibility"), "02-camera-screen.png")
            .outputStream().use {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        ui.onNodeWithContentDescription("文字取景引导").performClick().assertIsOff()
        val previousFrame = c.state.value.frame?.id
        click("拍照并选用画面")
        ui.waitUntil(30000) {
            c.state.value.frame != null && c.state.value.frame?.id != previousFrame &&
                c.state.value.notice.contains("拍照完成")
        }
        ui.onNodeWithText("识别中文文字").assertExists()
    }

    @Test
    fun frameworkCanFocusTaskAndLaunchWithoutCoordinates() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        tab("助手")
        ui.waitForIdle()
        val info = automation.serviceInfo
        val previousFlags = info.flags
        info.flags =
            info.flags or
                android.accessibilityservice.AccessibilityServiceInfo
                    .FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        automation.serviceInfo = info
        try {
            ui.waitUntil(5000) {
                ui.activity
                    .getSystemService(android.view.accessibility.AccessibilityManager::class.java)
                    .isTouchExplorationEnabled
            }
            fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (
                    (node.text?.toString().orEmpty() +
                            node.contentDescription?.toString().orEmpty())
                        .contains("读文字")
                ) {
                    var action: AccessibilityNodeInfo? = node
                    while (action != null && !action.isClickable) action = action.parent
                    if (action != null) return action
                }
                for (i in 0 until node.childCount) {
                    val match = find(node.getChild(i))
                    if (match != null) return match
                }
                return null
            }
            var task: AccessibilityNodeInfo? = null
            ui.waitUntil(5000) {
                task = find(automation.rootInActiveWindow)
                task != null
            }
            assertNotNull("系统无障碍节点应暴露读文字动作", task)
            val selectedTask = requireNotNull(task)
            assertTrue(selectedTask.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
            assertTrue(selectedTask.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            ui.waitForIdle()
            ui.onNodeWithText("返回助手功能").assertExists()
            ui.runOnIdle { c.speak("导航关键提示测试", automatic = true, source = "地图导航") }
            assertEquals("地图导航", c.speech.state.value.source)
        } finally {
            info.flags = previousFlags
            automation.serviceInfo = info
        }
    }

    @Test
    fun connectionFileImportNeedsExplicitSave() {
        val context = ui.activity
        val file =
            File(context.cacheDir, "exports/qa-connection.json").apply {
                parentFile?.mkdirs()
                writeText(
                    """{"schema":"yuntong-test-connection-v1","name":"导入的测试设备","address":"ws://10.0.2.2:8766/link","token":"instrumentation-test-token"}"""
                )
            }
        val uri =
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.files",
                file,
            )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor =
            object : android.app.Instrumentation.ActivityMonitor() {
                override fun onStartActivity(
                    intent: android.content.Intent?
                ): android.app.Instrumentation.ActivityResult? {
                    return if (intent?.action == android.content.Intent.ACTION_GET_CONTENT)
                        android.app.Instrumentation.ActivityResult(
                            android.app.Activity.RESULT_OK,
                            android.content.Intent().setData(uri),
                        )
                    else null
                }
            }
        instrumentation.addMonitor(monitor)
        val previousName = c.secrets.get("deviceName")
        try {
            tab("我的")
            click("设备与连接")
            click("连接设置")
            click("导入连接文件")
            ui.waitUntil(5000) { c.state.value.notice.contains("已导入") }
            ui.onNodeWithText("设备名称").assertTextContains("导入的测试设备")
            assertEquals(previousName, c.secrets.get("deviceName"))
            assertNotEquals("已连接测试接收端", c.state.value.connection)
            ui.onNodeWithText("导入连接文件").performScrollTo()
            shot("03-import-profile")
        } finally {
            instrumentation.removeMonitor(monitor)
            file.delete()
        }
    }
}
