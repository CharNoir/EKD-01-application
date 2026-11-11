package com.adilet.ekd01

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
