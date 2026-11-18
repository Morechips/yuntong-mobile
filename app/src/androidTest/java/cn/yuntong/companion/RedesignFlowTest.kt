package cn.yuntong.companion

import android.Manifest
import android.graphics.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import cn.yuntong.companion.navigation.Place
import java.io.File
import org.junit.*
import org.junit.Assert.*

class RedesignFlowTest {
    @get:Rule
    val permissions =
        GrantPermissionRule.grant(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.CAMERA,
        )
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val c
        get() = (ui.activity.application as YuntongApplication).controller

    @Before
    fun setup() {
        ui.runOnIdle {
            c.stopAll()
            c.chooseAssistant("")
            c.bool("cooperative", false)
            c.bool("contrast", false)
            c.number("large", 1f)
            c.notice("欢迎使用云瞳")
        }
        ui.waitUntil(5000) {
            !c.prefs.value.cooperative && !c.prefs.value.contrast && c.prefs.value.large == 1f
        }
    }

    @After
    fun finish() {
        ui.runOnIdle {
            c.stopAll()
            c.chooseAssistant("")
            c.bool("contrast", false)
            c.number("large", 1f)
            c.records.value.filter { it.source == "界面验证" }.forEach { c.delete(it.id) }
        }
    }

    private fun tab(name: String) = ui.onNodeWithText(name, useUnmergedTree = true).performClick()

    private fun click(name: String) = ui.onNodeWithText(name).performScrollTo().performClick()

    private fun back() = ui.onNodeWithContentDescription("返回上一页").performScrollTo().performClick()

