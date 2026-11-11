package com.adilet.ekd01

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Measurements for the Developer screen: how often T1/T9 packets arrive, and when the assist level
 * changed (with the time since the previous change and the gap between the packets around it).
 * Fed from [BikeLink] on the Bluetooth thread, read from the UI thread, hence the lock.
 */
object TelemetryStats {

    data class RateStats(val count: Int, val avgMs: Double, val minMs: Long, val maxMs: Long) {
        val perSecond: Double get() = if (avgMs > 0) 1000.0 / avgMs else 0.0
    }

    /** [from] is null for the first level seen after connecting. [packetGapMs] = time since the previous T1 packet. */
    data class Change(val wallMs: Long, val from: Int?, val to: Int, val sinceLastChangeMs: Long?, val packetGapMs: Long?)

    class Snapshot(val t1: RateStats?, val t9: RateStats?, val changes: List<Change>)

    private const val MAX_PACKETS = 200
    private const val MAX_CHANGES = 30

    private val lock = Any()
    private val t1Times = ArrayDeque<Long>()
    private val t9Times = ArrayDeque<Long>()
    private val changes = ArrayDeque<Change>()
    private var lastLevel: Int? = null
    private var lastChangeElapsed = -1L

    fun onT1(level: Int, elapsedMs: Long, wallMs: Long) = synchronized(lock) {
        val gap = t1Times.lastOrNull()?.let { elapsedMs - it }
        t1Times.addLast(elapsedMs)
        while (t1Times.size > MAX_PACKETS) t1Times.removeFirst()
        val prev = lastLevel
        if (prev != level) {
            val since = if (lastChangeElapsed >= 0 && prev != null) elapsedMs - lastChangeElapsed else null
            changes.addFirst(Change(wallMs, prev, level, since, gap))
            while (changes.size > MAX_CHANGES) changes.removeLast()
            lastLevel = level
            lastChangeElapsed = elapsedMs
        }
    }

    fun onT9(elapsedMs: Long) = synchronized(lock) {
        t9Times.addLast(elapsedMs)
        while (t9Times.size > MAX_PACKETS) t9Times.removeFirst()
    }

    /** Link lost: the next packet starts a new baseline. The change list is kept. */
    fun onDisconnect() = synchronized(lock) {
        t1Times.clear()
        t9Times.clear()
        lastLevel = null
        lastChangeElapsed = -1L
    }

    fun clear() = synchronized(lock) {
        onDisconnect()
        changes.clear()
    }

    fun snapshot(): Snapshot = synchronized(lock) { Snapshot(rate(t1Times), rate(t9Times), changes.toList()) }

    private fun rate(times: ArrayDeque<Long>): RateStats? {
        if (times.size < 2) return null
        var min = Long.MAX_VALUE
        var max = 0L
        var prev = times.first()
        for (i in 1 until times.size) {
            val d = times[i] - prev
            if (d < min) min = d
            if (d > max) max = d
            prev = times[i]
        }
        return RateStats(times.size, (times.last() - times.first()).toDouble() / (times.size - 1), min, max)
    }
}

/**
 * A "secret knock" on the stock controls: changing the assist level in a set order and quickly
 * (default 5-4-5-4-5-4, each step within 2 s of the previous one) triggers [perform].
 * Runs inside [BikeLink], so it also works in background mode, without the app open.
 *
 * The pattern is a list of levels as the display reports them. The first level is where you start
 * (no time limit); every following change has to come within [gapMs] of the one before. Whether quick
 * knocks are seen depends on how often the display reports the level: see the analysis card in the
 * Developer menu.
 */
object AssistGesture {

    private const val PREFS = "ekd01_prefs"
    private const val KEY_ENABLED = "gesture_enabled"
    private const val KEY_SEQUENCE = "gesture_sequence"
    private const val KEY_GAP_MS = "gesture_gap_ms"

    val DEFAULT_SEQUENCE = listOf(5, 4, 5, 4, 5, 4)
    const val DEFAULT_GAP_MS = 2000
    const val MIN_GAP_MS = 200
    const val MAX_GAP_MS = 10_000

    @Volatile private var enabled = true
    @Volatile private var pattern: List<Int> = DEFAULT_SEQUENCE
    @Volatile private var gapMs = DEFAULT_GAP_MS
    @Volatile var lastTriggeredAt = 0L
        private set

    private class State(val level: Int, val elapsedMs: Long)
    private val states = ArrayList<State>()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Loads the saved settings; called once from [BikeLink.init]. */
    fun load(ctx: Context) {
        val p = prefs(ctx)
        enabled = p.getBoolean(KEY_ENABLED, true)
        pattern = parse(p.getString(KEY_SEQUENCE, null) ?: "") ?: DEFAULT_SEQUENCE
        gapMs = p.getInt(KEY_GAP_MS, DEFAULT_GAP_MS).coerceIn(MIN_GAP_MS, MAX_GAP_MS)
    }

    val isEnabled: Boolean get() = enabled
    val sequenceText: String get() = pattern.joinToString("-")
    val maxGapMs: Int get() = gapMs

    fun setEnabled(ctx: Context, value: Boolean) {
        enabled = value
        prefs(ctx).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /** Levels 0-5 separated by "-", "," or spaces, 2 to 12 of them. Null if it does not parse. */
    fun parse(text: String): List<Int>? {
        val parts = text.split('-', ',', ' ', ';').filter { it.isNotBlank() }.map { it.trim().toIntOrNull() ?: return null }
        return parts.takeIf { it.size in 2..12 && it.all { v -> v in 0..5 } }
    }

    /** False (nothing saved) if [text] is not a valid sequence or [gap] is out of range. */
    fun configure(ctx: Context, text: String, gap: Int): Boolean {
        val parsed = parse(text) ?: return false
        if (gap !in MIN_GAP_MS..MAX_GAP_MS) return false
        pattern = parsed
        gapMs = gap
        prefs(ctx).edit().putString(KEY_SEQUENCE, parsed.joinToString("-")).putInt(KEY_GAP_MS, gap).apply()
        synchronized(states) { states.clear() }
        return true
    }

    fun reset() = synchronized(states) { states.clear() }

    /** Called for every T1 packet (any thread). */
    fun onLevel(ctx: Context, level: Int, elapsedMs: Long) {
        if (!enabled) return
        val pat = pattern
        val hit = synchronized(states) {
            if (states.isNotEmpty() && states.last().level == level) return
            states.add(State(level, elapsedMs))
            while (states.size > pat.size) states.removeAt(0)
            if (states.size == pat.size && matches(pat)) {
                states.clear()
                states.add(State(level, elapsedMs))
                true
            } else false
        }
        if (hit) {
            lastTriggeredAt = System.currentTimeMillis()
            perform(ctx)
        }
    }

    private fun matches(pat: List<Int>): Boolean {
        for (i in pat.indices) {
            if (states[i].level != pat[i]) return false
            if (i >= 1 && states[i].elapsedMs - states[i - 1].elapsedMs > gapMs) return false
        }
        return true
    }

    /**
     * What the knock does. For now: a short buzz and a log line, so the detection can be tested.
     * Put the real actions here.
     */
    @Suppress("DEPRECATION")
    private fun perform(ctx: Context) {
        BikeLink.log(ctx.getString(R.string.log_gesture, sequenceText))
        (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            ?.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
        BikeLink.notifyGesture(sequenceText)
    }
}
