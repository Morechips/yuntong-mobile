package cn.yuntong.companion.domain

import org.junit.Assert.*
import org.junit.Test

class AccessibilityPolicyTest {
    @Test
    fun screenReaderKeepsNavigationAndConnectionAlerts() {
        for (source in listOf("地图导航", "设备助行", "状态提醒")) assertTrue(
            SpeechPolicy().allow("有效信息", source, true, true, 100)
        )
        assertFalse(SpeechPolicy().allow("一般界面内容", "取景引导", true, true, 100))
        assertTrue(SpeechPolicy().allow("主动重复", "取景引导", false, true, 100))
    }

    @Test
    fun repeatedDeviceFramesDoNotRestartSpeechButChangedAlertsDo() {
        val p = SpeechPolicy()
        assertTrue(p.allow("转向受阻", "设备助行", true, true, 100))
        assertFalse(p.allow("转向受阻", "设备助行", true, true, 1100))
        assertTrue(p.allow("连接中断", "状态提醒", true, true, 1200))
        assertTrue(p.allow("转向受阻", "设备助行", false, true, 1300))
        p.reset()
        assertTrue(p.allow("连接中断", "状态提醒", true, true, 1400))
    }

    @Test
    fun framingNeverPromisesCompletePageAndDistinguishesMissingText() {
        val center = listOf(TextBounds(.2f, .3f, .8f, .4f))
        assertEquals("dark", framingHint(10.0, center).code)
        assertEquals("empty", framingHint(160.0, emptyList()).code)
        assertEquals("edge", framingHint(160.0, listOf(TextBounds(0f, .2f, .5f, .3f))).code)
        assertTrue(framingHint(160.0, center).text.contains("仍需核对"))
        assertEquals("small", framingHint(160.0, listOf(TextBounds(.2f, .2f, .8f, .21f))).code)
    }

    @Test
    fun noisyFramingDoesNotChatterAndStableChangeIsReported() {
        val p = FramingStability()
        val dark = framingHint(0.0, emptyList())
        val empty = framingHint(140.0, emptyList())
        assertFalse(p.update(dark, 0))
        assertFalse(p.update(empty, 1000))
        assertTrue(p.update(empty, 2000))
        assertFalse(p.update(empty, 8000))
        assertFalse(p.update(dark, 9000))
        assertTrue(p.update(dark, 10000))
    }

    @Test
    fun importedProfilesValidateBeforeSaving() {
        val good =
            """{"schema":"yuntong-test-connection-v1","name":"电脑测试端","address":"ws://192.168.1.2:8766/link","token":"example-test-token"}"""
        assertEquals("电脑测试端", ConnectionProfile.parse(good).name)
        for (bad in listOf("{}", good.replace("192.168.1.2", "8.8.8.8"), "x".repeat(17000))) {
            assertTrue(runCatching { ConnectionProfile.parse(bad) }.isFailure)
        }
    }
}
