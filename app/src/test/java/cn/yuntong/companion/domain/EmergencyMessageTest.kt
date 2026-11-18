package cn.yuntong.companion.domain

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class EmergencyMessageTest {
    @Test fun manualMessageStatesUrgencyAndLocation() {
        val body = manualEmergencyMessage("云瞳位置：30.123456, 120.123456（系统定位），定位时间：12:30")
        assertTrue(body.contains("紧急求助"))
        assertTrue(body.contains("尽快给我打电话"))
        assertTrue(body.contains("30.123456, 120.123456"))
    }

    @Test fun missingLocationIsExplicit() {
        val body = manualEmergencyMessage(null)
        assertTrue(body.contains("当前位置暂不可用"))
        assertFalse(body.contains("位置：0"))
    }

    @Test fun fallMessageIdentifiesTheTrigger() {
        val body = fallEmergencyMessage("30.123456, 120.123456")
        assertTrue(body.contains("跌倒求助"))
        assertTrue(body.contains("眼镜报告"))
    }
}
