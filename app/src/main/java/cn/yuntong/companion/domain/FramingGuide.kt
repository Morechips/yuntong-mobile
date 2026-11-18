package cn.yuntong.companion.domain

data class TextBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

data class FramingHint(val code: String, val text: String)

/** All coordinates are normalized, upright camera image coordinates, never walking directions. */
fun framingHint(brightness: Double, boxes: List<TextBounds>): FramingHint {
    if (brightness < 38) return FramingHint("dark", "画面偏暗，请增加照明，再保持手机稳定。")
    if (brightness > 246 && boxes.isEmpty()) return FramingHint("bright", "画面很亮且未检测到文字，请避开反光后重试。")
    if (boxes.isEmpty()) return FramingHint("empty", "暂未检测到文字，请缓慢调整镜头角度或距离。")
    if (boxes.any { it.left < .035f || it.top < .035f || it.right > .965f || it.bottom > .965f })
        return FramingHint("edge", "文字靠近画面边缘，请把手机稍微远离纸面，尽量收入完整文字。")
    if (boxes.map { it.bottom - it.top }.average() < .025)
        return FramingHint("small", "检测到的文字较小，可以把手机稍微靠近，再保持稳定。")
    return FramingHint("text", "已检测到文字，可以尝试拍照。是否完整仍需核对识别结果。")
}

/** Require two observations; announce changes at most once per four seconds. */
class FramingStability {
    private var candidate = ""
    private var count = 0
    private var spoken = ""
    private var lastAt = -10000L

    fun update(hint: FramingHint, now: Long): Boolean {
        if (candidate == hint.code) count++
        else {
            candidate = hint.code
            count = 1
        }
        if (count < 2 || hint.code == spoken || now - lastAt < 4000) return false
        spoken = hint.code
        lastAt = now
        return true
    }
}
