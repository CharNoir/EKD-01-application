package com.char_noir.ekd01

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single BLE connection to the display. It lives in the process, not in an Activity, so the UI
 * ([MainActivity]) and the background [BikeService] share it. Listener callbacks always run on the
 * main thread.
 *
 * Call [init] before anything else (every entry point does: activity, service, receivers).
 */
object BikeLink {

    const val DEVICE_NAME = "EKD01-TB"
    private val SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val WRITE_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    private val NOTIFY_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private const val MAX_LOG_CHARS = 200_000

    /** DISCONNECTED -> CONNECTING (scanning, GATT connect, auth) -> CONNECTED (auth accepted). */
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    interface Listener {
        fun onStateChanged(state: State) {}
        fun onTelemetry1(t: Protocol.Telemetry1) {}
        fun onTelemetry9(t: Protocol.Telemetry9) {}
        fun onLogLine(line: String) {}
        fun onGesture(sequence: String) {}
    }

    private lateinit var appContext: Context
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile var state = State.DISCONNECTED
        private set

    @Volatile var lastT1: Protocol.Telemetry1? = null
        private set
    @Volatile var lastT9: Protocol.Telemetry9? = null
        private set
    @Volatile var lastT1Raw: ByteArray? = null
        private set
    @Volatile var lastT9Raw: ByteArray? = null
        private set

    /** T1/T9 arrive several times a second; they are always decoded, this only gates logging. */
    @Volatile var showTelemetryLog = false

    /** Main-thread only. Everything is logged here; the Developer screen mirrors it. */
    val logBuffer = StringBuilder()

    @Volatile private var gatt: BluetoothGatt? = null

    /** True while [gatt] is a background (autoConnect) request that has not connected yet. */
    @Volatile private var autoPending = false

    /** Bluetooth address of the bike we are connected to (set when authenticated, cleared on disconnect). */
    @Volatile var connectedAddress: String? = null
        private set

    /** A bike found by [discoverDevices]. */
    data class Discovered(val address: String, val name: String, val rssi: Int)

    /** True while waiting in the background for the bike to appear. */
    val isArmed: Boolean get() = autoPending

    /** When the user last tapped "disconnect" (SystemClock.elapsedRealtime), so the service can tell that from a lost link. */
    @Volatile var lastManualDisconnectAt = -1_000_000L
        private set
    @Volatile private var parser = Protocol.FrameParser()
    private val writeQueue = ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var writeInFlight = false

    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var scanTimeout: Runnable? = null
    private var scanFinished: ((Boolean) -> Unit)? = null

    private var discoverScanner: BluetoothLeScanner? = null
    private var discoverCallback: ScanCallback? = null
    private var discoverTimeout: Runnable? = null
    private var discoverDone: (() -> Unit)? = null
    val isDiscovering: Boolean get() = discoverCallback != null

