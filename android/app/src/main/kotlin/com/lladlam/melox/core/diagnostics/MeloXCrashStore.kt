package com.lladlam.melox.core.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps uncaught crashes on disk. A process that dies cannot be read back from
 * logcat by pid, which is why exported logs used to stop at the last
 * audio-effect line.
 *
 * 2026-10-01 —— 记录内容扩写，用来支撑「在别的机型上闪退」这类只有一台真机的排查：
 *
 * - **完整 cause 链 + 完整 stackTrace**（原先只打顶层 `printStackTrace`，包一层
 *   `RuntimeException` 就丢掉了真正的原因）。
 * - **设备 + 版本信息**：玻璃相关的崩溃高度依赖 GPU 驱动 / ROM，机型是第一手线索。
 * - ★ **崩溃瞬间玻璃是否在渲染**（[glassRenderingActive]）。这是把「玻璃路径崩」
 *   和「其他路径崩」分开的**唯一**一位信息 —— 没有它，任何修复都只是猜。
 * - 保留最近 [MAX_REPORTS] 份。原先只留一份，连崩两次会互相覆盖。
 *
 * ⚠ 本记录器**只记录，不做任何降级**：玻璃是否继续渲染与它完全无关。
 *   它存在的意义是让下一次崩溃「说出是哪一行」。
 *   （也不要在崩溃瞬间写 SharedPreferences —— 那条路曾经被用来做「崩溃自愈」，
 *   会把**能正常跑玻璃的机型**的玻璃关掉，是藏 bug 而不是修 bug。）
 */
object MeloXCrashStore {
    private const val Tag = "MeloXCrash"
    private const val DirName = "crash"
    private const val FilePrefix = "crash-"
    private const val MaxReports = 10

    /**
     * 本进程是否真的把玻璃画上屏过。
     *
     * ⚠ 读它的人只该写日志，**不要**拿它做门控。
     */
    @Volatile
    var glassRenderingActive: Boolean = false
        private set

    /** 由 `meloXGlassBackdrop()` 打点。极轻，无锁、无 IO。 */
    fun markGlassRendering() {
        glassRenderingActive = true
    }

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // 记录本身绝不能成为二次崩溃源。
            runCatching { record(appContext, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** 最近若干份崩溃记录的正文（供日志导出使用）；没有则返回 null。 */
    fun read(context: Context): String? {
        val files = reportFiles(context).takeLast(RecentReportCount)
        if (files.isEmpty()) return null
        return files.joinToString(separator = "\n") { file ->
            runCatching { file.readText() }.getOrElse { "（无法读取 ${file.name}）" }
        }.takeIf { it.isNotBlank() }
    }

    /** 已落盘的崩溃份数（含更早的），供日志导出显示数量。 */
    fun reportCount(context: Context): Int = reportFiles(context).size

    private fun reportFiles(context: Context): List<File> =
        runCatching {
            File(context.filesDir, DirName)
                .listFiles { file -> file.isFile && file.name.startsWith(FilePrefix) }
                ?.sortedBy { it.name }
        }.getOrNull().orEmpty()

    private fun record(context: Context, thread: Thread, error: Throwable) {
        val dir = File(context.filesDir, DirName)
        if (!dir.exists() && !dir.mkdirs()) {
            // 落不了盘就至少别把日志通道也丢掉。
            Log.e(Tag, render(context, thread, error))
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val body = render(context, thread, error, stamp)
        runCatching { File(dir, "$FilePrefix$stamp.txt").writeText(body) }
            .onFailure { Log.e(Tag, "Unable to persist crash", it) }
        Log.e(Tag, body)
        prune(dir)
    }

    private fun render(
        context: Context,
        thread: Thread,
        error: Throwable,
        stamp: String = "?",
    ): String = buildString {
        appendLine("=== MeloX 崩溃现场 ===")
        appendLine("time=$stamp (${System.currentTimeMillis()})")
        appendLine("pid=${android.os.Process.myPid()}")
        appendLine("package=${context.packageName}  version=${versionName(context)}")
        appendLine("build=${if (com.lladlam.melox.BuildConfig.DEBUG) "Debug" else "Release"}")
        appendLine("thread=${thread.name}")
        // ★ 最关键的一位：用来区分「玻璃渲染路径崩」和「其他路径崩」。
        appendLine("glassRendering=$glassRenderingActive")
        appendLine("android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("board/hardware=${Build.BOARD} / ${Build.HARDWARE}")
        appendLine("abi=${Build.SUPPORTED_ABIS.joinToString("、")}")
        appendLine()
        appendLine("=== 异常 ===")
        appendLine("顶层：${error.javaClass.name}: ${error.message}")
        appendLine()
        appendLine("--- cause 链 ---")
        var cause: Throwable? = error
        var depth = 0
        // 自引用 cause 会死循环，深度也顺手封顶。
        while (cause != null && depth < 16) {
            appendLine("[$depth] ${cause.javaClass.name}: ${cause.message}")
            cause = cause.cause
            depth++
        }
        appendLine()
        appendLine("=== 完整堆栈 ===")
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        append(writer.toString())
    }

    /** 只保留最近的 [MaxReports] 份，避免长期占用用户存储。 */
    private fun prune(dir: File) {
        runCatching {
            val files = dir.listFiles { file -> file.isFile && file.name.startsWith(FilePrefix) }
                ?.sortedBy { it.name }
                ?: return
            if (files.size <= MaxReports) return
            files.dropLast(MaxReports).forEach { it.delete() }
        }
    }

    private fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    /** 日志导出里附上几份崩溃记录。 */
    private const val RecentReportCount = 3
}
