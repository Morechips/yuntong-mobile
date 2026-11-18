package cn.yuntong.companion

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import cn.yuntong.companion.platform.GlassesRecorder
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@SdkSuppress(minSdkVersion = 28)
class GlassesRecorderTest {
    @Test
    fun recordsChangingFramesAsDecodableSilentMp4() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("synthetic-glasses-", ".mp4", context.cacheDir)
        val source = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        try {
            val recorder = GlassesRecorder(file)
            try {
                repeat(8) { index ->
                    source.eraseColor(if (index < 4) Color.RED else Color.BLUE)
                    recorder.append(source)
                    SystemClock.sleep(250)
                }
            } catch (error: Exception) {
                runCatching { recorder.finish() }
                throw error
            }
            assertTrue("The recording must be finalized", recorder.finish())
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                assertEquals(1, extractor.trackCount)
                val track = extractor.getTrackFormat(0)
                assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, track.getString(MediaFormat.KEY_MIME))
                assertEquals(640, track.getInteger(MediaFormat.KEY_WIDTH))
                assertEquals(480, track.getInteger(MediaFormat.KEY_HEIGHT))
            } finally {
                extractor.release()
            }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val count = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
                assertTrue("The MP4 must contain the two groups of frames", count >= 6)
                val first = checkNotNull(retriever.getFrameAtIndex(0))
                val firstColor = first.getPixel(first.width / 2, first.height / 2)
                first.recycle()
                val last = checkNotNull(retriever.getFrameAtIndex(count - 1))
                val lastColor = last.getPixel(last.width / 2, last.height / 2)
                last.recycle()
                assertTrue("The first frame must decode as red", Color.red(firstColor) > Color.blue(firstColor) + 80)
                assertTrue("The last frame must decode as blue", Color.blue(lastColor) > Color.red(lastColor) + 80)
            } finally {
                retriever.release()
            }
        } finally {
            source.recycle()
            file.delete()
        }
    }
}
