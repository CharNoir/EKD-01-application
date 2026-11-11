package com.adilet.ekd01

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The log history: every log line is also appended to a file, so it survives disconnects, app
 * restarts and the app process being killed, and can be browsed afterwards (Developer > Log history).
 *
 * Bounded on purpose: two files of at most [MAX_FILE_BYTES] each (the older one is replaced when the
 * newer one fills up), and writing happens on its own thread. Saving can be switched off; the noisy
 * T1/T9 telemetry lines only end up here when their own checkbox is on as well.
 */
object LogStore {
    private const val PREFS = "ekd01_prefs"
    private const val KEY_ENABLED = "log_history_enabled"
    private const val MAX_FILE_BYTES = 1_000_000L

    @Volatile var enabled = true
        private set

    private var current: File? = null
    private var previous: File? = null
    private var handler: Handler? = null
    private val ioLock = Any()

    fun init(ctx: Context) {
        if (handler != null) return
        val dir = File(ctx.applicationContext.filesDir, "logs").apply { mkdirs() }
        current = File(dir, "log.txt")
        previous = File(dir, "log.1.txt")
        enabled = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
        handler = Handler(HandlerThread("ekd01-log").apply { start() }.looper)
        installCrashHandler()
        logPreviousExits(ctx.applicationContext)
    }

    private fun stamp(time: Long = System.currentTimeMillis()) =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(time))

    /** An uncaught exception is written to the log (synchronously, the process is dying) before the normal crash handling. */
    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (enabled) write("${stamp()}  CRASH in thread ${thread.name}: ${android.util.Log.getStackTraceString(error)}\n")
            previousHandler?.uncaughtException(thread, error)
        }
    }

    /** Android remembers why our earlier processes ended (crash, killed by the system, ...). Logs the ones not seen yet. */
    private fun logPreviousExits(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        handler?.post {
            try {
                val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val lastSeen = prefs.getLong("log_last_exit_ts", 0L)
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val exits = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5).filter { it.timestamp > lastSeen }
                for (e in exits.sortedBy { it.timestamp }) {
                    write("${stamp(e.timestamp)}  Previous process ended: ${reasonName(e.reason)} (status ${e.status}) ${e.description ?: ""}\n")
                }
                exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("log_last_exit_ts", it).apply() }
            } catch (_: Exception) {
            }
        }
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE CRASH"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "KILLED low memory"
        ApplicationExitInfo.REASON_SIGNALED -> "KILLED by signal"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER stopped it"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER force-stopped it"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "KILLED excessive resource use"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission changed"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited itself"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
        ApplicationExitInfo.REASON_OTHER -> "other (system)"
        else -> "reason $r"
    }

    fun setEnabled(ctx: Context, value: Boolean) {
        enabled = value
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /** [line] already has its timestamp and ends with a newline. Returns at once; the write happens on the log thread. */
    fun append(line: String) {
        if (!enabled) return
        handler?.post { write(line) }
    }

    private fun write(line: String) = synchronized(ioLock) {
        val file = current ?: return
        try {
            if (file.length() > MAX_FILE_BYTES) {
                previous?.delete()
                file.renameTo(previous)
            }
            FileOutputStream(file, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
        } catch (_: Exception) {
            // The log must never be the thing that breaks the app.
        }
    }

    /** The last [maxChars] characters of the history (older file first). Call off the main thread. */
    fun readTail(maxChars: Int): String = synchronized(ioLock) {
        val text = try {
            (previous?.takeIf { it.exists() }?.readText() ?: "") + (current?.takeIf { it.exists() }?.readText() ?: "")
        } catch (_: Exception) {
            ""
        }
        if (text.length <= maxChars) text else text.substring(text.length - maxChars).substringAfter('\n')
    }

    fun sizeBytes(): Long = synchronized(ioLock) { (previous?.length() ?: 0L) + (current?.length() ?: 0L) }

    fun clear() = synchronized(ioLock) {
        previous?.delete()
        current?.delete()
        Unit
    }
}
