package cn.hnist.timetable.ui

import android.content.Context
import android.os.Build
import cn.hnist.timetable.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime

/**
 * 用户机器上没有可用的 adb，闪退拿不到堆栈；把未捕获异常写进私有目录，
 * 下次启动主页面把报告展示出来让用户分享给开发者。
 */
object CrashGuard {

    private const val FILE_NAME = "crash_last.txt"

    /** 挂到当前进程的未捕获异常上；原来的处理器仍然会执行（系统照常弹“已停止”）。 */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { save(appContext, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun save(context: Context, throwable: Throwable): File? =
        runCatching {
            val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
            val report = buildString {
                appendLine("time: ${LocalDateTime.now()}")
                appendLine("version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("android: ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("thread: ${Thread.currentThread().name}")
                appendLine()
                append(stack)
            }
            File(context.applicationContext.filesDir, FILE_NAME).apply { writeText(report) }
        }.getOrNull()

    fun lastReport(context: Context): String? {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun clear(context: Context) {
        runCatching { File(context.applicationContext.filesDir, FILE_NAME).delete() }
    }
}
