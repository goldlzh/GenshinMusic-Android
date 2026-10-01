package com.example.lyretest

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 持久化运行日志，供无法连接电脑的用户直接导出反馈。 */
object RuntimeLog {
    private const val MAX_BYTES = 1024 * 1024L
    private const val FILE_NAME = "lyretest-runtime.log"
    @Volatile private var appContext: Context? = null
    private val fileLock = Any()
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "LyreRuntimeLog").apply { isDaemon = true }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        synchronized(fileLock) {
            val file = logFile(context)
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.writeText(
                    "LyreTest 运行日志\n" +
                            "设备：${Build.MANUFACTURER} ${Build.MODEL}\n" +
                            "Android：${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n"
                )
            }
        }
    }

    fun info(message: String) = append("INFO", message)

    fun error(message: String, throwable: Throwable? = null) {
        append("ERROR", buildString {
            append(message)
            throwable?.let { append(" | ${it.javaClass.simpleName}: ${it.message}") }
        })
    }

    private fun append(level: String, message: String) {
        val context = appContext ?: return
        writer.execute { appendNow(context, level, message) }
    }

    private fun appendNow(context: Context, level: String, message: String) {
        runCatching {
            synchronized(fileLock) {
                val file = logFile(context)
                if (file.length() >= MAX_BYTES) {
                    file.writeText("LyreTest 运行日志（达到 1MB 后已重新开始记录）\n")
                }
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                file.appendText("$time [$level] $message\n")
            }
        }
    }

    fun exportFile(context: Context): File {
        init(context)
        return writer.submit<File> {
            appendNow(context.applicationContext, "INFO", "用户导出运行日志")
            logFile(context)
        }.get(3, TimeUnit.SECONDS)
    }

    private fun logFile(context: Context): File =
        File(File(context.filesDir, "logs"), FILE_NAME)
}
