package cn.yuntong.companion

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import cn.yuntong.companion.recognition.LocalRecognition
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class ProductFlowTest {
    @get:Rule
    val permissions =
        GrantPermissionRule.grant(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.CAMERA,
        )
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val c
        get() = (ui.activity.application as YuntongApplication).controller

    private fun screenshot(name: String) {
        ui.waitForIdle()
        val file = File(ui.activity.getExternalFilesDir("qa"), "$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use {
            ui.onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Before
    fun setup() {
        ui.runOnIdle {
            c.stopAll()
            c.bool("cooperative", false)
            c.bool("contrast", false)
            c.number("large", 1f)
            c.notice("欢迎使用云瞳")
        }
        ui.waitForIdle()
    }

    @After
    fun finish() {
        ui.runOnIdle { c.stopAll() }
    }

    @Test
    fun fourTabsAndAccessibleExercise() {
        ui.onNodeWithText("助行", useUnmergedTree = true).assertExists()
        screenshot("01-home")
        ui.onNodeWithText("我的", useUnmergedTree = true).performClick()
        ui.onNodeWithText("使用教学").performScrollTo().performClick()
        ui.onNodeWithText("练习朗读").performScrollTo().performClick()
        ui.onNodeWithText("练习暂停").performScrollTo().performClick()
        ui.onNodeWithText("确认完成练习").performScrollTo().performClick()
        ui.onNodeWithText("开始模拟路线").performScrollTo().performClick()
        ui.waitUntil(5000) { c.state.value.navigation.phase == "active" }
        ui.onNodeWithText("导航", useUnmergedTree = true).performClick()
        screenshot("02-navigation")
        ui.onNodeWithText("下一演练步骤").performScrollTo().performClick()
        ui.waitUntil(3000) { c.state.value.practice == 1 }
        ui.onNodeWithText("暂停导航").performScrollTo().performClick()
        ui.waitUntil(3000) { c.state.value.navigation.phase == "paused" }
        ui.onNodeWithText("恢复导航").performScrollTo().performClick()
        ui.onNodeWithText("结束导航").performScrollTo().performClick()
        ui.onNodeWithText("确认", useUnmergedTree = true).performClick()
        ui.waitUntil(3000) { c.state.value.navigation.phase == "ended" }
    }

    @Test
    fun localChineseOcrAndCancellation() {
        val b = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888)
        Canvas(b).apply {
            drawColor(Color.WHITE)
            drawText(
                "云瞳 智能助行",
                70f,
                180f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 80f
                },
            )
            drawText(
                "教学楼入口 150米",
                70f,
                350f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 80f
                },
            )
        }
        ui.runOnIdle {
            c.setImage(b, "测试生成文字图")
            c.recognize("ocr")
        }
        ui.waitUntil(40000) { !c.state.value.busy }
        assertTrue(c.state.value.result, c.state.value.result.contains("教学楼"))
        ui.onNodeWithText("助手", useUnmergedTree = true).performClick()
        screenshot("03-assistant")
        ui.onNodeWithText("朗读内容").performScrollTo().assertHasClickAction()
        screenshot("04-ocr-result")
        ui.runOnIdle {
            c.recognize("ocr")
            c.cancelRecognition()
            c.setImage(Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888), "替换图像")
        }
        ui.waitForIdle()
        assertEquals("", c.state.value.result)
        assertFalse(c.state.value.busy)
    }

    @Test
    fun phoneToPcRoundtripAndSameFrame() {
        ui.runOnIdle {
            c.mode(true)
            c.connect("ws://10.0.2.2:8766/link", "instrumentation-test-token")
        }
        ui.waitUntil(20000) {
            c.state.value.connection == "已连接测试接收端" && c.state.value.frame != null
        }
        ui.runOnIdle {
            c.demoStart()
            c.demoStep(2)
        }
        ui.waitUntil(10000) { c.state.value.guidance.contains("转向受阻") }
        assertEquals("simulation", c.state.value.navigation.source)
        assertTrue(c.state.value.delivery.contains("接收") || c.state.value.delivery.contains("执行"))
        val f = c.state.value.frame!!
        assertTrue(f.analysis.contains(f.id))
        screenshot("05-cooperative")
        ui.onNodeWithText("我的", useUnmergedTree = true).performClick()
        ui.onNodeWithText("设备与连接").performScrollTo().performClick()
        ui.onNodeWithContentDescription("返回上一页").performScrollTo().performClick()
        ui.onNodeWithText("助行", useUnmergedTree = true).performClick()
        ui.runOnIdle { c.stopNavigation() }
        ui.onNodeWithText("查看画面").performScrollTo().performClick()
        ui.onNodeWithText("识别当前画面文字").performScrollTo()
        screenshot("06-stream")
        ui.runOnIdle { c.stopNavigation() }
        ui.waitUntil(3000) { c.state.value.navigation.phase == "ended" }
        assertEquals("", c.state.value.guidance)
    }

    @Test
    fun largeTextAndSettingsPersist() {
        ui.onNodeWithText("我的", useUnmergedTree = true).performClick()
        screenshot("07-mine")
        ui.onNodeWithText("无障碍设置").performScrollTo().performClick()
        ui.onNodeWithText("特大字号").performScrollTo().performClick()
        ui.waitUntil(3000) { c.prefs.value.large == 1.4f }
        ui.onNodeWithText("高对比度").performScrollTo().performClick()
        ui.waitUntil(3000) { c.prefs.value.contrast }
        screenshot("08-large-contrast")
        ui.onNodeWithContentDescription("返回上一页").performScrollTo().performClick()
        ui.onNodeWithText("记录与资料").performScrollTo().performClick()
        ui.onNodeWithText("搜索记录内容").performTextInput("导航")
        screenshot("09-history")
    }

    @Test
    fun localDetectionModelLoads() {
        val image =
            Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val result = runBlocking { LocalRecognition(ui.activity).find(image) }
        assertTrue("空白图不能生成可靠物品", result.isEmpty())
        val sample =
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("cat_and_dog.jpg")
                .use { BitmapFactory.decodeStream(it) }
        val findings = runBlocking { LocalRecognition(ui.activity).find(sample) }
        assertTrue(findings.toString(), findings.any { it.label in setOf("cat", "dog") })
        ui.runOnIdle {
            c.setImage(sample, "官方模型测试图片")
            c.recognize("find")
        }
        ui.waitUntil(15000) { !c.state.value.busy }
        assertTrue(
            c.state.value.result,
            c.state.value.result.contains("猫") || c.state.value.result.contains("狗"),
        )
        ui.onNodeWithText("助手", useUnmergedTree = true).performClick()
        ui.onNodeWithText("识别结果").performScrollTo()
        screenshot("10-local-finding")
    }

    @Test
    fun reconnectRouteReplacementAndBackgroundStream() {
        ui.runOnIdle {
            c.mode(true)
            c.connect("ws://10.0.2.2:8766/link", "instrumentation-test-token")
        }
        ui.waitUntil(20000) { c.state.value.frame?.source == "设备测试流" }
        ui.runOnIdle {
            c.demoStart()
            c.demoStep(2)
        }
        ui.waitUntil(10000) { c.state.value.guidance.contains("受阻") }
        val old = c.state.value.navigation.routeVersion
        ui.runOnIdle { c.demoStep(3) }
        ui.waitUntil(5000) {
            c.state.value.navigation.routeVersion > old && c.state.value.guidance.contains("重新规划")
        }
        ui.runOnIdle {
            c.disconnect()
            c.connect("ws://10.0.2.2:8766/link", "instrumentation-test-token")
        }
        ui.waitUntil(20000) {
            c.state.value.connection == "已连接测试接收端" && c.state.value.delivery.contains("接收")
        }
        val frameId = c.state.value.frame?.id
        val deadline = System.currentTimeMillis() + 60000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1000)
            assertEquals("已连接测试接收端", c.state.value.connection)
            assertTrue(System.currentTimeMillis() - (c.state.value.frame?.receivedAt ?: 0) < 5000)
        }
        assertNotEquals(frameId, c.state.value.frame?.id)
        ui.runOnIdle { c.stopNavigation() }
        assertEquals("ended", c.state.value.navigation.phase)
    }
}
