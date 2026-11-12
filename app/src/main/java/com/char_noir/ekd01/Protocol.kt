package com.char_noir.ekd01

import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * EKD01-TB (BikeGo) BLE protocol, reverse engineered from an HCI snoop capture
 * and the BikeGo APK (com.huiye.ebike v2.1.7 / 2.1.0). See PROTOCOL.md for details.
 *
 * Frame: 55 AA | LEN | SRC | DST | CMD | ARG | DATA[LEN] | CRC16 (LE)
 * CRC16 = (~sum(bytes from LEN to the last DATA byte)) and 0xFFFF, little-endian.
 *
 * Auth: the display answers HELLO with a 4-byte challenge. The reply is
 * AES/ECB/NoPadding of (challenge + 12 zero bytes) with a hardcoded key.
 */
object Protocol {

    // ---- Addresses -------------------------------------------------------------------------

    const val PHONE = 0x11
    const val DISPLAY = 0x10
    const val SETTINGS = 0xA5

    /** Navigation-projection subsystem (turn-by-turn on the display). */
    const val SUBSYS_NAV = 0xF1

    // ---- Framing ---------------------------------------------------------------------------

    fun crc(body: ByteArray): ByteArray {
        var sum = 0
        for (b in body) sum += (b.toInt() and 0xFF)
        return leBytes(sum.inv().toLong() and 0xFFFF, 2)
    }

    fun build(src: Int, dst: Int, cmd: Int, arg: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val body = ByteArray(5 + data.size)
        body[0] = data.size.toByte()
        body[1] = src.toByte()
        body[2] = dst.toByte()
        body[3] = cmd.toByte()
        body[4] = arg.toByte()
        System.arraycopy(data, 0, body, 5, data.size)
        return byteArrayOf(0x55, 0xAA.toByte()) + body + crc(body)
    }

    /** Fully manual frame, for probing DST values that aren't mapped yet. */
    fun rawFrame(dst: Int, cmd: Int, arg: Int, data: ByteArray) = build(PHONE, dst, cmd, arg, data)

    // ---- Handshake -------------------------------------------------------------------------

    private val AUTH_KEY = "2CTDU40qNyCgTjb1".toByteArray(Charsets.US_ASCII)

    fun helloFrame() = build(PHONE, DISPLAY, 0x01, 0x00, byteArrayOf(0x04))

