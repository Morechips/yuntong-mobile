package cn.yuntong.companion.domain

import android.graphics.Bitmap
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

data class ImageFrame(
    val id: String,
    val bitmap: Bitmap,
    val source: String,
    val receivedAt: Long,
    val analysis: String = "",
) {
    fun isDeviceImage() = source in setOf("设备测试流", "眼镜实时画面", "眼镜新画面")
}

interface FrameSource {
    val frame: StateFlow<ImageFrame?>

    fun stop()
}

interface PerceptionProvider {
    fun accept(frameId: String, result: JSONObject): Boolean
}

interface NavigationProvider {
    fun stop()

    fun pause()

    fun resume()
}

interface RecognitionProvider {
    suspend fun recognize(frame: ImageFrame, prompt: String): String
}
