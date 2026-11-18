package cn.yuntong.companion

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.rule.GrantPermissionRule
import java.io.File
import java.time.LocalDate
import org.junit.*
import org.junit.Assert.*

class HomeFlowTest {
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
            c.bool("cooperative", false)
            c.bool("contrast", false)
            c.number("large", 1f)
            c.saveChecks(emptySet(), LocalDate.now().toString())
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
            c.bool("cooperative", false)
            c.bool("contrast", false)
            c.number("large", 1f)
        }
    }

    private fun shot(name: String) {
        ui.waitForIdle()
        val file = File(ui.activity.getExternalFilesDir("qa-home"), "$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use {
            ui.onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun readyChecklistAndFrameEntry() {
        ui.onNodeWithText("今天想去哪里？").assertIsDisplayed()
        ui.onNodeWithText("选择目的地").assertIsDisplayed()
        ui.onNodeWithText("未连接").assertDoesNotExist()
        shot("01-home-ready")
        ui.onNodeWithText("出发检查").performScrollTo().performClick()
        ui.onNodeWithText("辅助用具准备好").performScrollTo().performClick()
        ui.waitUntil(3000) { "0" in c.prefs.value.checks }
        ui.onNodeWithContentDescription("返回上一页").performScrollTo().performClick()
        ui.onNodeWithText("手动检查 · 已完成 1/4 项").assertExists()
        ui.onNodeWithText("查看画面").performScrollTo().performClick()
        ui.onNodeWithText("选择本地图片").performScrollTo().assertHasClickAction()
        ui.onNodeWithContentDescription("返回上一页").performScrollTo().performClick()
        ui.onNodeWithText("今天想去哪里？").assertExists()
        ui.onNodeWithText("拍照识字").performScrollTo().performClick()
        ui.onNodeWithText("手机取景").performScrollTo().assertExists()
        ui.onNodeWithContentDescription("返回上一页").performScrollTo().performClick()
        ui.onNodeWithText("今天想去哪里？").assertExists()
    }

    @Test
    fun favoriteRequiresDestinationConfirmation() {
        val json =
            org.json
                .JSONObject()
                .put("id", "home-test-place")
                .put("name", "测试图书馆")
                .put("address", "测试地址，不作真实导航")
                .put("lat", 31.23)
                .put("lon", 121.47)
                .toString()
        ui.runOnIdle { c.save("地点", "测试图书馆", json, "首页仪器测试") }
        ui.waitUntil(3000) { c.records.value.any { it.source == "首页仪器测试" } }
        try {
            ui.onNodeWithText("测试图书馆").performScrollTo().performClick()
            ui.onNodeWithText("确认目的地").performScrollTo().assertExists()
            assertEquals("测试图书馆", c.state.value.selected?.name)
            assertFalse(c.state.value.navigation.phase == "active")
            ui.onNodeWithText("确认并规划步行路线").performScrollTo().assertHasClickAction()
        } finally {
            ui.runOnIdle {
                c.records.value.filter { it.source == "首页仪器测试" }.forEach { c.delete(it.id) }
            }
        }
    }

    @Test
    fun runningPauseModeGuardAndEndConfirmation() {
        ui.runOnIdle {
            c.demoStart()
            c.demoStep(1)
        }
        ui.onNodeWithText("前方50米向左转").assertExists()
        shot("02-home-running")
        ui.onNodeWithText("暂停导航").assertIsDisplayed().performClick()
        ui.waitUntil(3000) { c.state.value.navigation.phase == "paused" }
        ui.onNodeWithText("前方50米向左转").assertDoesNotExist()
        shot("03-home-paused")
        ui.onNodeWithText("手机独立").performClick()
        ui.onNodeWithText("眼镜协同使用").assertIsNotEnabled()
        ui.onNodeWithText("关闭").performClick()
        ui.onNodeWithText("恢复导航").performClick()
        ui.onNodeWithText("结束本次导航").performClick()
        ui.onNodeWithText("取消").performClick()
        assertEquals("active", c.state.value.navigation.phase)
        ui.onNodeWithText("结束本次导航").performClick()
        ui.onNodeWithText("确认").performClick()
        ui.waitUntil(3000) { c.state.value.navigation.phase == "ended" }
        ui.onNodeWithText("今天想去哪里？").assertExists()
    }

    @Test
    fun cooperativeExpiryAndDisconnect() {
        ui.runOnIdle {
            c.mode(true)
            c.connect("ws://10.0.2.2:8766/link", "instrumentation-test-token")
        }
        ui.waitUntil(20000) { c.state.value.connection == "已连接测试接收端" && c.prefs.value.cooperative }
        ui.runOnIdle {
            c.demoStart()
            c.demoStep(2)
        }
        ui.waitUntil(10000) { c.state.value.guidance.contains("转向受阻") }
        ui.onNodeWithText("模拟设备：转向受阻，请停下并确认周围环境。").assertExists()
        ui.onNodeWithText("向左转").assertDoesNotExist()
        shot("04-home-cooperative")
        ui.waitUntil(10000) { c.state.value.guidanceExpired }
        ui.onNodeWithText("设备提示已过期").assertExists()
        shot("05-home-expired")
        ui.runOnIdle { c.disconnect() }
        ui.onNodeWithText("设备连接已断开").assertExists()
        ui.onNodeWithText("重新连接设备").assertHasClickAction()
        shot("06-home-disconnected")
    }

    @Test
    fun largeContrastKeepsControlsReachable() {
        ui.runOnIdle {
            c.bool("contrast", true)
            c.number("large", 1.4f)
        }
        ui.waitUntil(5000) { c.prefs.value.contrast && c.prefs.value.large == 1.4f }
        ui.onNodeWithText("选择目的地").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("出发检查").performScrollTo().performClick()
        ui.onNodeWithText("已确认周围环境").performScrollTo().assertIsDisplayed()
        ui.onNodeWithContentDescription("返回上一页").performScrollTo().performClick()
        ui.runOnIdle { c.demoStart() }
        ui.waitUntil(3000) { c.state.value.navigation.phase == "active" }
        ui.waitUntil(3000) { ui.onAllNodesWithText("暂停导航").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("暂停导航").performScrollTo().assertIsDisplayed().performClick()
        ui.waitUntil(3000) { c.state.value.navigation.phase == "paused" }
        ui.onNodeWithText("恢复导航").performScrollTo().assertIsDisplayed()
        shot("07-home-large-contrast")
        ui.onNodeWithText("结束本次导航").performScrollTo().performClick()
        ui.onNodeWithText("确认").performClick()
        assertEquals("ended", c.state.value.navigation.phase)
    }
}
