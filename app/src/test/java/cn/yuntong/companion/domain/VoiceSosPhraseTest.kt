package cn.yuntong.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceSosPhraseTest {
    @Test fun acceptsShortCompletedHelpWithOrWithoutProductName() {
        assertEquals(VoiceSosCommand.HELP, voiceSosCommand("云 瞳 紧 急 求 助"))
        assertEquals(VoiceSosCommand.HELP, voiceSosCommand("云 同 紧 急 求 助"))
        assertEquals(VoiceSosCommand.HELP, voiceSosCommand("紧急求助"))
        assertEquals(VoiceSosCommand.HELP, voiceSosCommand("您同紧急求助"))
    }

    @Test fun ignoresOrdinaryLongSpeech() {
        assertEquals(VoiceSosCommand.NONE, voiceSosCommand("我们正在讨论紧急求助功能"))
        assertEquals(VoiceSosCommand.NONE, voiceSosCommand("云瞳请帮忙"))
    }

    @Test fun cancellationWinsEvenIfHelpIsRepeated() {
        assertEquals(VoiceSosCommand.CANCEL, voiceSosCommand("云瞳紧急求助，取消求助"))
    }
}
