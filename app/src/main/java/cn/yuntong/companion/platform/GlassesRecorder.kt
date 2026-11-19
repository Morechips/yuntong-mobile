package cn.yuntong.companion.platform

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/** Encodes the subscribed glasses preview to a silent, locally playable MP4. */
class GlassesRecorder(private val file: File) {
    private val width = 640
    private val height = 480
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val codec = try {
        MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    } catch (e: Exception) {
        muxer.release()
        throw e
    }
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var started = false
    private var writtenFrames = 0
    private val startedAt = System.nanoTime()

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 600_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 4)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            try { codec.release() } finally { muxer.release() }
            throw e
        }
    }

    fun append(source: Bitmap) {
        val input = codec.dequeueInputBuffer(500_000)
        if (input < 0) return
        val image = codec.getInputImage(input) ?: error("视频编码器无法接收画面")
        val bitmap = Bitmap.createScaledBitmap(source, width, height, true)
        try {
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val y = image.planes[0]
            val u = image.planes[1]
            val v = image.planes[2]
            for (row in 0 until height) {
                for (col in 0 until width) {
                    val pixel = pixels[row * width + col]
                    val red = pixel shr 16 and 255
                    val green = pixel shr 8 and 255
                    val blue = pixel and 255
                    val luma = ((66 * red + 129 * green + 25 * blue + 128) shr 8) + 16
                    y.buffer.put(row * y.rowStride + col * y.pixelStride, luma.coerceIn(0, 255).toByte())
                    if (row % 2 == 0 && col % 2 == 0) {
                        val chromaU = ((-38 * red - 74 * green + 112 * blue + 128) shr 8) + 128
                        val chromaV = ((112 * red - 94 * green - 18 * blue + 128) shr 8) + 128
                        val offsetU = row / 2 * u.rowStride + col / 2 * u.pixelStride
                        val offsetV = row / 2 * v.rowStride + col / 2 * v.pixelStride
                        u.buffer.put(offsetU, chromaU.coerceIn(0, 255).toByte())
                        v.buffer.put(offsetV, chromaV.coerceIn(0, 255).toByte())
                    }
                }
            }
        } finally {
            image.close()
            if (bitmap !== source) bitmap.recycle()
        }
        codec.queueInputBuffer(input, 0, width * height * 3 / 2,
            (System.nanoTime() - startedAt) / 1000, 0)
        drain(false)
    }

    fun finish(): Boolean {
        try {
            val input = codec.dequeueInputBuffer(1_000_000)
            if (input >= 0) codec.queueInputBuffer(input, 0, 0,
                (System.nanoTime() - startedAt) / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            return writtenFrames > 0
        } finally {
            try {
                try { codec.stop() } finally { codec.release() }
            } finally {
                try { if (started) muxer.stop() } finally { muxer.release() }
            }
        }
    }

    private fun drain(end: Boolean) {
        var waiting = 0
        while (true) {
            val output = codec.dequeueOutputBuffer(info, if (end) 100_000 else 0)
            when (output) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!end || ++waiting >= 20) return
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                }
                else -> if (output >= 0) {
                    if (info.size > 0 && started && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val bytes = codec.getOutputBuffer(output) ?: error("视频编码结果不可用")
                        bytes.position(info.offset)
                        bytes.limit(info.offset + info.size)
                        muxer.writeSampleData(track, bytes, info)
                        writtenFrames++
                    }
                    val done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(output, false)
                    if (done) return
                }
            }
        }
    }
}
