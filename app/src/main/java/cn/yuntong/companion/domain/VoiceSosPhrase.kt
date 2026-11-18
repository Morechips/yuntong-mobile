package cn.yuntong.companion.domain

enum class VoiceSosCommand { HELP, CANCEL, NONE }

/** A short completed utterance is only a candidate; service requires a second utterance. */
fun voiceSosCommand(hypothesis: String): VoiceSosCommand {
    val normalized = hypothesis.filter { it.isLetterOrDigit() }
    return when {
        normalized.contains("取消求助") -> VoiceSosCommand.CANCEL
        normalized.length <= 8 && normalized.endsWith("紧急求助") -> VoiceSosCommand.HELP
        else -> VoiceSosCommand.NONE
    }
}
