package com.mycosensai.hermes

import android.content.Context
import android.os.Process
import java.io.File
import kotlin.system.exitProcess

/** App-private, bounded diagnostics. Never uploads anything automatically. */
object CrashDiagnostics {
    private fun report(context: Context) = File(context.filesDir, "last-crash.txt")
    fun record(context: Context, error: Throwable) {
        runCatching {
            val text = error.stackTraceToString()
                .replace(Regex("(?i)Bearer\\s+[^\\s]+"), "Bearer [redacted]")
                .replace(Regex("""(?i)(api[_-]?key|access_token|refresh_token)([\s"':=]+)[^\s,}]+"""), "$1$2[redacted]")
            report(context).writeText(text.take(64000))
        }
    }
    fun previous(context: Context): String? = runCatching {
        report(context).takeIf { it.exists() }?.readText()?.take(64000)
    }.getOrNull()
    fun clear(context: Context) { runCatching { report(context).delete() } }
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(context, error)
            if (previous != null) previous.uncaughtException(thread, error)
            else { Process.killProcess(Process.myPid()); exitProcess(10) }
        }
    }
}
