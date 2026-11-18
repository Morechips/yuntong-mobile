package cn.yuntong.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSosModeTest {
    @Test fun practiceNeverCommunicatesOutsidePhone() {
        assertTrue(sosActions(VoiceSosMode.PRACTICE).isEmpty())
    }

    @Test fun liveRequestsCallBeforeBestEffortSms() {
        assertEquals(listOf(SosAction.CALL_CONTACT, SosAction.REQUEST_SMS), sosActions(VoiceSosMode.LIVE))
    }
}
