package cn.yuntong.companion.recognition

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.ObjectDetector

data class Finding(
    val label: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

class LocalRecognition(private val context: Context) {
    private val ocr = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private var detector: ObjectDetector? = null
    private val detectionLock = Mutex()

    suspend fun read(bitmap: Bitmap): String = suspendCancellableCoroutine { c ->
        ocr.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener {
                if (c.isActive) c.resume(it.text)
            }
            .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
    }

    suspend fun find(bitmap: Bitmap): List<Finding> =
        withContext(Dispatchers.Default) {
            detectionLock.withLock {
                try {
                    val d =
                        detector
                            ?: ObjectDetector.createFromFileAndOptions(
                                    context,
                                    "efficientdet-lite0.tflite",
                                    ObjectDetector.ObjectDetectorOptions.builder()
                                        .setScoreThreshold(.45f)
                                        .setMaxResults(10)
                                        .build(),
                                )
                                .also { detector = it }
                    d.detect(TensorImage.fromBitmap(bitmap)).mapNotNull { detection ->
                        val c =
                            detection.categories.maxByOrNull { it.score } ?: return@mapNotNull null
                        val b = detection.boundingBox
                        Finding(
                            c.label,
                            c.score,
                            b.left / bitmap.width,
                            b.top / bitmap.height,
                            b.right / bitmap.width,
                            b.bottom / bitmap.height,
                        )
                    }
                } catch (e: LinkageError) {
                    throw IllegalStateException("此设备不支持本地找物推理，请使用联网识别", e)
                }
            }
        }
}

class CloudRecognition(private val context: Context) {
    suspend fun ask(
        bitmap: Bitmap,
        question: String,
        base: String,
        model: String,
        key: String,
        mobileData: Boolean,
    ): String =
        withContext(Dispatchers.IO) {
            val uri = URI(base)
            require(uri.scheme == "https" && uri.host != null && uri.userInfo == null &&
                uri.query == null && uri.fragment == null) {
                "请填写不含查询参数或凭据的 HTTPS 服务地址"
            }
            require(key.isNotBlank() && model.isNotBlank()) { "请先配置服务地址、模型和密钥" }
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val internet =
                cm.allNetworks.firstOrNull { n ->
                    cm.getNetworkCapabilities(n)?.let {
                        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                            (mobileData || !it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                    } == true
                } ?: throw IllegalStateException("没有允许使用的互联网连接；可检查移动数据设置")
            val scale = 1280f / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1280)
            val small =
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            val out = ByteArrayOutputStream()
            try {
                check(small.compress(Bitmap.CompressFormat.JPEG, 85, out)) { "无法压缩当前画面" }
            } finally {
                if (small !== bitmap) small.recycle()
            }
            val image =
                "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            val content =
                JSONArray()
                    .put(
                        JSONObject()
                            .put("type", "text")
                            .put("text", question + "。请用中文描述画面中的信息；不确定时明确说明，不提供通行或过街许可。")
                    )
                    .put(
                        JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject().put("url", image))
                    )
            val payload =
                JSONObject()
                    .put("model", model)
                    .put("max_tokens", 500)
                    .put(
                        "messages",
                        JSONArray().put(JSONObject().put("role", "user").put("content", content)),
                    )
            val client =
                OkHttpClient.Builder()
                    .socketFactory(internet.socketFactory)
                    .dns(
                        object : Dns {
                            override fun lookup(hostname: String) =
                                internet.getAllByName(hostname).toList()
                        }
                    )
                    .followRedirects(false)
                    .callTimeout(35, TimeUnit.SECONDS)
                    .build()
            val call =
                client.newCall(
                    Request.Builder()
                        .url(base.trimEnd('/') + "/chat/completions")
                        .header("Authorization", "Bearer $key")
                        .post(payload.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                )
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(
                    object : Callback {
                        override fun onFailure(call: Call, e: java.io.IOException) {
                            if (continuation.isActive)
                                continuation.resumeWithException(java.io.IOException("识别服务连接失败或超时，请检查网络和服务后重试"))
                        }

                        override fun onResponse(call: Call, response: Response) {
                            val result = runCatching {
                                response.use {
                                    check(it.isSuccessful) { "识别服务返回 ${it.code}，请检查配置或额度" }
                                    val input = it.body?.byteStream() ?: error("识别服务未返回内容")
                                    val buffer = ByteArray(4096)
                                    val output = ByteArrayOutputStream()
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        check(output.size() + count <= 200_000) { "识别响应过大" }
                                        output.write(buffer, 0, count)
                                    }
                                    val bytes = output.toByteArray()
                                    JSONObject(String(bytes, Charsets.UTF_8))
                                        .getJSONArray("choices")
                                        .getJSONObject(0)
                                        .getJSONObject("message")
                                        .getString("content")
                                }
                            }
                            if (continuation.isActive)
                                result.fold(
                                    { continuation.resume(it) },
                                    { continuation.resumeWithException(it) },
                                )
                        }
                    }
                )
            }
        }
}
