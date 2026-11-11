package com.adilet.ekd01

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.io.FileOutputStream

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
