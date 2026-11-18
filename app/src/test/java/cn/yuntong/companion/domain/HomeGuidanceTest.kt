package cn.yuntong.companion.domain

import org.junit.Assert.*
import org.junit.Test

class HomeGuidanceTest {
    private val active = NavigationState(phase = "active", instruction = "立即右转")

    @Test
    fun cooperativeNeverFallsBackToMapTurn() {
        val waiting = homeGuidance(active, true, true, "", false, "")
        assertFalse(waiting.available)
        assertFalse(waiting.text.contains("右转"))
        val blocked = homeGuidance(active, true, true, "转向受阻，请等待", false, "")
        assertEquals("转向受阻，请等待", blocked.text)
        assertTrue(blocked.available)
    }

    @Test
    fun pausedDisconnectedAndEndedNeverShowCachedGuidance() {
        assertEquals(
            "导航已暂停",
            homeGuidance(active.copy(phase = "paused"), true, true, "右转", false, "").text,
        )
        assertEquals("设备连接已断开", homeGuidance(active, true, false, "右转", false, "").text)
        assertEquals(
            "尚未开始导航",
            homeGuidance(active.copy(phase = "ended"), true, true, "右转", false, "").text,
        )
    }

    @Test
    fun expiredAndWeakLocationAreExplicit() {
        assertEquals("设备提示已过期", homeGuidance(active, true, true, "", true, "").text)
        for (cooperative in listOf(true, false)) {
            val view = homeGuidance(active, cooperative, true, "右转", false, "定位信号较弱")
            assertEquals("定位信号较弱", view.text)
            assertFalse(view.available)
        }
    }

    @Test
    fun standaloneDoesNotRequireGlassesAndKeepsSourceText() {
        val view = homeGuidance(active, false, false, "旧设备结果", false, "")
        assertEquals(active.instruction, view.text)
        assertTrue(view.available)
        assertEquals("高德步行导航提示", view.detail)
    }
}