    private fun shot(name: String) {
        ui.waitForIdle()
        val f = File(ui.activity.getExternalFilesDir("qa-product"), "$name.png")
        f.parentFile?.mkdirs()
        f.outputStream().use {
            ui.onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun textImage(): Bitmap =
        Bitmap.createBitmap(1100, 500, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).apply {
                drawColor(Color.WHITE)
                drawText(
                    "云瞳 图书馆入口",
                    60f,
                    180f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = 80f
                    },
                )
                drawText(
                    "开放时间 8点到18点",
                    60f,
                    330f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = 70f
                    },
                )
            }
        }

    @Test
    fun assistantTaskOcrResultAndCancelUpload() {
        tab("助手")
        shot("01-assistant-tasks")
        click("读文字")
        ui.runOnIdle { c.setImage(textImage(), "界面验证文字图") }
        click("识别中文文字")
        ui.waitUntil(40000) { !c.state.value.busy && c.state.value.result.isNotBlank() }
        assertTrue(c.state.value.result, c.state.value.result.contains("图书馆"))
        ui.onNodeWithText("识别结果").performScrollTo().assertIsDisplayed()
        shot("02-ocr-result")
        click("朗读内容")
        ui.waitUntil(5000) { c.speech.state.value.phase != "idle" }
        assertEquals("助手 · 读文字", c.speech.state.value.source)
        click("返回助手功能")
        assertEquals("idle", c.speech.state.value.phase)
        click("场景问答")
        click("确认画面并提问")
        ui.onNodeWithText("确认上传").assertIsEnabled()
        ui.runOnIdle { c.setImage(textImage(), "替换后的图像") }
        ui.onNodeWithText("确认上传").assertIsNotEnabled()
        ui.onNodeWithText("取消").performClick()
        assertFalse(c.state.value.busy)
    }

    @Test
    fun localTargetFilteringAndCaptureReturn() {
        tab("助手")
        click("找物品")
        click(if (c.state.value.frame == null) "拍照或选择图片" else "更换画面")
        ui.onNodeWithText("打开相机").assertExists()
        back()
        ui.onNodeWithText("查找目标：全部支持类别").assertExists()
        val sample =
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("cat_and_dog.jpg")
                .use { BitmapFactory.decodeStream(it) }
        ui.runOnIdle { c.setImage(sample, "官方模型测试图片") }
        click("查找目标：全部支持类别")
        ui.onNodeWithText("搜索物品类别").performTextInput("狗")
        ui.onNode(hasText("狗") and !hasSetTextAction()).performClick()
        click("开始查找")
        ui.waitUntil(20000) { !c.state.value.busy && c.state.value.result.isNotBlank() }
        assertTrue(c.state.value.result, c.state.value.findings.isNotEmpty())
        assertTrue(c.state.value.findings.all { it.label == "dog" })
        ui.onNodeWithText("识别结果").performScrollTo()
        shot("03-target-result")
    }

    @Test
    fun destinationStagesAndNavigationState() {
        tab("导航")
        shot("04-navigation-search")
        ui.onNodeWithText("地点名称或地址").performTextInput("图书馆")
        ui.runOnIdle { c.select(Place("sample-place", "图书馆（测试）", "测试地址，不作真实导航", 31.23, 121.47)) }
        ui.onNodeWithText("确认目的地").assertExists()
        shot("05-destination-confirm")
        assertFalse(c.state.value.navigation.phase == "active")
        click("返回地点搜索")
        ui.onNodeWithText("地点名称或地址").assertTextContains("图书馆")
        ui.runOnIdle {
            c.demoStart()
            c.demoStep(1)
        }
        ui.onNodeWithText("模拟演练 · 进行中").assertExists()
        click("查看路线步骤")
        ui.onNodeWithText("1. 沿示例道路直行，200米").assertExists()
        click("暂停导航")
        ui.waitUntil(3000) { c.state.value.navigation.phase == "paused" }
        click("结束导航")
        ui.onNodeWithText("取消").performClick()
        assertEquals("paused", c.state.value.navigation.phase)
        click("结束导航")
        ui.onNodeWithText("确认").performClick()
        ui.onNodeWithText("地点名称或地址").assertExists()
        assertTrue(c.state.value.route.isBlank())
    }

    @Test
    fun savedDeviceReconnectAndNestedBack() {
        val oldAddress = c.secrets.get("deviceAddress")
        val oldToken = c.secrets.get("deviceToken")
        val oldName = c.secrets.get("deviceName")
        try {
            tab("我的")
            click("设备与连接")
            click("连接设置")
            ui.onNodeWithText("设备名称").performTextReplacement("演练电脑")
            ui.onNodeWithText("连接地址").performTextReplacement("ws://10.0.2.2:8766/link")
            ui.onNodeWithText("连接密钥").performTextReplacement("instrumentation-test-token")
            click("保存连接设置")
            assertNotEquals("已连接测试接收端", c.state.value.connection)
            ui.onNodeWithText("演练电脑").assertExists()
            click("连接已保存设备")
            ui.waitUntil(20000) { c.state.value.connection == "已连接测试接收端" }
            ui.onNodeWithText("我的设备").performScrollTo()
            shot("06-device-saved")
            click("佩戴与准备")
            ui.onNodeWithText("请求设备自检").assertIsNotEnabled()
            back()
            ui.onNodeWithText("我的设备").assertExists()
            click("断开连接")
            assertNotEquals("已连接测试接收端", c.state.value.connection)
        } finally {
            ui.runOnIdle {
                c.disconnect()
                c.secrets.put("deviceAddress", oldAddress)
                c.secrets.put("deviceToken", oldToken)
                c.secrets.put("deviceName", oldName)
            }
        }
    }

    @Test
    fun recordSearchDetailAndSafeManagement() {
        ui.runOnIdle {
            c.save("收藏", "界面验证记录甲", "可以朗读的收藏内容", "界面验证")
            c.save("导航", "界面验证记录乙", "已结束的模拟出行", "界面验证")
        }
        ui.waitUntil(3000) { c.records.value.count { it.source == "界面验证" } == 2 }
        tab("我的")
        click("记录与资料")
        ui.onNodeWithText("搜索记录内容").performTextInput("界面验证")
        shot("07-records-list")
        click("界面验证记录甲")
        ui.onNodeWithText("可以朗读的收藏内容").assertExists()
        click("删除这条记录")
        ui.onNodeWithText("取消").performClick()
        assertTrue(c.records.value.any { it.title == "界面验证记录甲" })
        click("返回记录列表")
        click("管理资料")
        ui.onNodeWithText("导出并分享记录").assertExists()
        click("清空全部记录")
        ui.onNodeWithText("取消").performClick()
        assertEquals(2, c.records.value.count { it.source == "界面验证" })
        back()
        ui.onNodeWithText("搜索记录内容").assertTextContains("界面验证")
        click("分类：全部")
        ui.onNodeWithText("收藏", useUnmergedTree = true).performClick()
        ui.onNodeWithText("界面验证记录乙").assertDoesNotExist()
    }

    @Test
    fun largeTextFourPagesRemainReachable() {
        ui.runOnIdle {
            c.number("large", 1.4f)
            c.bool("contrast", true)
        }
        ui.waitUntil(5000) { c.prefs.value.large == 1.4f && c.prefs.value.contrast }
        tab("助手")
        click("场景问答")
        ui.onNodeWithText("想了解画面的什么信息").performScrollTo().assertIsDisplayed()
        tab("导航")
        ui.onNodeWithText("地点名称或地址").performScrollTo().assertIsDisplayed()
        tab("我的")
        click("设备与连接")
        click("连接设置")
        ui.onNodeWithText("连接密钥").performScrollTo().assertIsDisplayed()
        back()
        back()
        click("记录与资料")
        click("管理资料")
        ui.onNodeWithText("导出并分享记录").performScrollTo().assertIsDisplayed()
        shot("08-large-record-management")
    }
}
