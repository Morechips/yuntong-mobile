package cn.yuntong.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceSosConfirmationTest {
    @Test fun requiresTwoSeparatedUtterances() {
        val gate = VoiceSosConfirmation()
        assertEquals(ConfirmationStep.AWAIT_REPEAT, gate.offer(1_000))
        assertEquals(ConfirmationStep.TOO_SOON, gate.offer(1_050))
        assertEquals(ConfirmationStep.CONFIRMED, gate.offer(2_500))
    }

    @Test fun lateSecondUtteranceStartsNewConfirmation() {
        val gate = VoiceSosConfirmation()
        assertEquals(ConfirmationStep.AWAIT_REPEAT, gate.offer(1_000))
        assertEquals(ConfirmationStep.AWAIT_REPEAT, gate.offer(12_000))
    }

    @Test fun cancelClearsFirstWithoutAcceptingLateDuplicate() {
        val gate = VoiceSosConfirmation()
        assertEquals(ConfirmationStep.AWAIT_REPEAT, gate.offer(1_000))
        gate.reset()
        assertEquals(ConfirmationStep.TOO_SOON, gate.offer(1_050))
        assertEquals(ConfirmationStep.AWAIT_REPEAT, gate.offer(2_500))
    }
}
