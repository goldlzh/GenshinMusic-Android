package com.example.lyretest.songs

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.lyretest.BuildConfig
import com.example.lyretest.RuntimeLog
import java.io.File

/**
 * 曲库：用户通过系统文件选择器导入的 MIDI 会被复制到应用私有目录，
 * 之后无需再持有 URI 权限，也不受外部文件被移动/删除影响。
 */
class SongRepository(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "songs").apply { if (!exists()) mkdirs() }

    fun list(): List<File> =
        dir.listFiles { f: File -> f.extension.lowercase() in MIDI_EXTS }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()

    /**
     * 临时内置曲库版首次运行时，把 APK assets 中的 MIDI 落到正常曲库目录。
     * 正常 debug/release 的 BuildConfig 开关为 false，不会扫描或生成任何内置歌曲。
     */
    fun installBundledSongsIfNeeded(): Int {
        if (!BuildConfig.BUNDLED_MIDI) return 0
        val marker = File(dir, BUNDLED_MARKER)
        if (marker.exists()) return 0

        val assetNames = context.assets.list("").orEmpty()
            .filter { it.substringAfterLast('.', "").lowercase() in MIDI_EXTS }
            .sortedBy { it.lowercase() }
        var installed = 0
        try {
            for (assetName in assetNames) {
                val target = File(dir, sanitize(assetName))
                if (target.exists()) continue
                context.assets.open(assetName).use { input ->
                    target.outputStream().use(input::copyTo)
                }
                installed++
            }
            marker.writeText("catalog=v1\ncount=${assetNames.size}\n")
            RuntimeLog.info("内置曲库初始化完成：资源=${assetNames.size}，新增=$installed")
        } catch (e: Exception) {
            RuntimeLog.error("内置曲库初始化失败：已新增=$installed", e)
            throw e
        }
        return installed
    }

    /** 导入一个 MIDI，返回落地后的文件；重名会自动加序号 */
    fun import(uri: Uri): File {
        val name = queryName(uri) ?: "未命名.mid"
        val target = uniqueFile(sanitize(name))
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(4)
                var read = 0
                while (read < header.size) {
                    val count = input.read(header, read, header.size - read)
                    if (count < 0) break
                    read += count
                }
                if (read != 4 || !header.contentEquals(byteArrayOf('M'.code.toByte(), 'T'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte()))) {
                    throw IllegalArgumentException("所选文件不是标准 MIDI（缺少 MThd 文件头）")
                }
                target.outputStream().use { output ->
                    output.write(header)
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("无法读取所选文件")
            target
        } catch (e: Exception) {
            // 批量导入中单个文件失败时不能留下半截 MIDI。
            target.delete()
            throw e
        }
    }

    fun delete(file: File): Boolean = file.parentFile == dir && file.delete()

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        val base = cleaned.ifEmpty { "未命名.mid" }
        return if (base.substringAfterLast('.', "").lowercase() in MIDI_EXTS) base
        else "$base.mid"
    }

    private fun uniqueFile(name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "mid")
        var i = 2
        while (candidate.exists()) {
            candidate = File(dir, "$stem($i).$ext")
            i++
        }
        return candidate
    }

    private fun queryName(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    companion object {
        private const val BUNDLED_MARKER = ".bundled-midi-172-v1"
        val MIDI_EXTS = setOf("mid", "midi")

        /** 系统文件选择器用的 MIME 类型，部分机型认不出 audio/midi，所以放宽 */
        val PICK_MIME_TYPES = arrayOf(
            "audio/midi",
            "audio/mid",
            "audio/x-midi",
            "audio/sp-midi",
            "application/x-midi",
            "application/octet-stream"
        )
    }
}
