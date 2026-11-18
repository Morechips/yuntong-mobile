package cn.yuntong.companion.domain

import org.junit.Assert.*
import org.junit.Test

class SpeechSessionTest {
    @Test
    fun pauseResumesCurrentSegmentAndRejectsLateCompletion() {
        val s = SpeechSession()
        s.start("第一段。第二段。", "助手")
        val old = s.token
        s.pause()
        assertFalse(s.done(old))
        assertEquals("paused", s.state.phase)
        assertTrue(s.resume())
        assertEquals("第一段。", s.text)
        assertFalse(s.done(old))
        assertTrue(s.done(s.token))
        assertEquals(2, s.state.part)
    }

    @Test
    fun stoppedOrReplacedSpeechCannotResume() {
        val s = SpeechSession()
        s.start("旧画面。", "助手")
        val old = s.token
        s.stop()
        assertFalse(s.resume())
        assertFalse(s.done(old))
        s.start("设备受阻提示。", "设备助行")
        assertFalse(s.done(old))
        assertEquals("设备助行", s.state.source)
    }

    @Test
    fun completionAndErrorAreDistinct() {
        val s = SpeechSession()
        s.start("内容。", "资料")
        assertFalse(s.done(s.token))
        assertEquals("completed", s.state.phase)
        assertFalse(s.resume())
        s.start("新内容。", "助手")
        s.fail("缺少引擎")
        assertEquals("error", s.state.phase)
        assertFalse(s.resume())
    }

    @Test
    fun longTextStaysWithinTtsLimits() {
        val text = "字".repeat(6001)
        val s = SpeechSession()
        s.start(text, "助手")
        assertEquals(3, s.state.total)
        val all = StringBuilder()
        do {
            assertTrue(s.text.length <= 2500)
            all.append(s.text)
        } while (s.done(s.token))
        assertEquals(text, all.toString())
    }
}
