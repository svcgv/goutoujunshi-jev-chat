package com.jev.probe

import android.app.Application
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
 * Records uncaught exceptions to `filesDir/crash.log` (and logcat) before the
 * process dies.
 *
 * This app runs an AccessibilityService, which is not restartable interactively:
 * when a crash kills the process the service silently disappears and the user
 * sees only "it stopped working". Keeping the last stack traces on disk makes the
 * failure diagnosable, and [readLast] lets the UI surface the reason.
 *
 * No chat content, conversation titles or API keys are ever written here.
 */
class GoutouApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
    }
}

object CrashLogger {

    private const val TAG = "JEVASSIST"
    private const val MAX_BYTES = 64 * 1024

    @Volatile private var logFile: File? = null

    fun install(context: Context) {
        val target = File(context.applicationContext.filesDir, "crash.log")
        logFile = target
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record(target, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** The most recent recorded crash, or null when there is none. */
    fun readLast(context: Context): String? =
        runCatching {
            val f = File(context.applicationContext.filesDir, "crash.log")
            if (f.isFile && f.length() > 0) f.readText() else null
        }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.applicationContext.filesDir, "crash.log").delete() }
    }

    /** Also used by defensive catch blocks that do not want to kill the app. */
    fun recordCaught(context: Context?, where: String, throwable: Throwable) {
        Log.e(TAG, "caught in $where", throwable)
        val target = logFile ?: context?.let { File(it.applicationContext.filesDir, "crash.log") } ?: return
        runCatching { record(target, Thread.currentThread(), throwable, where) }
    }

    private fun record(target: File, thread: Thread, throwable: Throwable, where: String? = null) {
        val sw = StringWriter()
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val header = buildString {
            append("=== ").append(stamp)
            where?.let { append(" [").append(it).append(']') }
            append(" ===\n")
            append("thread=").append(thread.name)
            append(" device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(" android=").append(Build.VERSION.RELEASE)
            append(" sdk=").append(Build.VERSION.SDK_INT).append('\n')
        }
        val entry = header + sw.toString() + "\n"
        Log.e(TAG, entry)
        // Keep the file bounded so a crash loop cannot fill the device.
        runCatching {
            if (target.isFile && target.length() > MAX_BYTES) target.delete()
            target.parentFile?.mkdirs()
            target.appendText(entry, Charsets.UTF_8)
        }
    }
}
