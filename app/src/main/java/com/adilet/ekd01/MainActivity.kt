package com.adilet.ekd01

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.widget.NestedScrollView
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

private const val DEVICE_NAME = "EKD01-TB"
private val SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
private val WRITE_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
private val NOTIFY_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

// Runtime colors for things that change programmatically (status text, light-button tint).
// Static colors for the inflated XML layouts live in res/values/colors.xml instead - these
// two sets are kept in sync by hand since there are only a handful of them.
private val COLOR_AMBER: Int = 0xFFF57C00.toInt()
private val COLOR_ON_AMBER: Int = 0xFF1C1C1E.toInt()
private val COLOR_CARD_BORDER: Int = 0xFF3A3A3F.toInt()
private val COLOR_TEXT_PRIMARY: Int = 0xFFEDEDEF.toInt()
private val COLOR_OK: Int = 0xFF4CAF50.toInt()
private val COLOR_WARN: Int = 0xFFFF9800.toInt()
private val COLOR_NEUTRAL: Int = 0xFFAFAFB4.toInt()
private val COLOR_BAD: Int = 0xFFE53935.toInt()

// Byte offsets inside the T1/T9 telemetry payloads that Protocol.kt already decodes -
// used to label the raw-byte dump in the Developer screen so the still-unidentified
// bytes stand out from the ones we already understand.
private val T1_KNOWN_BYTES: Map<Int, String> = mapOf(
    4 to "light", 5 to "assist", 7 to "battery", 9 to "speed",
    11 to "trip lo", 12 to "trip hi",
    15 to "odo b0", 16 to "odo b1", 17 to "odo b2", 18 to "odo b3"
)
private val T9_KNOWN_BYTES: Map<Int, String> = mapOf(
    0 to "rideTime lo", 1 to "rideTime hi",
    4 to "avg lo", 5 to "avg hi",
    6 to "max lo", 7 to "max hi",
    15 to "btUnlock"
)

/** One line per byte: index, value in the requested base, and a [label] for bytes
 *  Protocol.kt already decodes. Unlabeled lines get a ">>" marker so they're easy to
 *  spot at a glance - those are the candidates worth watching for a speed signal. */
private fun formatRawBytes(data: ByteArray, known: Map<Int, String>, hex: Boolean): String {
    val sb = StringBuilder()
    for (i in data.indices) {
        val b = data[i].toInt() and 0xFF
        val valStr = if (hex) "0x%02X".format(b) else "%3d".format(b)
        val label = known[i]
        if (label != null) {
            sb.append("   %2d: %s  [%s]\n".format(i, valStr, label))
        } else {
            sb.append(">> %2d: %s\n".format(i, valStr))
        }
    }
    return sb.toString()
}

class MainActivity : AppCompatActivity() {

    private enum class Screen { MAIN, SETTINGS, DEVELOPER }
    private var currentScreen: Screen = Screen.MAIN

    // Log is nullable - the Log panel only exists while the Developer screen is inflated,
    // but log() gets called from app launch onward (scanning, GATT events...) regardless
    // of which screen is showing. Everything always goes into logBuffer; the live
    // TextView (when present) just mirrors it.
    private val logBuffer = StringBuilder()
    private var logView: TextView? = null
    private var logScroll: NestedScrollView? = null

    // Only present while the Main screen is inflated - nullable like the other Main-screen refs.
    // connectionIcon IS the connect/disconnect control now - no separate status text or button.
    private var connectionIcon: ImageView? = null
    private var batteryFillImage: ImageView? = null
    private var batteryPercentLabel: TextView? = null
    // Tracks the color setStatus() last used, so re-entering the Main screen (which
    // re-inflates connectionIcon from scratch) can restore the correct tint immediately
    // instead of resetting to neutral gray until the next telemetry/GATT event.
    private var currentStatusColor: Int = COLOR_NEUTRAL
    // Whether to show the battery percentage label next to the battery icon - toggled
    // from Settings. Not persisted across app restarts (in-memory only, for now).
    private var showBatteryPercent = true

