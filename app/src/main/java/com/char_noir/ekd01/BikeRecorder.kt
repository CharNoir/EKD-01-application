package com.char_noir.ekd01

import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper

/**
 * Writes ride history to [RideDb]. Fed directly by [BikeLink] (state changes and telemetry); all
 * work runs on its own background thread, so the UI and Bluetooth threads never wait on the disk.
 *
 * - CONNECTED: opens a session; the first telemetry packet writes the first sample and checks
 *   whether the battery was charged since the previous session.
 * - While connected: one sample every N seconds (Settings, default 60).
 * - DISCONNECTED (link lost, tapped, or the app was closed): writes a last sample with the last
 *   known values and status 'disconnected', and closes the session.
 * - If the process is killed without a disconnect event, the session is closed at the next app
 *   start using its last sample.
 */
object BikeRecorder {

    private const val PREFS = "ekd01_prefs"
    private const val KEY_INTERVAL_S = "record_interval_s"
    const val DEFAULT_INTERVAL_S = 60
    const val MIN_INTERVAL_S = 1
    const val MAX_INTERVAL_S = 3600

    /** A battery percentage this many points higher than at the end of the previous session counts as "charged". */
    const val CHARGE_JUMP_PCT = 15

    private const val CONNECTED = "connected"
    private const val DISCONNECTED = "disconnected"

    private lateinit var db: RideDb
    private lateinit var appContext: Context
    private lateinit var handler: Handler
    @Volatile private var started = false

    // Everything below is touched only on the recorder thread.
    private var sessionId = -1L
    private var sessionAddress: String? = null
    private var t1: Protocol.Telemetry1? = null
    private var t9: Protocol.Telemetry9? = null
    private var firstT1Seen = false

    private val tick = object : Runnable {
        override fun run() {
            if (sessionId < 0) return
            guarded { writeSample(CONNECTED) }
            handler.postDelayed(this, intervalMs())
        }
    }