    fun init(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
            LogStore.init(appContext)
            LogStore.append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()) + "  " + appContext.getString(R.string.log_process_started) + "\n")
            BikeRecorder.start(appContext)
            KnockStore.load(appContext)
        }
    }

    fun addListener(l: Listener) { listeners.addIfAbsent(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    // ---- Logging ---------------------------------------------------------------------------

    fun log(msg: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "${stamp.substring(11)}  $msg\n" // the on-screen log shows the time only
        LogStore.append("$stamp  $msg\n")
        main.post {
            logBuffer.append(line)
            if (logBuffer.length > MAX_LOG_CHARS) logBuffer.delete(0, MAX_LOG_CHARS / 2)
            listeners.forEach { it.onLogLine(line) }
        }
    }

    fun clearLog() { logBuffer.clear() }

    fun logDbError(message: String) = log(str(R.string.log_db_error, message))

    private fun str(id: Int, vararg args: Any): String = appContext.getString(id, *args)

    /** An assist-level knock was recognized ([KnockDetector]); listeners are told on the main thread. */
    fun notifyGesture(sequence: String) = main.post { listeners.forEach { it.onGesture(sequence) } }

    private fun setState(s: State) {
        if (state == s) return
        state = s
        if (s == State.DISCONNECTED) {
            KnockDetector.reset()
            TelemetryStats.onDisconnect()
        }
        BikeRecorder.onState(s, connectedAddress)
        main.post { listeners.forEach { it.onStateChanged(s) } }
    }

    // ---- Scan + connect --------------------------------------------------------------------

    /**
     * Scans for the display and connects to the first match. [onFinished] runs on the main thread
     * once the scan is over: true = a display was found and a GATT connect was started (watch
     * [State] for the outcome), false = nothing found, scan failed, Bluetooth off, or cancelled.
     *
     * [filtered] = background mode: uses hardware scan filters (the saved address and the device
     * name). Android 8.1+ delivers no results for unfiltered scans while the screen is off, so a
     * background scan must be filtered. Foreground scans stay unfiltered, as before.
     */
    @SuppressLint("MissingPermission")
    fun scanAndConnect(timeoutMs: Long, filtered: Boolean, onFinished: ((found: Boolean) -> Unit)? = null) {
        if (autoPending) cancelAutoConnect()
        if (gatt != null || scanCallback != null) {
            if (onFinished != null) main.post { onFinished(gatt != null) }
            return
        }
        scanFinished = onFinished

        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            log(str(R.string.bluetooth_off))
            finishScan(false)
            return
        }
        val leScanner = adapter.bluetoothLeScanner
        if (leScanner == null) {
            log(str(R.string.bluetooth_off))
            finishScan(false)
            return
        }

        // With a bike selected, only that bike counts. Without one, any display with the right name does.
        val active = BackgroundMode.savedAddress(appContext)
        val filters = if (filtered) {
            buildList<ScanFilter> {
                if (active != null) add(ScanFilter.Builder().setDeviceAddress(active).build())
                else add(ScanFilter.Builder().setDeviceName(DEVICE_NAME).build())
            }
        } else emptyList()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        log(str(R.string.log_scanning, DEVICE_NAME))
        setState(State.CONNECTING)

        val handled = AtomicBoolean(false)
        val seen = mutableSetOf<String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name
                val addr = result.device.address
                if (!filtered && seen.add(addr)) {
                    log("seen: name=${name ?: "(null)"} addr=$addr rssi=${result.rssi}")
                }
                val match = when {
                    active != null -> addr.equals(active, ignoreCase = true)
                    else -> filtered || (name != null && name.trim().equals(DEVICE_NAME, ignoreCase = true))
                }
                if (match && handled.compareAndSet(false, true)) {
                    stopScanInternal()
                    log(str(R.string.log_found, DEVICE_NAME))
                    connectTo(result.device)
                    finishScan(true)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                if (handled.compareAndSet(false, true)) {
                    log(str(R.string.log_scan_failed, errorCode))
                    stopScanInternal()
                    if (gatt == null) setState(State.DISCONNECTED)
                    finishScan(false)
                }
            }
        }
        scanner = leScanner
        scanCallback = cb
        val timeout = Runnable {
            if (handled.compareAndSet(false, true)) {
                stopScanInternal()
                if (gatt == null) setState(State.DISCONNECTED)
                finishScan(false)
            }
        }
        scanTimeout = timeout
        main.postDelayed(timeout, timeoutMs)
        try {
            leScanner.startScan(filters, settings, cb)
        } catch (e: SecurityException) {
            handled.set(true)
            stopScanInternal()
            setState(State.DISCONNECTED)
            finishScan(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal() {
        scanTimeout?.let { main.removeCallbacks(it) }
        scanTimeout = null
        val cb = scanCallback
        scanCallback = null
        if (cb != null) try { scanner?.stopScan(cb) } catch (_: Exception) {}
        scanner = null
    }

    private fun finishScan(found: Boolean) {
        val f = scanFinished ?: return
        scanFinished = null
        main.post { f(found) }
    }

    @SuppressLint("MissingPermission")
    private fun connectTo(device: BluetoothDevice) {
        gatt = device.connectGatt(appContext, false, gattCallback)
    }

    /** Connects straight to the bike with this address (foreground connect, no scan). False if it could not be started. */
    @SuppressLint("MissingPermission")
    fun connectToAddress(address: String): Boolean {
        if (autoPending) cancelAutoConnect()
        if (gatt != null || scanCallback != null) return false
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            log(str(R.string.bluetooth_off))
            return false
        }
        return try {
            val device = adapter.getRemoteDevice(address)
            log(str(R.string.log_connecting_to, address))
            setState(State.CONNECTING)
            connectTo(device)
            true
        } catch (e: Exception) {
            gatt = null
            setState(State.DISCONNECTED)
            false
        }
    }

    /**
     * Scans for nearby displays (by name) and reports each one once through [onFound]; [onDone] runs
     * when the scan ends by itself (timeout or failure), not when [stopDiscovery] cancels it. Both run
     * on the main thread. Returns false if the scan could not be started.
     */
    @SuppressLint("MissingPermission")
    fun discoverDevices(timeoutMs: Long, onFound: (Discovered) -> Unit, onDone: () -> Unit): Boolean {
        if (isDiscovering) stopDiscovery()
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        val leScanner = if (adapter != null && adapter.isEnabled) adapter.bluetoothLeScanner else null
        if (leScanner == null) {
            log(str(R.string.bluetooth_off))
            return false
        }
        val seen = mutableSetOf<String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name?.trim() ?: return
                if (!name.equals(DEVICE_NAME, ignoreCase = true)) return
                if (seen.add(result.device.address)) onFound(Discovered(result.device.address, name, result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                log(str(R.string.log_scan_failed, errorCode))
                finishDiscovery(notify = true)
            }
        }
        discoverScanner = leScanner
        discoverCallback = cb
        discoverDone = onDone
        val timeout = Runnable { finishDiscovery(notify = true) }
        discoverTimeout = timeout
        main.postDelayed(timeout, timeoutMs)
        return try {
            leScanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
            true
        } catch (e: SecurityException) {
            finishDiscovery(notify = false)
            false
        }
    }

    fun stopDiscovery() = finishDiscovery(notify = false)

    @SuppressLint("MissingPermission")
    private fun finishDiscovery(notify: Boolean) {
        discoverTimeout?.let { main.removeCallbacks(it) }
        discoverTimeout = null
        val cb = discoverCallback
        discoverCallback = null
        if (cb != null) try { discoverScanner?.stopScan(cb) } catch (_: Exception) {}
        discoverScanner = null
        val done = discoverDone
        discoverDone = null
        if (notify) done?.invoke()
    }

    /** The selected bike changed: drop whatever link, wait or scan belongs to the old one and forget its readings. */
    fun dropForBikeChange() {
        disconnect()
        lastT1 = null
        lastT9 = null
        lastT1Raw = null
        lastT9Raw = null
    }

    /**
     * Waits for the bike without scanning: asks the Bluetooth controller to connect as soon as the
     * display with the saved address appears (GATT autoConnect). The controller does the waiting, so
     * this costs almost no power, and it works for as long as this process and Bluetooth are alive.
     * Returns false if it could not be armed (no saved address yet, Bluetooth off, permission missing).
     */
    @SuppressLint("MissingPermission")
    fun armAutoConnect(): Boolean {
        if (gatt != null || scanCallback != null) return autoPending
        val address = BackgroundMode.savedAddress(appContext) ?: return false
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) return false
        return try {
            autoPending = true
            gatt = adapter.getRemoteDevice(address).connectGatt(appContext, true, gattCallback)
            log(str(R.string.log_auto_armed))
            true
        } catch (e: Exception) {
            autoPending = false
            gatt = null
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun cancelAutoConnect() {
        val g = gatt
        gatt = null
        autoPending = false
        if (g != null) try { g.disconnect(); g.close() } catch (_: Exception) {}
    }

    /** Cancels a scan or connection in progress, or drops the live link. [manual] = the user asked for it. */
    @SuppressLint("MissingPermission")
    fun disconnect(manual: Boolean = false) {
        if (manual) lastManualDisconnectAt = SystemClock.elapsedRealtime()
        stopScanInternal()
        autoPending = false
        val g = gatt
        gatt = null
        if (g != null) try { g.disconnect(); g.close() } catch (_: Exception) {}
        writeQueue.clear()
        writeInFlight = false
        parser = Protocol.FrameParser()
        connectedAddress = null
        setState(State.DISCONNECTED)
        log(str(R.string.log_disconnected))
        finishScan(false)
    }

    // ---- GATT ------------------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                autoPending = false
                setState(State.CONNECTING) // the bike showed up (or the foreground connect succeeded)
                log(str(R.string.log_connected_discovering))
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log(str(R.string.log_link_dropped, status))
                try { g.close() } catch (_: Exception) {}
                if (gatt === g) { gatt = null; autoPending = false }
                writeQueue.clear()
                writeInFlight = false
                parser = Protocol.FrameParser()
                connectedAddress = null
                setState(State.DISCONNECTED)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE_UUID)
            if (service == null) { log(str(R.string.log_uart_not_found)); return }
            val notifyChar = service.getCharacteristic(NOTIFY_UUID)
            g.setCharacteristicNotification(notifyChar, true)
            val cccd = notifyChar.getDescriptor(CCCD_UUID)
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(cccd)
            log(str(R.string.log_services_discovered))
            // HELLO is sent once notifications are confirmed enabled, see onDescriptorWrite.
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            log(str(R.string.log_notifications_enabled))
            queueWrite(Protocol.helloFrame())
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleIncoming(characteristic.value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeInFlight = false
            processWriteQueue()
        }
    }

    private fun handleIncoming(data: ByteArray) {
        for (f in parser.feed(data)) {
            when {
                f.cmd == 0x04 && f.arg == 0x00 && f.data.size == 4 -> {
                    log("Challenge: ${Protocol.hex(f.data)}")
                    queueWrite(Protocol.authFrame(f.data))
                }
                f.cmd == 0x20 && f.arg == 0x00 -> {
                    val ok = f.data.isNotEmpty() && f.data[0].toInt() == 0
                    log(if (ok) str(R.string.log_auth_accepted) else str(R.string.log_auth_rejected, Protocol.hex(f.data)))
                    if (ok) {
                        val address = gatt?.device?.address
                        connectedAddress = address
                        // First bike ever: it becomes the selected one. Otherwise the selection is the user's choice.
                        if (address != null && BackgroundMode.savedAddress(appContext) == null) BackgroundMode.saveAddress(appContext, address)
                        setState(State.CONNECTED)
                        val now = System.currentTimeMillis() / 1000
                        for (fr in Protocol.timeSyncFrames(now)) queueWrite(fr)
                    }
                }
                f.cmd == 0x05 -> {
                    // Write ack: data[0] status byte, 0=ok, 1/2=various errors (per decompiled app).
                    val status = f.data.getOrNull(0)?.toInt()?.and(0xFF)
                    if (status != null && status != 0) {
                        log("ACK reg=0x%02X status=%d (non-zero = rejected)".format(f.arg, status))
                    }
                    // status==0 (plain success) stays quiet to not spam the log during normal use.
                }
                else -> {
                    val t1 = Protocol.decodeTelemetry1(f)
                    val t9 = Protocol.decodeTelemetry9(f)
                    if (t1 != null) {
                        lastT1 = t1
                        lastT1Raw = f.data
                        val elapsed = SystemClock.elapsedRealtime()
                        TelemetryStats.onT1(t1.assistLevel, elapsed, System.currentTimeMillis())
                        KnockDetector.onLevel(appContext, t1.assistLevel, elapsed)
                        BikeRecorder.onTelemetry1(t1)
                        main.post { listeners.forEach { it.onTelemetry1(t1) } }
                        if (showTelemetryLog) {
                            log("T1  light=${t1.light} assist=${t1.assistLevel} batt=${t1.batteryPct}% speed=${t1.speedKmh}km/h unlocked=${t1.speedUnlocked} trip=${t1.tripKm}km odo=${t1.odometerKm}km")
                        }
                    } else if (t9 != null) {
                        lastT9 = t9
                        lastT9Raw = f.data
                        TelemetryStats.onT9(SystemClock.elapsedRealtime())
                        BikeRecorder.onTelemetry9(t9)
                        main.post { listeners.forEach { it.onTelemetry9(t9) } }
                        if (showTelemetryLog) {
                            log("T9  rideTime=${t9.rideTimeS}s avg=${t9.avgKmh}km/h max=${t9.maxKmh}km/h btUnlock=${t9.btUnlockLevel}")
                        }
                    } else {
                        // Anything we don't recognize yet - including register read replies (cmd=0x04)
                        // and any real nav-projection pushes from the display. Never drop silently.
                        val asciiGuess = runCatching { String(f.data, Charsets.US_ASCII) }
                            .getOrNull()?.trim('\u0000', ' ')
                            ?.takeIf { it.isNotEmpty() && it.all { c -> c.code in 32..126 } }
                        log(
                            "RX  src=%02X dst=%02X cmd=%02X arg=%02X data=%s%s".format(
                                f.src, f.dst, f.cmd, f.arg, Protocol.hex(f.data),
                                if (asciiGuess != null) "  text=\"$asciiGuess\"" else ""
                            )
                        )
                    }
                }
            }
        }
    }

    // ---- Writes (BLE allows one outstanding write at a time) -------------------------------

    private fun queueWrite(frame: ByteArray) {
        writeQueue.add(frame)
        processWriteQueue()
    }

    @SuppressLint("MissingPermission")
    private fun processWriteQueue() {
        if (writeInFlight) return
        val g = gatt ?: return
        val frame = writeQueue.poll() ?: return
        val service = g.getService(SERVICE_UUID) ?: return
        val char = service.getCharacteristic(WRITE_UUID) ?: return
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        char.value = frame
        writeInFlight = true
        log("TX  ${Protocol.hex(frame)}")
        g.writeCharacteristic(char)
    }

    /** Sends a frame if a link exists, otherwise logs "Not connected." */
    fun send(frame: ByteArray) {
        if (gatt == null || autoPending) { log(str(R.string.not_connected)); return }
        queueWrite(frame)
    }
}