    // T1/T9 telemetry streams in multiple times a second and was drowning out everything
    // else in the log. Still decoded either way - this only gates whether it's printed.
    private var showTelemetryLog = false

    private var bluetoothGatt: BluetoothGatt? = null
    private val parser = Protocol.FrameParser()
    private val writeQueue = ConcurrentLinkedQueue<ByteArray>()
    private var writeInFlight = false
    private val main = Handler(Looper.getMainLooper())

    private var lastT1: Protocol.Telemetry1? = null
    private var lastT9: Protocol.Telemetry9? = null
    /** Optimistic local state for the Main screen's light button - flips the instant you
     *  tap it, then gets corrected by the next incoming T1 telemetry packet (hardware
     *  truth always wins once a packet actually arrives). */
    private var lightOn: Boolean? = null
    private var assistLevelOverride: Int? = null
    // Brightness has no telemetry readback on this firmware, so there's no hardware truth
    // to sync the Settings slider to - this just remembers the last value you set it to.
    private var localBrightnessGuess = 2

    // Live refs to Main-screen widgets, so telemetry callbacks can update them in place
    // even though the whole screen gets re-inflated from scratch every time you navigate
    // back to it (at which point it re-seeds itself from lastT1/lastT9 instead).
    private var lightIconBtn: ImageView? = null
    private var odoLabel: TextView? = null
    private var mainTripDistLabel: TextView? = null
    private var mainTripTimeLabel: TextView? = null
    private var mainTripMaxLabel: TextView? = null
    private var mainTripAvgLabel: TextView? = null
    private var speedValueLabel: TextView? = null
    private var mainAssistLevelLabel: TextView? = null

