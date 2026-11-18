package cn.yuntong.companion.domain

const val SOS_CANCEL_SECONDS = 10

enum class ConfirmationStep { AWAIT_REPEAT, CONFIRMED, TOO_SOON }

/** Two distinct completed utterances inside a short monotonic window. */
class VoiceSosConfirmation(
    val windowMs: Long = 10_000L,
    private val minimumGapMs: Long = 1_200L,
) {
    private var firstAt: Long? = null
    private var lastAt: Long? = null

    fun offer(nowMs: Long): ConfirmationStep {
        val previous = lastAt
        if (previous != null && nowMs >= previous && nowMs - previous < minimumGapMs)
            return ConfirmationStep.TOO_SOON
        lastAt = nowMs
        val first = firstAt
        if (first != null && nowMs >= first && nowMs - first <= windowMs) {
            firstAt = null
            return ConfirmationStep.CONFIRMED
        }
        firstAt = nowMs
        return ConfirmationStep.AWAIT_REPEAT
    }

    fun reset() { firstAt = null }
}
