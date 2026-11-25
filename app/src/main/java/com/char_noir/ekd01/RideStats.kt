package com.char_noir.ekd01

import kotlin.math.max
import kotlin.math.min

/** Small calculations over recorded rides, shared by the history screens. */
object RideStats {

    fun distanceKm(s: SessionRow): Double? {
        val a = s.odoStartKm
        val b = s.odoEndKm
        return if (a != null && b != null) (b - a).coerceAtLeast(0.0) else null
    }

    /** Time between connecting and disconnecting; null while the ride is still open. */
    fun durationS(s: SessionRow): Int? = s.disconnectedAt?.let { ((it - s.connectedAt) / 1000).toInt() }

    /** Riding time: the display's trip time when it was recorded, otherwise the connection time. */
    fun ridingSeconds(s: SessionRow): Int? = s.tripTimeS ?: durationS(s)

    fun totalKm(rides: List<SessionRow>): Double? {
        val parts = rides.mapNotNull { distanceKm(it) }
        return if (parts.isEmpty()) null else parts.sum()
    }

    fun totalSeconds(rides: List<SessionRow>): Int = rides.sumOf { ridingSeconds(it) ?: 0 }

    fun topSpeedKmh(rides: List<SessionRow>): Double? = rides.mapNotNull { it.maxKmh }.maxOrNull()

    fun avgSpeedKmh(rides: List<SessionRow>): Double? {
        val km = totalKm(rides) ?: return null
        val s = totalSeconds(rides)
        return if (s > 0) km / (s / 3600.0) else null
    }

    /** Battery percentage points used over several rides (charging between rides is not counted). */
    fun batteryUsed(rides: List<SessionRow>): Int? {
        val parts = rides.mapNotNull { r ->
            val a = r.batteryStart
            val b = r.batteryEnd
            if (a != null && b != null) max(0, a - b) else null
        }
        return if (parts.isEmpty()) null else parts.sum()
    }

    /**
     * Rough total climb in metres from the GPS altitude: smoothed over 5 fixes, and rises smaller
     * than 2 m are ignored. Phone altitude is noisy, so show it as approximate.
     */
    fun climbM(points: List<TrackPoint>): Double? {
        val alts = points.mapNotNull { it.altM }
        if (alts.size < 5) return null
        val smooth = alts.indices.map { i -> alts.subList(max(0, i - 2), min(alts.size - 1, i + 2) + 1).average() }
        var gain = 0.0
        var ref = smooth[0]
        for (v in smooth) {
            if (v - ref >= 2.0) { gain += v - ref; ref = v } else if (v < ref) ref = v
        }
        return gain
    }
}
