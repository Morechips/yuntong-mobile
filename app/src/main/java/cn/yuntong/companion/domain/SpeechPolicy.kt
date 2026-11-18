package cn.yuntong.companion.domain

/** Automatic navigation remains audible with a screen reader. Never queue old directions. */
class SpeechPolicy {
    private var lastKey = ""
    private var lastAt = Long.MIN_VALUE

    fun allow(
        text: String,
        source: String,
        automatic: Boolean,
        screenReader: Boolean,
        now: Long,
    ): Boolean {
        if (text.isBlank()) return false
        if (!automatic) return true
        val essential = source in setOf("地图导航", "设备助行", "状态提醒")
        if (screenReader && !essential) return false
        val key = "$source:$text"
        if (key == lastKey && now >= lastAt && now - lastAt < 6000) return false
        lastKey = key
        lastAt = now
        return true
    }

    fun reset() {
        lastKey = ""
        lastAt = Long.MIN_VALUE
    }
}