    fun computeAuthReply(challenge4: ByteArray): ByteArray {
        require(challenge4.size == 4) { "Challenge must be 4 bytes" }
        val block = challenge4 + ByteArray(12)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(AUTH_KEY, "AES"))
        return cipher.doFinal(block)
    }

    fun authFrame(challenge4: ByteArray) = build(PHONE, DISPLAY, 0x20, 0x00, computeAuthReply(challenge4))

    /**
     * Time sync: one write to DISPLAY, ARG 46 = LOCAL time as an epoch (UTC + the phone's current
     * UTC offset, DST included), 4-byte LE. The display shows this value as-is, so sending plain UTC
     * makes it show UTC. Verified on the display that this single write is enough.
     */
    fun timeSyncFrames(utcEpochSeconds: Long, tzOffsetSeconds: Long = localOffsetSeconds(utcEpochSeconds)): List<ByteArray> = listOf(
        build(PHONE, DISPLAY, 0x02, 0x46, leBytes(utcEpochSeconds + tzOffsetSeconds, 4))
    )

    /** The phone's UTC offset at the given moment, in seconds (3600 in Budapest in winter, 7200 in summer). */
    fun localOffsetSeconds(utcEpochSeconds: Long): Long =
        TimeZone.getDefault().getOffset(utcEpochSeconds * 1000) / 1000L

    // ---- Settings (DST = SETTINGS, CMD 02 write / 01 read) ---------------------------------

    fun setLight(on: Boolean) = build(PHONE, SETTINGS, 0x02, 0xA6, byteArrayOf(if (on) 1 else 0, 0))
    fun setAssist(level: Int) = build(PHONE, SETTINGS, 0x02, 0xA4, byteArrayOf(level.toByte(), 0))
    fun setBrightness(level: Int) = build(PHONE, SETTINGS, 0x02, 0xA7, byteArrayOf(level.toByte()))
    fun setBtUnlock(level: Int) = build(PHONE, SETTINGS, 0x02, 0xE2, byteArrayOf(level.toByte()))
    fun setUnitsMiles(miles: Boolean) = build(PHONE, SETTINGS, 0x02, 0xE0, byteArrayOf(if (miles) 1 else 0))

    /**
     * Read-request for the display's 26-byte config block (BikeGo: readColumn(0xF1, 1), read once at connect).
     * Frame layout assumed to mirror [readRegister]: CMD 01, ARG = register, data = [length]. Unverified.
     * It holds the feature flags BikeGo checks before it uses lock / notification push / brightness etc.
     */
    fun readDisplayConfig() = build(PHONE, SUBSYS_NAV, 0x01, 0x01, byteArrayOf(26))

    /**
     * Decodes the feature flags of the config block. Byte and bit positions come from BikeGo's
     * MeterConfig getters (Dart assembly, index read through Smi tagging), so treat them as a best reading
     * and compare with the raw hex that is logged next to them.
     */
    fun describeConfig(d: ByteArray): String {
        fun bit(i: Int, b: Int) = if (i < d.size) (d[i].toInt() shr b) and 1 == 1 else false
        val flags = listOf(
            "lampSwitch" to bit(3, 0), "pushNotify(E1)" to bit(3, 1), "dateTime" to bit(3, 2), "bleUnlock(0E)" to bit(3, 7),
            "upgrade" to bit(4, 0), "locate" to bit(4, 2), "syncUnbind" to bit(4, 4), "unlockFlag" to bit(4, 5),
            "tirePressure" to bit(5, 0), "unit" to bit(6, 3), "gear" to bit(7, 7),
            "brightness" to bit(8, 7), "bleUnlockLevel(E2)" to bit(9, 7), "errorCode" to bit(11, 7)
        )
        val cat = d.getOrNull(1)?.toInt()?.and(0xFF)
        return "category=$cat  " + flags.joinToString(" ") { (n, v) -> "$n=${if (v) 1 else 0}" }
    }

    /** Auto-off / sleep timeout in minutes (confirmed live: writing 1 gave a 1-minute timeout). */
    fun setAutoOffMinutes(minutes: Int) = build(PHONE, SETTINGS, 0x02, 0xB4, leBytes(minutes.toLong(), 2))

    /**
     * SETTINGS register table: register -> payload length the firmware expects
     * (from the BikeGo app's O3/v.java).
     *
     * Confirmed meanings: 0xA4 assist, 0xA6 light, 0xA7 brightness, 0xE2 BT unlock,
     * 0xE0 units (0 = km, 1 = mi), 0xB4 auto-off minutes. 0xE1 is "message notification" in
     * BikeGo (1 byte, on/off), not tested live.
     *
     * Not usable on this firmware: 0xA0/A2/A8/AA/AC/AE/B0 are rejected (ack status 2).
     * 0xB6 is accepted but corrupts brightness (firmware buffer overflow), so don't write it.
     */
    val SETTINGS_REGISTERS: Map<Int, Int> = mapOf(
        0x00 to 24, 0x18 to 24, 0x30 to 24,
        0x48 to 16, 0x58 to 16, 0x68 to 16, 0x78 to 16,
        0x88 to 24,
        0xA0 to 2, 0xA2 to 2, 0xA4 to 2, 0xA6 to 2, 0xA7 to 1,
        0xA8 to 2, 0xAA to 2, 0xAC to 2, 0xAE to 2,
        0xB0 to 4, 0xB4 to 2, 0xB6 to 27,
        0xE0 to 1, 0xE1 to 1, 0xE2 to 1
    )

    /** Write a SETTINGS register, checking the payload length when the register is known. */
    fun setRegister(reg: Int, data: ByteArray): ByteArray {
        val expected = SETTINGS_REGISTERS[reg]
        require(expected == null || data.size == expected) {
            "Register 0x%02X expects %d bytes, got %d".format(reg, expected, data.size)
        }
        return build(PHONE, SETTINGS, 0x02, reg, data)
    }

    /** Read-request for a SETTINGS register (CMD 01). The reply arrives as CMD 04. */
    fun readRegister(reg: Int, knownLen: Int? = null): ByteArray {
        val len = knownLen ?: SETTINGS_REGISTERS[reg]
            ?: throw IllegalArgumentException("Unknown length for 0x%02X - pass knownLen explicitly".format(reg))
        return build(PHONE, SETTINGS, 0x01, reg, byteArrayOf(len.toByte()))
    }

    // ---- Navigation (DST = SUBSYS_NAV, CMD 03, ARG 00) -------------------------------------
    //
    // Fire-and-forget pushes with payload [seq, msgType, ...]:
    //   msgType 1 = start nav mode, 2 = route-progress update, 3 = stop nav mode.
    // Confirmed live on the display (2026-11-08): a navUpdate with "right turn in 200 m" values
    // rendered the correct arrow and distances. nextNext* fields have no visible slot on that
    // screen state. The register scheme (reg 1 / reg 2 on this DST) is a dead end; don't use it.

    private var navSeq = 0

    /** Maps a Mapbox maneuver modifier to the display's icon code (0 = unknown). */
    fun navIconForModifier(modifier: String?): Int = when (modifier) {
        "straight" -> 1
        "left" -> 2
        "right" -> 3
        "slight left" -> 4
        "slight right" -> 5
        "sharp left" -> 6
        "sharp right" -> 7
        "uturn" -> 8
        "COMPLETE" -> 10
        else -> if (modifier.isNullOrEmpty()) 10 else 0
    }

    fun navStart(): ByteArray {
        navSeq = 1
        return build(PHONE, SUBSYS_NAV, 0x03, 0x00, byteArrayOf(1, 1))
    }

    fun navStop(): ByteArray {
        val seq = navSeq + 1
        navSeq = 0
        return build(PHONE, SUBSYS_NAV, 0x03, 0x00, byteArrayOf(seq.toByte(), 3))
    }

    /**
     * Turn-by-turn push; call on every route-progress tick (the sequence number auto-increments).
     * Payload after [seq, 2]: curStepRetain (3B LE), tmpIcon (1B), nextStep (3B LE), nextIcon (1B),
     * nextNextStep (3B LE), nextNextIcon (1B), pathRetain (4B LE). Distances in meters.
     */
    fun navUpdate(
        tmpIconType: Int,           // icon for the maneuver being approached
        curStepRetainDistance: Int, // counts down as you close in on it
        nextStepDistance: Int,      // length of that step (does not count down)
        nextIconType: Int,          // icon for the maneuver after that
        nextNextStepDistance: Int,  // length of the step after that
        nextNextIconType: Int,      // icon for the maneuver two after that
        pathRetainDistance: Int     // total remaining route distance
    ): ByteArray {
        navSeq += 1
        val data = byteArrayOf(navSeq.toByte(), 2) +
            leBytes(curStepRetainDistance.toLong(), 3) + byteArrayOf(tmpIconType.toByte()) +
            leBytes(nextStepDistance.toLong(), 3) + byteArrayOf(nextIconType.toByte()) +
            leBytes(nextNextStepDistance.toLong(), 3) + byteArrayOf(nextNextIconType.toByte()) +
            leBytes(pathRetainDistance.toLong(), 4)
        return build(PHONE, SUBSYS_NAV, 0x03, 0x00, data)
    }

    // ---- Parsing ---------------------------------------------------------------------------

    data class Frame(val src: Int, val dst: Int, val cmd: Int, val arg: Int, val data: ByteArray)

    /** Reassembles frames split across BLE packets; frames with a bad CRC are dropped. */
    class FrameParser {
        private var buf = ByteArray(0)

        fun feed(chunk: ByteArray): List<Frame> {
            buf += chunk
            val out = mutableListOf<Frame>()
            while (true) {
                val i = indexOfHeader(buf)
                if (i < 0) { buf = ByteArray(0); break }
                if (i > 0) buf = buf.copyOfRange(i, buf.size)
                if (buf.size < 3) break
                val n = 9 + (buf[2].toInt() and 0xFF)
                if (buf.size < n) break
                val fr = buf.copyOfRange(0, n)
                buf = buf.copyOfRange(n, buf.size)
                val body = fr.copyOfRange(2, fr.size - 2)
                if (crc(body).contentEquals(fr.copyOfRange(fr.size - 2, fr.size))) {
                    out.add(
                        Frame(
                            src = fr[3].toInt() and 0xFF,
                            dst = fr[4].toInt() and 0xFF,
                            cmd = fr[5].toInt() and 0xFF,
                            arg = fr[6].toInt() and 0xFF,
                            data = fr.copyOfRange(7, fr.size - 2)
                        )
                    )
                }
            }
            return out
        }

        private fun indexOfHeader(b: ByteArray): Int {
            for (i in 0 until b.size - 1) if (b[i] == 0x55.toByte() && b[i + 1] == 0xAA.toByte()) return i
            return -1
        }
    }

    // ---- Telemetry (DISPLAY -> phone, CMD 06, ~1 Hz) ---------------------------------------

    data class Telemetry1(
        val light: Boolean,
        val assistLevel: Int,
        val batteryPct: Int,
        val tripKm: Double,
        val odometerKm: Double,
        val speedKmh: Double,
        /** Byte 8: the display's speed-limit unlock flag (non-zero = unlocked, speed above 25 km/h allowed). */
        val speedUnlocked: Boolean = false,
        /** Byte 6: number of assist levels the display offers ("wholeGear" in the BikeGo app). */
        val gearCount: Int = 0,
        /** Byte 3: BikeGo shows "Bike Unlocked" when non-zero and "Bike Locked" when 0. */
        val bikeUnlocked: Boolean = false,
        /** Byte 2 bit 0 ("pwdLockStatus") and bit 1 ("bleLockStatus") in BikeGo. */
        val passwordLock: Boolean = false,
        val bleLock: Boolean = false
    )

    data class Telemetry9(val rideTimeS: Int, val avgKmh: Double, val maxKmh: Double, val btUnlockLevel: Int)

    private fun leUInt(data: ByteArray, off: Int, len: Int): Long {
        var v = 0L
        for (i in 0 until len) v = v or ((data[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    fun decodeTelemetry1(f: Frame): Telemetry1? {
        if (f.src != DISPLAY || f.cmd != 0x06 || f.arg != 0x01 || f.data.size != 21) return null
        return Telemetry1(
            light = f.data[4].toInt() != 0,
            assistLevel = f.data[5].toInt() and 0xFF,
            batteryPct = f.data[7].toInt() and 0xFF,
            tripKm = leUInt(f.data, 11, 4) / 100.0, // 4 bytes LE, like the BikeGo app
            odometerKm = leUInt(f.data, 15, 4) / 100.0,
            speedKmh = leUInt(f.data, 9, 2) / 10.0, // bytes 9 (low) and 10 (high), LE, x10 (100 -> 10.0 km/h)
            speedUnlocked = f.data[8].toInt() != 0,
            gearCount = f.data[6].toInt() and 0xFF,
            bikeUnlocked = f.data[3].toInt() != 0,
            passwordLock = (f.data[2].toInt() and 1) != 0,
            bleLock = (f.data[2].toInt() and 2) != 0
        )
    }

    fun decodeTelemetry9(f: Frame): Telemetry9? {
        if (f.src != DISPLAY || f.cmd != 0x06 || f.arg != 0x09 || f.data.size != 16) return null
        return Telemetry9(
            rideTimeS = leUInt(f.data, 0, 4).toInt(), // 4 bytes LE; 2 bytes would overflow after 18 h
            avgKmh = leUInt(f.data, 4, 2) / 100.0,
            maxKmh = leUInt(f.data, 6, 2) / 10.0,
            btUnlockLevel = f.data[15].toInt() and 0xFF
        )
    }

    // ---- Helpers ---------------------------------------------------------------------------

    fun leBytes(value: Long, byteCount: Int): ByteArray =
        ByteArray(byteCount) { i -> ((value shr (8 * i)) and 0xFF).toByte() }

    fun hex(b: ByteArray): String = b.joinToString(" ") { String.format(Locale.US, "%02X", it) }
}
