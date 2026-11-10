package com.adilet.ekd01

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One connection to the bike. Times are epoch ms (UTC); [disconnectedAt] is null while still connected. */
data class SessionRow(
    val id: Long,
    val deviceAddress: String?,
    val connectedAt: Long,
    val disconnectedAt: Long?,
    val batteryStart: Int?,
    val batteryEnd: Int?,
    val prevBatteryEnd: Int?,
    val chargeDetected: Boolean,
    val odoStartKm: Double?,
    val odoEndKm: Double?,
    val tripTimeS: Int?,
    val maxKmh: Double?,
    val avgKmh: Double?
)

/** A bike (display) the app has talked to. [nickname] is chosen by the user; [lastConnectedAt] is null if never connected. */
data class DeviceRow(
    val address: String,
    val nickname: String?,
    val addedAt: Long,
    val lastConnectedAt: Long?,
    val sessionCount: Int
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
 * devices:  the bikes the app knows (Bluetooth address + the user's nickname).
 * sessions: one row per connection to a bike (which bike, connect time, disconnect time, battery and
 *           odometer at both ends, and whether the battery looks like it was charged since the last
 *           session of the same bike).
 * samples:  one row per minute while connected, plus a final row with status 'disconnected'.
 */
class RideDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "ekd01.db", null, 3) {

    private val appContext: Context = context.applicationContext

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createDevicesTable(db)
        db.execSQL(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_address TEXT,
                connected_at INTEGER NOT NULL,
                disconnected_at INTEGER,
                battery_start INTEGER,
                battery_end INTEGER,
                prev_battery_end INTEGER,
                charge_detected INTEGER NOT NULL DEFAULT 0,
                odo_start_km REAL,
                odo_end_km REAL,
                trip_time_s INTEGER,
                max_kmh REAL,
                avg_kmh REAL
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
        db.execSQL("CREATE INDEX idx_sessions_device ON sessions(device_address, id)")
    }

    private fun createDevicesTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE devices (
                address TEXT PRIMARY KEY,
                nickname TEXT,
                added_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v2: rides belong to a bike. Everything recorded so far came from the one bike the app
            // had saved, so it is attached to that one.
            createDevicesTable(db)
            db.execSQL("ALTER TABLE sessions ADD COLUMN device_address TEXT")
            val saved = BackgroundMode.savedAddress(appContext)
            if (saved != null) {
                db.execSQL("INSERT OR IGNORE INTO devices (address, nickname, added_at) VALUES (?, NULL, ?)", arrayOf<Any>(saved, System.currentTimeMillis()))
                db.execSQL("UPDATE sessions SET device_address = ?", arrayOf<Any>(saved))
            }
            db.execSQL("CREATE INDEX idx_sessions_device ON sessions(device_address, id)")
        }
        if (oldVersion < 3) {
            // v3: trip time, max and average speed per ride (the display's trip values at the end of the ride).
            db.execSQL("ALTER TABLE sessions ADD COLUMN trip_time_s INTEGER")
            db.execSQL("ALTER TABLE sessions ADD COLUMN max_kmh REAL")
            db.execSQL("ALTER TABLE sessions ADD COLUMN avg_kmh REAL")
            for ((target, source) in listOf("trip_time_s" to "trip_time_s", "max_kmh" to "trip_max_kmh", "avg_kmh" to "trip_avg_kmh")) {
                db.execSQL(
                    "UPDATE sessions SET $target = (SELECT s.$source FROM samples s WHERE s.session_id = sessions.id " +
                        "AND s.$source IS NOT NULL ORDER BY s.ts DESC, s.id DESC LIMIT 1)"
                )
            }
        }
    }

    // ---- Reading (call from a background thread) ---------------------------------------------

    /** Newest first. [deviceAddress] = only that bike's rides; null = all rides. */
    fun loadSessions(deviceAddress: String? = null, limit: Int = 200): List<SessionRow> {
        val where = if (deviceAddress != null) " WHERE device_address = ?" else ""
        val args = if (deviceAddress != null) arrayOf(deviceAddress, limit.toString()) else arrayOf(limit.toString())
        return readableDatabase.rawQuery("$SESSION_COLUMNS$where ORDER BY id DESC LIMIT ?", args).use { c ->
            buildList<SessionRow> { while (c.moveToNext()) add(c.toSession()) }
        }
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

    // ---- Bikes ---------------------------------------------------------------------------------

    /** Adds the bike if it is not known yet (keeps an existing nickname). */
    fun addDevice(address: String) {
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO devices (address, nickname, added_at) VALUES (?, NULL, ?)",
            arrayOf<Any>(address, System.currentTimeMillis())
        )
    }

    /** A blank [nickname] clears it. */
    fun renameDevice(address: String, nickname: String) {
        addDevice(address)
        val values = ContentValues().apply { if (nickname.isBlank()) putNull("nickname") else put("nickname", nickname.trim()) }
        writableDatabase.update("devices", values, "address = ?", arrayOf(address))
    }

    fun deviceNickname(address: String): String? =
        readableDatabase.rawQuery("SELECT nickname FROM devices WHERE address = ?", arrayOf(address)).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
        }

    /** Known bikes, most recently used first. */
    fun loadDevices(): List<DeviceRow> =
        readableDatabase.rawQuery(
            """
            SELECT d.address, d.nickname, d.added_at,
                   (SELECT MAX(s.connected_at) FROM sessions s WHERE s.device_address = d.address),
                   (SELECT COUNT(*) FROM sessions s WHERE s.device_address = d.address)
            FROM devices d
            ORDER BY COALESCE((SELECT MAX(s.connected_at) FROM sessions s WHERE s.device_address = d.address), d.added_at) DESC
            """.trimIndent(),
            null
        ).use { c ->
            buildList<DeviceRow> {
                while (c.moveToNext()) add(
                    DeviceRow(
                        address = c.getString(0), nickname = if (c.isNull(1)) null else c.getString(1),
                        addedAt = c.getLong(2), lastConnectedAt = if (c.isNull(3)) null else c.getLong(3),
                        sessionCount = c.getInt(4)
                    )
                )
            }
        }

    /** Removes the bike and all its rides and readings. */
    fun forgetDevice(address: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("sessions", "device_address = ?", arrayOf(address)) // samples go with them (cascade)
            db.delete("devices", "address = ?", arrayOf(address))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)
    private fun Cursor.doubleOrNull(i: Int): Double? = if (isNull(i)) null else getDouble(i)
    private fun Cursor.toSession() = SessionRow(
        id = getLong(0), connectedAt = getLong(1), disconnectedAt = if (isNull(2)) null else getLong(2),
        batteryStart = intOrNull(3), batteryEnd = intOrNull(4), prevBatteryEnd = intOrNull(5),
        chargeDetected = getInt(6) != 0, odoStartKm = doubleOrNull(7), odoEndKm = doubleOrNull(8),
        deviceAddress = if (isNull(9)) null else getString(9),
        tripTimeS = intOrNull(10), maxKmh = doubleOrNull(11), avgKmh = doubleOrNull(12)
    )

    companion object {
        private const val SESSION_COLUMNS =
            "SELECT id, connected_at, disconnected_at, battery_start, battery_end, prev_battery_end, " +
                "charge_detected, odo_start_km, odo_end_km, device_address, trip_time_s, max_kmh, avg_kmh FROM sessions"

        @Volatile private var instance: RideDb? = null

        /** One shared instance for the whole process (the recorder writes, the History screen reads). */
        fun get(context: Context): RideDb =
            instance ?: synchronized(this) { instance ?: RideDb(context).also { instance = it } }
    }
}
