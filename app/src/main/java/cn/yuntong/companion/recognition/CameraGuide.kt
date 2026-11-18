package cn.yuntong.companion.recognition

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import cn.yuntong.companion.domain.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*

class CameraGuide(private val report: (FramingHint, Boolean) -> Unit) : ImageAnalysis.Analyzer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ocr = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val running = AtomicBoolean(false)
    private val stability = FramingStability()
    @Volatile private var closed = false
    private var lastAt = -10000L

    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (closed || now - lastAt < 1000 || !running.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastAt = now
        val bitmap =
            try {
                val b = image.toBitmap()
                val angle = image.imageInfo.rotationDegrees
                if (angle == 0) b
                else
                    Bitmap.createBitmap(
                        b,
                        0,
                        0,
                        b.width,
                        b.height,
                        Matrix().apply { postRotate(angle.toFloat()) },
                        true,
                    )
            } catch (_: Exception) {
                running.set(false)
                null
            } finally {
                image.close()
            }
        if (bitmap == null) return
        scope.launch {
            try {
                val hint = inspect(bitmap)
                if (!closed) report(hint, stability.update(hint, SystemClock.elapsedRealtime()))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (!closed) report(FramingHint("error", "取景检测暂不可用，仍可手动拍照。"), false)
            } finally {
                running.set(false)
            }
        }
    }

    suspend fun inspect(bitmap: Bitmap): FramingHint {
        val brightness =
            withContext(Dispatchers.Default) {
                var sum = 0.0
                var count = 0
                for (y in 0 until bitmap.height step 16) for (x in 0 until bitmap.width step 16) {
                    val p = bitmap.getPixel(x, y)
                    sum +=
                        .299 * ((p shr 16) and 255) +
                            .587 * ((p shr 8) and 255) +
                            .114 * (p and 255)
                    count++
                }
                sum / count.coerceAtLeast(1)
            }
        val boxes =
            suspendCancellableCoroutine<List<TextBounds>> { c ->
                ocr.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { text ->
                        if (c.isActive)
                            c.resume(
                                text.textBlocks
                                    .flatMap { it.lines }
                                    .mapNotNull { line ->
                                        line.boundingBox?.let { b ->
                                            TextBounds(
                                                b.left.toFloat() / bitmap.width,
                                                b.top.toFloat() / bitmap.height,
                                                b.right.toFloat() / bitmap.width,
                                                b.bottom.toFloat() / bitmap.height,
                                            )
                                        }
                                    }
                            )
                    }
                    .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
            }
        return framingHint(brightness, boxes)
    }

    fun close() {
        closed = true
        scope.cancel()
        ocr.close()
    }
}