    // Raw-byte dump for the Developer screen (see "Raw telemetry bytes" card) - lets you
    // watch every byte of the last T1/T9 packet live, to hunt for an undecoded speed field.
    private var lastT1Raw: ByteArray? = null
    private var lastT9Raw: ByteArray? = null
    private var rawHexMode = true
    private var rawT1View: TextView? = null
    private var rawT9View: TextView? = null

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) startScan() else log("Permissions denied.")
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        navigateTo(Screen.MAIN)
    }

    override fun onBackPressed() {
        if (currentScreen != Screen.MAIN) navigateTo(Screen.MAIN) else super.onBackPressed()
    }

    private fun navigateTo(screen: Screen) {
        currentScreen = screen
        setContentView(
            when (screen) {
                Screen.MAIN -> buildMainScreen()
                Screen.SETTINGS -> buildSettingsScreen()
                Screen.DEVELOPER -> buildDeveloperScreen()
            }
        )
    }

    // ---------- Main screen: the clean, everyday interface (res/layout/screen_main.xml) ----------

    private fun buildMainScreen(): View {
        val root = layoutInflater.inflate(R.layout.screen_main, null)

        connectionIcon = root.findViewById(R.id.connectionIcon)
        connectionIcon?.imageTintList = android.content.res.ColorStateList.valueOf(currentStatusColor)
        connectionIcon?.setOnClickListener { if (bluetoothGatt == null) requestPermissionsAndScan() else disconnectGatt() }

        batteryFillImage = root.findViewById(R.id.batteryFillImage)
        batteryPercentLabel = root.findViewById(R.id.batteryPercentLabel)
        updateBatteryViews()

        // Headlight icon toggle - tints amber when the light is on, gray when off.
        // Flips instantly on tap, then the next T1 telemetry packet (see handleIncoming)
        // corrects it if we guessed wrong.
        lightIconBtn = root.findViewById(R.id.lightIconBtn)
        lightIconBtn?.setOnClickListener {
            val newState = !(lightOn ?: lastT1?.light ?: false)
            lightOn = newState
            updateLightButtonVisual()
            sendNow(Protocol.setLight(newState))
        }
        updateLightButtonVisual()

        root.findViewById<TextView>(R.id.settingsIconBtn).setOnClickListener { navigateTo(Screen.SETTINGS) }

        // Assist level +/- stepper, between the settings and light icons. Optimistically
        // updates assistLevelOverride on tap (clamped 0-5, matching the assist0..5Btn
        // presets in the Developer menu); the next T1 telemetry packet confirms it.
        mainAssistLevelLabel = root.findViewById(R.id.assistLevelLabel)
        updateMainAssistLabel()
        root.findViewById<TextView>(R.id.assistMinusBtn).setOnClickListener {
            val cur = assistLevelOverride ?: lastT1?.assistLevel ?: 0
            val newLevel = (cur - 1).coerceIn(0, 5)
            assistLevelOverride = newLevel
            updateMainAssistLabel()
            sendNow(Protocol.setAssist(newLevel))
        }
        root.findViewById<TextView>(R.id.assistPlusBtn).setOnClickListener {
            val cur = assistLevelOverride ?: lastT1?.assistLevel ?: 0
            val newLevel = (cur + 1).coerceIn(0, 5)
            assistLevelOverride = newLevel
            updateMainAssistLabel()
            sendNow(Protocol.setAssist(newLevel))
        }

        speedValueLabel = root.findViewById(R.id.speedValueLabel)
        speedValueLabel?.text = lastT1?.let { "%.1f".format(it.speedKmh) } ?: "—"

        odoLabel = root.findViewById(R.id.odoLabel)
        mainTripDistLabel = root.findViewById(R.id.tripDistLabel)
        mainTripTimeLabel = root.findViewById(R.id.tripTimeLabel)
        mainTripMaxLabel = root.findViewById(R.id.tripMaxLabel)
        mainTripAvgLabel = root.findViewById(R.id.tripAvgLabel)
        odoLabel?.text = "Odo: " + (lastT1?.let { "%.2f km".format(it.odometerKm) } ?: "—")
        mainTripDistLabel?.text = lastT1?.let { "%.2f km".format(it.tripKm) } ?: "—"
        mainTripTimeLabel?.text = lastT9?.let { formatDuration(it.rideTimeS) } ?: "—"
        mainTripMaxLabel?.text = lastT9?.let { "%.1f".format(it.maxKmh) } ?: "—"
        mainTripAvgLabel?.text = lastT9?.let { "%.1f".format(it.avgKmh) } ?: "—"

        return root
    }

    private fun updateMainAssistLabel() {
        val level = assistLevelOverride ?: lastT1?.assistLevel
        mainAssistLevelLabel?.text = level?.toString() ?: "—"
    }

    private fun updateLightButtonVisual() {
        val icon = lightIconBtn ?: return
        val on = lightOn == true
        icon.imageTintList = android.content.res.ColorStateList.valueOf(if (on) COLOR_AMBER else COLOR_NEUTRAL)
    }

    /** Updates the battery icon's fill width (via ClipDrawable level, 0-10000) and color
     *  (green >=50%, amber 20-49%, red <20%), plus the toggleable percent label. Safe to
     *  call whether or not the Main screen is currently inflated (nullable refs). */
    private fun updateBatteryViews() {
        val pct = lastT1?.batteryPct
        if (pct != null) {
            batteryFillImage?.setImageLevel(pct * 100)
            val color = when {
                pct >= 50 -> COLOR_OK
                pct >= 20 -> COLOR_WARN
                else -> COLOR_BAD
            }
            batteryFillImage?.imageTintList = android.content.res.ColorStateList.valueOf(color)
            batteryPercentLabel?.text = "$pct%"
        } else {
            batteryFillImage?.setImageLevel(0)
            batteryPercentLabel?.text = "—%"
        }
        batteryPercentLabel?.visibility = if (showBatteryPercent) View.VISIBLE else View.GONE
    }

    private fun formatDuration(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    // ---------- Settings screen: the sliders for everyday adjustments (res/layout/screen_settings.xml) ----------

    private fun buildSettingsScreen(): View {
        val root = layoutInflater.inflate(R.layout.screen_settings, null)
        root.findViewById<Button>(R.id.backBtn).setOnClickListener { navigateTo(Screen.MAIN) }

        val assistLabel = root.findViewById<TextView>(R.id.assistLabel)
        val assistSlider = root.findViewById<SeekBar>(R.id.assistSlider)
        val assistInitial = lastT1?.assistLevel ?: 0
        assistLabel.text = "Assist level: $assistInitial"
        assistSlider.progress = assistInitial
        assistSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                assistLabel.text = "Assist level: $value"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) { sendNow(Protocol.setAssist(seekBar.progress)) }
        })

        val btUnlockLabel = root.findViewById<TextView>(R.id.btUnlockLabel)
        val btUnlockSlider = root.findViewById<SeekBar>(R.id.btUnlockSlider)
        val btUnlockInitial = lastT9?.btUnlockLevel ?: 1
        btUnlockLabel.text = "BT unlock level: $btUnlockInitial"
        btUnlockSlider.progress = btUnlockInitial
        btUnlockSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                btUnlockLabel.text = "BT unlock level: $value"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) { sendNow(Protocol.setBtUnlock(seekBar.progress)) }
        })

        val brightnessLabel = root.findViewById<TextView>(R.id.brightnessLabel)
        val brightnessSlider = root.findViewById<SeekBar>(R.id.brightnessSlider)
        // No telemetry readback for brightness on this firmware, so this starts from
        // whatever you last set it to in this session rather than a confirmed value.
        brightnessLabel.text = "Brightness: $localBrightnessGuess"
        brightnessSlider.progress = localBrightnessGuess
        brightnessSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                brightnessLabel.text = "Brightness: $value"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                localBrightnessGuess = seekBar.progress
                sendNow(Protocol.setBrightness(seekBar.progress))
            }
        })

        root.findViewById<Button>(R.id.unitsKmBtn).setOnClickListener { sendNow(Protocol.setUnitsMiles(false)) }
        root.findViewById<Button>(R.id.unitsMiBtn).setOnClickListener { sendNow(Protocol.setUnitsMiles(true)) }
        val autoOffInput = root.findViewById<EditText>(R.id.autoOffInput)
        root.findViewById<Button>(R.id.setAutoOffBtn).setOnClickListener {
            val v = autoOffInput.text.toString().toIntOrNull()
            if (v == null) log("Enter a timeout in minutes first.") else sendNow(Protocol.setAutoOffMinutes(v))
        }

        root.findViewById<CheckBox>(R.id.showBatteryPercentCheckbox).apply {
            isChecked = showBatteryPercent
            setOnCheckedChangeListener { _, checked ->
                showBatteryPercent = checked
                updateBatteryViews()
            }
        }

        root.findViewById<Button>(R.id.developerNavBtn).setOnClickListener { navigateTo(Screen.DEVELOPER) }

        return root
    }

    // ---------- Developer menu: everything else, basically unchanged (res/layout/screen_developer.xml) ----------

    private fun buildDeveloperScreen(): View {
        val root = layoutInflater.inflate(R.layout.screen_developer, null)
        root.findViewById<Button>(R.id.backBtn).setOnClickListener { navigateTo(Screen.MAIN) }

        // Raw byte dump of the last T1/T9 packets, for hunting an undecoded speed field.
        rawT1View = root.findViewById(R.id.rawT1View)
        rawT9View = root.findViewById(R.id.rawT9View)
        root.findViewById<Button>(R.id.rawHexBtn).setOnClickListener { rawHexMode = true; updateRawViews() }
        root.findViewById<Button>(R.id.rawDecimalBtn).setOnClickListener { rawHexMode = false; updateRawViews() }
        updateRawViews()

        root.findViewById<Button>(R.id.lightOnBtn).setOnClickListener {
            // The display's light register (0xA6) is a TOGGLE, not a set - it flips
            // whatever state it's currently in regardless of the payload byte we send.
            // So only send the frame when we actually need to change state, based on
            // the light value last reported in telemetry.
            if (lastT1?.light != true) sendNow(Protocol.setLight(true)) else log("Light already on.")
        }
        root.findViewById<Button>(R.id.lightOffBtn).setOnClickListener {
            if (lastT1?.light != false) sendNow(Protocol.setLight(false)) else log("Light already off.")
        }

        val assistIds = listOf(R.id.assist0Btn, R.id.assist1Btn, R.id.assist2Btn, R.id.assist3Btn, R.id.assist4Btn, R.id.assist5Btn)
        for ((level, id) in assistIds.withIndex()) {
            root.findViewById<Button>(id).setOnClickListener { sendNow(Protocol.setAssist(level)) }
        }

        val brightnessIds = listOf(R.id.brightness1Btn, R.id.brightness2Btn, R.id.brightness3Btn, R.id.brightness4Btn)
        for ((idx, id) in brightnessIds.withIndex()) {
            val level = idx + 1
            root.findViewById<Button>(id).setOnClickListener { sendNow(Protocol.setBrightness(level)) }
        }

        val btUnlockIds = listOf(R.id.btUnlock1Btn, R.id.btUnlock2Btn, R.id.btUnlock3Btn, R.id.btUnlock4Btn, R.id.btUnlock5Btn)
        for ((idx, id) in btUnlockIds.withIndex()) {
            val level = idx + 1
            root.findViewById<Button>(id).setOnClickListener { sendNow(Protocol.setBtUnlock(level)) }
        }

        // The real nav subsystem, found by decompiling NavigationMainActivity.v() and
        // S4/b.java's stop-navigation dialog handler. It's cmd=0x03 on DST=0xF1 - a single
        // start/stop command, not a per-turn data feed. Left alone for now.
        root.findViewById<Button>(R.id.navStartBtn).setOnClickListener { sendNow(Protocol.navStart()) }
        root.findViewById<Button>(R.id.navStopBtn).setOnClickListener { sendNow(Protocol.navStop()) }

        // THE REAL MECHANISM, confirmed live on the physical display - see Protocol.navUpdate's
        // doc comment for the decompile source and the confirmed byte layout. Send Nav START first.
        val tmpIconInput = root.findViewById<EditText>(R.id.tmpIconInput)
        val curRetainInput = root.findViewById<EditText>(R.id.curRetainInput)
        val nextStepDistInput = root.findViewById<EditText>(R.id.nextStepDistInput)
        val nextIconInput = root.findViewById<EditText>(R.id.nextIconInput)
        val nextNextStepDistInput = root.findViewById<EditText>(R.id.nextNextStepDistInput)
        val nextNextIconInput = root.findViewById<EditText>(R.id.nextNextIconInput)
        val pathRetainInput = root.findViewById<EditText>(R.id.pathRetainInput)
        root.findViewById<Button>(R.id.navPresetBtn).setOnClickListener {
            tmpIconInput.setText("3"); curRetainInput.setText("200"); nextStepDistInput.setText("500")
            nextIconInput.setText("1"); nextNextStepDistInput.setText("300"); nextNextIconInput.setText("8")
            pathRetainInput.setText("1200")
        }
        root.findViewById<Button>(R.id.navSendBtn).setOnClickListener {
            try {
                sendNow(Protocol.navUpdate(
                    tmpIconType = tmpIconInput.text.toString().trim().toInt(),
                    curStepRetainDistance = curRetainInput.text.toString().trim().toInt(),
                    nextStepDistance = nextStepDistInput.text.toString().trim().toInt(),
                    nextIconType = nextIconInput.text.toString().trim().toInt(),
                    nextNextStepDistance = nextNextStepDistInput.text.toString().trim().toInt(),
                    nextNextIconType = nextNextIconInput.text.toString().trim().toInt(),
                    pathRetainDistance = pathRetainInput.text.toString().trim().toInt()
                ))
            } catch (e: Exception) {
                log("Nav update error: ${e.message}")
            }
        }

        root.findViewById<Button>(R.id.unitsKmBtn2).setOnClickListener { sendNow(Protocol.setUnitsMiles(false)) }
        root.findViewById<Button>(R.id.unitsMiBtn2).setOnClickListener { sendNow(Protocol.setUnitsMiles(true)) }
        val autoOffInput2 = root.findViewById<EditText>(R.id.autoOffInput2)
        root.findViewById<Button>(R.id.setAutoOffBtn2).setOnClickListener {
            val v = autoOffInput2.text.toString().toIntOrNull()
            if (v == null) log("Enter a timeout in minutes first.") else sendNow(Protocol.setAutoOffMinutes(v))
        }

        val telemetryCheckbox = root.findViewById<CheckBox>(R.id.telemetryLogCheckbox)
        telemetryCheckbox.isChecked = showTelemetryLog
        telemetryCheckbox.setOnCheckedChangeListener { _, checked -> showTelemetryLog = checked }

        val lv = root.findViewById<TextView>(R.id.logView)
        lv.text = logBuffer.toString()
        logView = lv
        logScroll = root.findViewById(R.id.logScroll)
        root.findViewById<Button>(R.id.copyLogBtn).setOnClickListener {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("EKD01 log", logBuffer.toString()))
            log("Log copied to clipboard.")
        }
        root.findViewById<Button>(R.id.clearLogBtn).setOnClickListener { logBuffer.clear(); lv.text = "" }

        return root
    }

    // ---------- Shared small helpers ----------

    private fun updateRawViews() {
        rawT1View?.text = lastT1Raw?.let { formatRawBytes(it, T1_KNOWN_BYTES, rawHexMode) } ?: "No T1 packet yet."
        rawT9View?.text = lastT9Raw?.let { formatRawBytes(it, T9_KNOWN_BYTES, rawHexMode) } ?: "No T9 packet yet."
    }

    /** Sets the connection-status color (gray/amber/green), shown only via connectionIcon's
     *  tint now - no status text label. The transitions this used to label are already
     *  covered by the explicit log() calls at each call site. */
    private fun setStatus(color: Int) {
        currentStatusColor = color
        connectionIcon?.imageTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$ts  $msg\n"
        main.post {
            logBuffer.append(line)
            val lv = logView
            if (lv != null) {
                lv.append(line)
                // NOT fullScroll(FOCUS_DOWN): that requests focus, which bubbles up and drags
                // the OUTER page ScrollView down too every time a line is logged. Scroll only
                // this inner box, by coordinates, with no focus involved.
                logScroll?.post { logScroll?.scrollTo(0, lv.height) }
            }
        }
    }

    // ---------- Permissions + scan ----------

    private fun requestPermissionsAndScan() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        val missing = needed.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startScan() else permissionLauncher.launch(missing.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val btManager = getSystemService(BluetoothManager::class.java)
        val adapter = btManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            log("Bluetooth is off."); return
        }
        log("Scanning for $DEVICE_NAME ...")
        setStatus(COLOR_WARN)
        val scanner = adapter.bluetoothLeScanner
        val seen = mutableSetOf<String>()
        scanner.startScan(object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name
                val addr = result.device.address
                if (seen.add(addr)) {
                    log("seen: name=${name ?: "(null)"} addr=$addr rssi=${result.rssi}")
                }
                if (name != null && name.trim().equals(DEVICE_NAME, ignoreCase = true)) {
                    scanner.stopScan(this)
                    log("Found $DEVICE_NAME, connecting...")
                    connectTo(result.device)
                }
            }
            override fun onScanFailed(errorCode: Int) { log("Scan failed: $errorCode") }
        })
        main.postDelayed({ try { scanner.stopScan(object : ScanCallback() {}) } catch (_: Exception) {} }, 15000)
    }

    // ---------- GATT ----------

    @SuppressLint("MissingPermission")
    private fun connectTo(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        setStatus(COLOR_NEUTRAL)
        log("Disconnected.")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Connected, discovering services...")
                main.post { setStatus(COLOR_WARN) }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Link dropped.")
                main.post { setStatus(COLOR_NEUTRAL) }
                bluetoothGatt = null
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(SERVICE_UUID)
            if (service == null) { log("UART service not found."); return }
            val notifyChar = service.getCharacteristic(NOTIFY_UUID)
            gatt.setCharacteristicNotification(notifyChar, true)
            val cccd = notifyChar.getDescriptor(CCCD_UUID)
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccd)
            log("Services discovered, authenticating...")
            main.post { setStatus(COLOR_WARN) }
            // HELLO is sent once notifications are confirmed enabled, see onDescriptorWrite.
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            log("Notifications enabled, sending HELLO.")
            queueWrite(Protocol.helloFrame())
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleIncoming(characteristic.value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeInFlight = false
            processWriteQueue()
        }
    }

    private fun handleIncoming(data: ByteArray) {
        for (f in parser.feed(data)) {
            when {
                f.cmd == 0x04 && f.arg == 0x00 && f.data.size == 4 -> {
                    log("Challenge: ${Protocol.hex(f.data)}")
                    val reply = Protocol.authFrame(f.data)
                    queueWrite(reply)
                }
                f.cmd == 0x20 && f.arg == 0x00 -> {
                    val ok = f.data.isNotEmpty() && f.data[0].toInt() == 0
                    log(if (ok) "AUTH accepted." else "AUTH REJECTED (${Protocol.hex(f.data)}).")
                    if (ok) {
                        main.post { setStatus(COLOR_OK) }
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
                        lightOn = t1.light
                        assistLevelOverride = null
                        main.post {
                            updateLightButtonVisual()
                            updateBatteryViews()
                            updateMainAssistLabel()
                            speedValueLabel?.text = "%.1f".format(t1.speedKmh)
                            odoLabel?.text = "Odo: %.2f km".format(t1.odometerKm)
                            mainTripDistLabel?.text = "%.2f km".format(t1.tripKm)
                            updateRawViews()
                        }
                        if (showTelemetryLog) {
                            log("T1  light=${t1.light} assist=${t1.assistLevel} batt=${t1.batteryPct}% speed=${t1.speedKmh}km/h trip=${t1.tripKm}km odo=${t1.odometerKm}km")
                        }
                    } else if (t9 != null) {
                        lastT9 = t9
                        lastT9Raw = f.data
                        main.post {
                            mainTripTimeLabel?.text = formatDuration(t9.rideTimeS)
                            mainTripMaxLabel?.text = "%.1f".format(t9.maxKmh)
                            mainTripAvgLabel?.text = "%.1f".format(t9.avgKmh)
                            updateRawViews()
                        }
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

    // ---------- Write queue (BLE allows one outstanding write at a time) ----------

    private fun queueWrite(frame: ByteArray) {
        writeQueue.add(frame)
        processWriteQueue()
    }

    @SuppressLint("MissingPermission")
    private fun processWriteQueue() {
        if (writeInFlight) return
        val frame = writeQueue.poll() ?: return
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(SERVICE_UUID) ?: return
        val char = service.getCharacteristic(WRITE_UUID) ?: return
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        char.value = frame
        writeInFlight = true
        log("TX  ${Protocol.hex(frame)}")
        gatt.writeCharacteristic(char)
    }

    private fun sendNow(frame: ByteArray) {
        if (bluetoothGatt == null) { log("Not connected."); return }
        queueWrite(frame)
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectGatt()
    }
}
