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

    /** Riding time from our own readings (moving time); null when the ride has no usable readings. */
    fun ridingSeconds(s: SessionRow): Int? = s.movingS

    fun totalKm(rides: List<SessionRow>): Double? {
        val parts = rides.mapNotNull { distanceKm(it) }
        return if (parts.isEmpty()) null else parts.sum()
    }

    fun totalSeconds(rides: List<SessionRow>): Int = rides.sumOf { ridingSeconds(it) ?: 0 }

    fun topSpeedKmh(rides: List<SessionRow>): Double? = rides.mapNotNull { it.topKmh }.maxOrNull()

    fun avgSpeedKmh(rides: List<SessionRow>): Double? {
        val km = totalKm(rides) ?: return null
        val s = totalSeconds(rides)
        return if (s > 0) km / (s / 3600.0) else null
    }

    /**
     * Battery percentage points used over several rides. Rides are chained between charges: the use is
     * the level at the start of the first ride minus the level at the end of the last one. Adding up each
     * ride separately would count the voltage sag after a hard ride, which the battery then recovers by itself.
     */
    fun batteryUsed(rides: List<SessionRow>): Int? {
        var total = 0
        var any = false
        var first: Int? = null
        var last: Int? = null
        fun close() {
            val a = first
            val b = last
            if (a != null && b != null) { total += max(0, a - b); any = true }
            first = null
            last = null
        }
        for (r in rides.sortedBy { it.connectedAt }) {
            if (r.chargeDetected) close()
            val a = r.batteryStart ?: continue
            val b = r.batteryEnd ?: continue
            if (first == null) first = a
            last = b
        }
        close()
        return if (any) total else null
    }

    // ---- Charge detection: tuning constants (see chargedBetween) ----------------------------------
    /** Largest sag recovery expected after a hard ride from a full battery, in percentage points. */
    private const val SAG_MAX_PTS = 20.0
    /** Mean speed over the last minutes at which the sag is taken as full. */
    private const val SPEED_REF_KMH = 30.0
    private const val MIN_SPEED_FACTOR = 0.25
    private const val MIN_LEVEL_FACTOR = 0.4
    /** How much more recovery to expect after the ride, as a share of the recovery seen during it. */
    private const val AFTER_RIDE_SHARE = 0.6
    /** Rises up to this many points are never called a charge (reading noise, small recovery). */
    private const val MIN_ALLOWANCE_PTS = 6.0
    /** Used when the previous ride has no speed readings / no start level. */
    private const val DEFAULT_SPEED_KMH = 21.0
    private const val DEFAULT_START_PCT = 80
    /** Next ride starting this far above the previous ride's start (and MIN_CERTAIN_RISE above its end) is a sure charge. */
    private const val CERTAIN_MARGIN_PTS = 2
    private const val MIN_CERTAIN_RISE_PTS = 5

    /**
     * Was the battery charged between two rides? A battery under load shows a lower level than it really
     * has and recovers by itself, partly while still riding and for hours afterwards, so a rise alone is
     * not proof. A small top-up that stays within the plausible recovery is deliberately not reported.
     *
     *   speed factor  f_v = clamp(v10 / SPEED_REF, MIN_SPEED_FACTOR, 1)   v10 = [recentKmh], mean speed in the last 10 min
     *   level factor  f_L = clamp(start% / 100, MIN_LEVEL_FACTOR, 1)      start% = level at the start of the previous ride
     *   model         M   = SAG_MAX x f_v x f_L
     *   observed      O   = AFTER_RIDE_SHARE x [drawup]                   biggest rise from a low point during the ride
     *                                                                     (nothing charges while riding, so it is recovery)
     *   allowance     A   = min(SAG_MAX, max(M, O))
     *   threshold     T   = max(MIN_ALLOWANCE, min(A, drop))              drop = what the previous ride took off
     *
     * Charged when rise (next start - previous end) > T, or when the next ride starts at least
     * CERTAIN_MARGIN above where the previous one started (resting cannot give back more than was there).
     */
    fun chargedBetween(prevStart: Int?, prevEnd: Int?, start: Int?, recentKmh: Double?, drawup: Int?): Boolean {
        if (prevEnd == null || start == null) return false
        val rise = start - prevEnd
        if (prevStart != null && start >= prevStart + CERTAIN_MARGIN_PTS && rise >= MIN_CERTAIN_RISE_PTS) return true
        val drop = max(0, (prevStart ?: prevEnd) - prevEnd)
        val speedFactor = ((recentKmh ?: DEFAULT_SPEED_KMH) / SPEED_REF_KMH).coerceIn(MIN_SPEED_FACTOR, 1.0)
        val levelFactor = ((prevStart ?: DEFAULT_START_PCT) / 100.0).coerceIn(MIN_LEVEL_FACTOR, 1.0)
        val model = SAG_MAX_PTS * speedFactor * levelFactor
        val observed = AFTER_RIDE_SHARE * (drawup ?: 0)
        val allowance = minOf(SAG_MAX_PTS, max(model, observed))
        return rise > max(MIN_ALLOWANCE_PTS, minOf(allowance, drop.toDouble()))
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
