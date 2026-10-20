package com.hashim.notepad

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Writes the last uncaught exception to app storage so the next launch can offer to share it.
 * No network, no third-party SDK: the report leaves the device only when the user shares it.
 */
object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val FILE_NAME = "last-crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
                .onFailure { Log.e(TAG, "Could not persist crash report", it) }
            if (previous != null) previous.uncaughtException(thread, error) else exitProcess(10)
        }
    }

    fun pendingReport(context: Context): String? =
        reportFile(context).takeIf { it.isFile }?.let { file -> runCatching { file.readText() }.getOrNull() }

    fun clear(context: Context) {
        reportFile(context).delete()
    }

    fun shareIntent(report: String): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "NotePad crash report")
                putExtra(Intent.EXTRA_TEXT, report)
            },
            "Share crash report",
        )

    internal fun format(thread: Thread, error: Throwable, versionName: String, time: Date = Date()): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            appendLine("NotePad $versionName crash report")
            appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(time)}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ${Build.DISPLAY}")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS?.joinToString().orEmpty()}")
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(trace.take(MAX_TRACE_CHARS))
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val file = reportFile(context)
        file.parentFile?.mkdirs()
        file.writeText(format(thread, error, BuildConfig.VERSION_NAME))
    }

    private fun reportFile(context: Context) = File(File(context.filesDir, "crash"), FILE_NAME)

    private const val MAX_TRACE_CHARS = 24_000
}
