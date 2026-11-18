package cn.yuntong.companion.platform

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

internal object OfflineVoiceModel {
    private const val NAME = "vosk-model-small-cn-0.22"

    fun unpack(context: Context): File {
        val target = File(context.filesDir, NAME)
        if (valid(target)) return target
        val staging = File(context.filesDir, "$NAME.partial")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "无法创建离线语音模型目录" }
        try {
            var total = 0L
            ZipInputStream(context.assets.open("$NAME.zip")).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val relative = entry.name.removePrefix("$NAME/")
                    if (entry.name != "$NAME/" && relative.isNotEmpty()) {
                        require(entry.name.startsWith("$NAME/") &&
                            !relative.contains("..") && !relative.startsWith('/')) { "语音模型文件名异常" }
                        val file = File(staging, relative)
                        require(file.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                            "语音模型路径异常"
                        }
                        if (entry.isDirectory) check(file.mkdirs() || file.isDirectory)
                        else {
                            check(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true)
                            file.outputStream().use { output ->
                                val buffer = ByteArray(32 * 1024)
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    require(total <= 100_000_000) { "语音模型大小异常" }
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            check(valid(staging)) { "离线语音模型缺少必要文件" }
            if (target.exists()) check(target.deleteRecursively())
            check(staging.renameTo(target)) { "无法安装离线语音模型" }
            return target
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun valid(dir: File): Boolean =
        File(dir, "am/final.mdl").isFile && File(dir, "graph/HCLr.fst").isFile &&
            File(dir, "graph/Gr.fst").isFile
}