    /** Seconds between saved readings while connected. */
    fun intervalSeconds(context: Context): Int =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_INTERVAL_S, DEFAULT_INTERVAL_S).coerceIn(MIN_INTERVAL_S, MAX_INTERVAL_S)

    /** Saves the interval (clamped to 1..3600 s); takes effect right away, also for a ride in progress. */
    fun setIntervalSeconds(context: Context, seconds: Int) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_INTERVAL_S, seconds.coerceIn(MIN_INTERVAL_S, MAX_INTERVAL_S)).apply()
        if (!started) return
        handler.post {
            if (sessionId >= 0) {
                handler.removeCallbacks(tick)
                handler.postDelayed(tick, intervalMs())
            }
        }
    }

    private fun intervalMs(): Long = intervalSeconds(appContext) * 1000L

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        db = RideDb.get(context)
        val thread = HandlerThread("bike-recorder").apply { start() }
        handler = Handler(thread.looper)
        handler.post { guarded { closeOrphanSessions() } }
    }

    // ---- Entry points, called by BikeLink on any thread --------------------------------------

    /** [address] = the bike's Bluetooth address, given with CONNECTED. */
    fun onState(state: BikeLink.State, address: String? = null) {
        if (!started) return
        when (state) {
            BikeLink.State.CONNECTED -> handler.post { guarded { beginSession(address) } }
            BikeLink.State.DISCONNECTED -> handler.post { guarded { endSession() } }
            BikeLink.State.CONNECTING -> {}
        }
    }

    fun onTelemetry1(t: Protocol.Telemetry1) {
        if (started) handler.post { guarded { handleT1(t) } }
    }

    fun onTelemetry9(t: Protocol.Telemetry9) {
        if (started) handler.post { t9 = t }
    }

    /**
     * Deletes a bike and its history. Runs on the recorder thread, so it comes after any session
     * write that a just-requested disconnect has queued. [onDone] runs on the main thread.
     */
    fun forgetDevice(address: String, onDone: () -> Unit) {
        handler.post {
            guarded { db.forgetDevice(address) }
            Handler(Looper.getMainLooper()).post(onDone)
        }
    }

    // ---- Recorder thread ---------------------------------------------------------------------

    private fun beginSession(address: String?) {
        if (sessionId >= 0) return
        t1 = null
        t9 = null
        firstT1Seen = false
        sessionAddress = address
        if (address != null) db.addDevice(address)
        val values = ContentValues().apply {
            put("connected_at", System.currentTimeMillis())
            put("device_address", address)
        }
        sessionId = db.writableDatabase.insertOrThrow("sessions", null, values)
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, intervalMs())
    }

    private fun handleT1(t: Protocol.Telemetry1) {
        t1 = t
        if (sessionId < 0 || firstT1Seen) return
        firstT1Seen = true

        val prev = previousBatteryEnd()
        val charged = prev != null && t.batteryPct - prev >= CHARGE_JUMP_PCT
        val values = ContentValues().apply {
            put("battery_start", t.batteryPct)
            put("odo_start_km", t.odometerKm)
            put("prev_battery_end", prev)
            put("charge_detected", if (charged) 1 else 0)
        }
        db.writableDatabase.update("sessions", values, "id = ?", arrayOf(sessionId.toString()))
        writeSample(CONNECTED)
    }

    private fun endSession() {
        if (sessionId < 0) return
        handler.removeCallbacks(tick)
        writeSample(DISCONNECTED)
        val values = ContentValues().apply {
            put("disconnected_at", System.currentTimeMillis())
            t1?.let {
                put("battery_end", it.batteryPct)
                put("odo_end_km", it.odometerKm)
            }
            t9?.let {
                put("trip_time_s", it.rideTimeS)
                put("max_kmh", it.maxKmh)
                put("avg_kmh", it.avgKmh)
            }
        }
        db.writableDatabase.update("sessions", values, "id = ?", arrayOf(sessionId.toString()))
        sessionId = -1
        sessionAddress = null
        t1 = null
        t9 = null
    }

    private fun writeSample(status: String) {
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("ts", System.currentTimeMillis())
            put("status", status)
            t1?.let {
                put("battery_pct", it.batteryPct)
                put("speed_kmh", it.speedKmh)
                put("odo_km", it.odometerKm)
                put("trip_km", it.tripKm)
            }
            t9?.let {
                put("trip_time_s", it.rideTimeS)
                put("trip_avg_kmh", it.avgKmh)
                put("trip_max_kmh", it.maxKmh)
            }
        }
        db.writableDatabase.insertOrThrow("samples", null, values)
    }

    /** Battery percentage at the end of the most recent earlier session of the same bike that has one. */
    private fun previousBatteryEnd(): Int? {
        val address = sessionAddress
        val sql = "SELECT battery_end FROM sessions WHERE id < ? AND battery_end IS NOT NULL" +
            (if (address != null) " AND device_address = ?" else "") + " ORDER BY id DESC LIMIT 1"
        val args = if (address != null) arrayOf(sessionId.toString(), address) else arrayOf(sessionId.toString())
        return db.readableDatabase.rawQuery(sql, args).use { if (it.moveToFirst() && !it.isNull(0)) it.getInt(0) else null }
    }

    /** Closes sessions left open by a killed process, using their last sample as the end. */
    private fun closeOrphanSessions() {
        val database = db.writableDatabase
        val open = mutableListOf<Long>()
        database.rawQuery("SELECT id FROM sessions WHERE disconnected_at IS NULL", null).use {
            while (it.moveToNext()) open += it.getLong(0)
        }
        for (id in open) {
            database.rawQuery(
                "SELECT ts, battery_pct, odo_km, trip_time_s, trip_max_kmh, trip_avg_kmh FROM samples WHERE session_id = ? ORDER BY ts DESC LIMIT 1",
                arrayOf(id.toString())
            ).use { c ->
                val values = ContentValues()
                if (c.moveToFirst()) {
                    values.put("disconnected_at", c.getLong(0))
                    if (!c.isNull(1)) values.put("battery_end", c.getInt(1))
                    if (!c.isNull(2)) values.put("odo_end_km", c.getDouble(2))
                    if (!c.isNull(3)) values.put("trip_time_s", c.getInt(3))
                    if (!c.isNull(4)) values.put("max_kmh", c.getDouble(4))
                    if (!c.isNull(5)) values.put("avg_kmh", c.getDouble(5))
                } else {
                    values.put("disconnected_at", System.currentTimeMillis())
                }
                database.update("sessions", values, "id = ?", arrayOf(id.toString()))
            }
            // Closing sample: a copy of the last one, with status 'disconnected'.
            database.execSQL(
                """
                INSERT INTO samples (session_id, ts, status, battery_pct, speed_kmh, odo_km, trip_km, trip_time_s, trip_avg_kmh, trip_max_kmh)
                SELECT session_id, ts, ?, battery_pct, speed_kmh, odo_km, trip_km, trip_time_s, trip_avg_kmh, trip_max_kmh
                FROM samples WHERE session_id = ? ORDER BY ts DESC LIMIT 1
                """.trimIndent(),
                arrayOf<Any>(DISCONNECTED, id)
            )
        }
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            BikeLink.logDbError(e.message ?: e.javaClass.simpleName)
        }
    }
}
