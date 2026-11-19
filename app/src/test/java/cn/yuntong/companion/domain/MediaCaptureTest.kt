package cn.yuntong.companion.domain

import org.junit.Assert.*
import org.junit.Test

class MediaCaptureTest {
    @Test fun onlyTheCurrentRequestCanClaimAndDisplayAFrame() {
        val capture = MediaCapture()
        assertTrue(capture.begin("one"))
        assertFalse(capture.begin("two"))
        assertFalse(capture.claimFrame("legacy"))
        assertTrue(capture.claimFrame("one"))
        assertFalse(capture.claimFrame("one"))
        assertTrue(capture.finish("one"))
        assertNull(capture.requestId)
    }

    @Test fun lateDecodeCannotCompleteAfterTimeoutOrSourceSwitch() {
        val capture = MediaCapture()
        assertTrue(capture.begin("one"))
        assertTrue(capture.claimFrame("one"))
        assertTrue(capture.finish("one"))
        assertTrue(capture.begin("two"))
        assertFalse(capture.finish("one"))
        capture.cancel()
        assertFalse(capture.claimFrame("two"))
    }
}
