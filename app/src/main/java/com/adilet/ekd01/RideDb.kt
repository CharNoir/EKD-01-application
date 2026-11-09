package com.adilet.ekd01

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One connection to the bike. Times are epoch ms (UTC); [disconnectedAt] is null while still connected. */
data class SessionRow(
    val id: Long,
    val connectedAt: Long,
    val disconnectedAt: Long?,
    val batteryStart: Int?,
    val batteryEnd: Int?,
    val prevBatteryEnd: Int?,
    val chargeDetected: Boolean,
    val odoStartKm: Double?,
    val odoEndKm: Double?
)

/** One recorded reading. [status] is "connected" or "disconnected". */
data class SampleRow(
    val ts: Long,
    val status: String,
    val batteryPct: Int?,
    val speedKmh: Double?,
    val odoKm: Double?,
    val tripKm: Double?,
    val tripTimeS: Int?,
    val tripAvgKmh: Double?,
    val tripMaxKmh: Double?
)

/**
 * Local ride history (file: databases/ekd01.db). All timestamps are epoch milliseconds in UTC.
 *
 * sessions: one row per connection to the bike (connect time, disconnect time, battery and odometer
 *           at both ends, and whether the battery looks like it was charged since the last session).
 * samples:  one row per minute while connected, plus a final row with status 'disconnected'.
 */
class RideDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "ekd01.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                connected_at INTEGER NOT NULL,
                disconnected_at INTEGER,
                battery_start INTEGER,
                battery_end INTEGER,
                prev_battery_end INTEGER,
                charge_detected INTEGER NOT NULL DEFAULT 0,
                odo_start_km REAL,
                odo_end_km REAL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE samples (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                ts INTEGER NOT NULL,
                status TEXT NOT NULL,
                battery_pct INTEGER,
                speed_kmh REAL,
                odo_km REAL,
                trip_km REAL,
                trip_time_s INTEGER,
                trip_avg_kmh REAL,
                trip_max_kmh REAL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_samples_session_ts ON samples(session_id, ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Nothing to migrate yet. Add ALTER TABLE steps here when the schema version is raised.
    }

    // ---- Reading (call from a background thread) ---------------------------------------------

    /** Newest first. */
    fun loadSessions(limit: Int = 200): List<SessionRow> =
        readableDatabase.rawQuery("$SESSION_COLUMNS ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            buildList<SessionRow> { while (c.moveToNext()) add(c.toSession()) }
        }

    fun loadSession(id: Long): SessionRow? =
        readableDatabase.rawQuery("$SESSION_COLUMNS WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.toSession() else null
        }

    /** Oldest first. */
    fun loadSamples(sessionId: Long): List<SampleRow> =
        readableDatabase.rawQuery(
            "SELECT ts, status, battery_pct, speed_kmh, odo_km, trip_km, trip_time_s, trip_avg_kmh, trip_max_kmh " +
                "FROM samples WHERE session_id = ? ORDER BY ts, id",
            arrayOf(sessionId.toString())
        ).use { c ->
            buildList<SampleRow> {
                while (c.moveToNext()) add(
                    SampleRow(
                        ts = c.getLong(0), status = c.getString(1),
                        batteryPct = c.intOrNull(2), speedKmh = c.doubleOrNull(3), odoKm = c.doubleOrNull(4),
                        tripKm = c.doubleOrNull(5), tripTimeS = c.intOrNull(6),
                        tripAvgKmh = c.doubleOrNull(7), tripMaxKmh = c.doubleOrNull(8)
                    )
                )
            }
        }

    private fun Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)
    private fun Cursor.doubleOrNull(i: Int): Double? = if (isNull(i)) null else getDouble(i)
    private fun Cursor.toSession() = SessionRow(
        id = getLong(0), connectedAt = getLong(1), disconnectedAt = if (isNull(2)) null else getLong(2),
        batteryStart = intOrNull(3), batteryEnd = intOrNull(4), prevBatteryEnd = intOrNull(5),
        chargeDetected = getInt(6) != 0, odoStartKm = doubleOrNull(7), odoEndKm = doubleOrNull(8)
    )

    companion object {
        private const val SESSION_COLUMNS =
            "SELECT id, connected_at, disconnected_at, battery_start, battery_end, prev_battery_end, " +
                "charge_detected, odo_start_km, odo_end_km FROM sessions"

        @Volatile private var instance: RideDb? = null

        /** One shared instance for the whole process (the recorder writes, the History screen reads). */
        fun get(context: Context): RideDb =
            instance ?: synchronized(this) { instance ?: RideDb(context).also { instance = it } }
    }
}
