package cn.yuntong.companion.domain

data class SpeechState(
    val phase: String = "idle",
    val source: String = "",
    val part: Int = 0,
    val total: Int = 0,
    val error: String = "",
    val content: String = "",
)

/** Tokens reject callbacks from cancelled or replaced utterances. */
class SpeechSession {
    var state = SpeechState()
        private set

    private var generation = 0L
    private var segments = emptyList<String>()
    private var index = 0
    val token
        get() = "$generation:$index"

    val text
        get() = segments.getOrNull(index).orEmpty()

    fun start(text: String, source: String) {
        generation++
        segments =
            text
                .split(Regex("(?<=[。！？\\n])"))
                .filter { it.isNotBlank() }
                .flatMap { it.chunked(2500) }
        index = 0
        state =
            SpeechState(
                if (segments.isEmpty()) "idle" else "speaking",
                source,
                if (segments.isEmpty()) 0 else 1,
                segments.size,
                content = text,
            )
    }

    fun done(id: String?): Boolean {
        if (state.phase != "speaking" || id != token) return false
        if (index + 1 < segments.size) {
            index++
            state = state.copy(part = index + 1)
            return true
        }
        state = state.copy(phase = "completed")
        return false
    }

    fun pause() {
        if (state.phase == "speaking") {
            generation++
            state = state.copy(phase = "paused")
        }
    }

    fun resume(): Boolean {
        if (state.phase != "paused" || segments.isEmpty()) return false
        generation++
        state = state.copy(phase = "speaking")
        return true
    }

    fun stop() {
        generation++
        segments = emptyList()
        state = SpeechState()
    }

    fun fail(message: String) {
        generation++
        segments = emptyList()
        state = state.copy(phase = "error", error = message)
    }
}
