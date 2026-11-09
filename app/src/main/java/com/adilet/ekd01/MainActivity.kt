package com.adilet.ekd01

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.widget.NestedScrollView

// Runtime colors for things that change programmatically (status text, light-button tint).
// Static colors for the inflated XML layouts live in res/values/colors.xml instead - these
// two sets are kept in sync by hand since there are only a handful of them.
private const val COLOR_AMBER: Int = 0xFFF57C00.toInt()
private const val COLOR_OK: Int = 0xFF4CAF50.toInt()
private const val COLOR_WARN: Int = 0xFFFF9800.toInt()
private const val COLOR_NEUTRAL: Int = 0xFFAFAFB4.toInt()
private const val COLOR_BAD: Int = 0xFFE53935.toInt()

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

    // The BLE connection itself lives in BikeLink (shared with the background BikeService).
    // Telemetry and log are read from there; this activity only renders them.
    private val lastT1: Protocol.Telemetry1? get() = BikeLink.lastT1
    private val lastT9: Protocol.Telemetry9? get() = BikeLink.lastT9
    private val dash: String get() = getString(R.string.placeholder_dash)

    // The Log panel only exists while the Developer screen is inflated, so this is nullable.
    // Everything is always in BikeLink.logBuffer; the live TextView (when present) mirrors it.
    private var logView: TextView? = null
    private var logScroll: NestedScrollView? = null

    // Only present while the Main screen is inflated - nullable like the other Main-screen refs.
    // connectionIcon IS the connect/disconnect control - no separate status text or button.
    private var connectionIcon: ImageView? = null
    private var batteryFillImage: ImageView? = null
    private var batteryPercentLabel: TextView? = null
    // Whether to show the battery percentage label next to the battery icon - toggled
    // from Settings. Not persisted across app restarts (in-memory only, for now).
    private var showBatteryPercent = true

    /** Optimistic local state for the Main screen's light button - flips the instant you
     *  tap it, then gets corrected by the next incoming T1 telemetry packet (hardware
     *  truth always wins once a packet actually arrives). */
    private var lightOn: Boolean? = null
    private var assistLevelOverride: Int? = null
    // Brightness has no telemetry read-back on this firmware, so there's no hardware truth
    // to sync the Settings slider to - this just remembers the last value you set it to.
    private var localBrightnessGuess = 2

    // Live refs to Main-screen widgets, so telemetry callbacks can update them in place
    // even though the whole screen gets re-inflated from scratch every time you navigate
    // back to it (at which point it re-seeds itself from BikeLink's last telemetry instead).
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
    private var rawHexMode = true
    private var rawT1View: TextView? = null
    private var rawT9View: TextView? = null

    // Settings screen: "Background execution enabled" checkbox.
    private var backgroundCheckbox: CheckBox? = null

    private val linkListener = object : BikeLink.Listener {
        override fun onStateChanged(state: BikeLink.State) {
            updateConnectionIcon()
        }

        override fun onTelemetry1(t: Protocol.Telemetry1) {
            lightOn = t.light
            assistLevelOverride = null
            updateLightButtonVisual()
            updateBatteryViews()
            updateMainAssistLabel()
            speedValueLabel?.text = "%.1f".format(t.speedKmh)
            odoLabel?.text = getString(R.string.odo_value, t.odometerKm)
            mainTripDistLabel?.text = getString(R.string.distance_km, t.tripKm)
            updateRawViews()
        }

        override fun onTelemetry9(t: Protocol.Telemetry9) {
            mainTripTimeLabel?.text = formatDuration(t.rideTimeS)
            mainTripMaxLabel?.text = "%.1f".format(t.maxKmh)
            mainTripAvgLabel?.text = "%.1f".format(t.avgKmh)
            updateRawViews()
        }

        override fun onLogLine(line: String) {
            val lv = logView ?: return
            lv.append(line)
            // NOT fullScroll(FOCUS_DOWN): that requests focus, which bubbles up and drags
            // the OUTER page ScrollView down too every time a line is logged. Scroll only
            // this inner box, by coordinates, with no focus involved.
            logScroll?.post { logScroll?.scrollTo(0, lv.height) }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) startScan() else log(getString(R.string.permissions_denied))
    }

    private val backgroundPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (BackgroundMode.hasRequiredPermissions(this)) {
            BackgroundMode.setEnabled(this, true)
        } else {
            backgroundCheckbox?.isChecked = false
            Toast.makeText(this, R.string.background_permissions_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BikeLink.init(this)
        BikeLink.addListener(linkListener)
        // Registrations can be lost (reboot, update); re-registering is harmless.
        if (BackgroundMode.isEnabled(this)) BackgroundMode.register(this)
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
        updateConnectionIcon()
        connectionIcon?.setOnClickListener {
            if (BikeLink.state == BikeLink.State.DISCONNECTED) requestPermissionsAndScan() else BikeLink.disconnect()
        }

        batteryFillImage = root.findViewById(R.id.batteryFillImage)
        batteryPercentLabel = root.findViewById(R.id.batteryPercentLabel)
        updateBatteryViews()

        // Headlight icon toggle - tints amber when the light is on, gray when off.
        // Flips instantly on tap, then the next T1 telemetry packet (see onTelemetry1)
        // corrects it if we guessed wrong.
        if (lightOn == null) lightOn = lastT1?.light
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
        speedValueLabel?.text = lastT1?.let { "%.1f".format(it.speedKmh) } ?: dash

        odoLabel = root.findViewById(R.id.odoLabel)
        mainTripDistLabel = root.findViewById(R.id.tripDistLabel)
        mainTripTimeLabel = root.findViewById(R.id.tripTimeLabel)
        mainTripMaxLabel = root.findViewById(R.id.tripMaxLabel)
        mainTripAvgLabel = root.findViewById(R.id.tripAvgLabel)
        odoLabel?.text = lastT1?.let { getString(R.string.odo_value, it.odometerKm) } ?: getString(R.string.odo_unknown)
        mainTripDistLabel?.text = lastT1?.let { getString(R.string.distance_km, it.tripKm) } ?: dash
        mainTripTimeLabel?.text = lastT9?.let { formatDuration(it.rideTimeS) } ?: dash
        mainTripMaxLabel?.text = lastT9?.let { "%.1f".format(it.maxKmh) } ?: dash
        mainTripAvgLabel?.text = lastT9?.let { "%.1f".format(it.avgKmh) } ?: dash

        return root
    }

    private fun updateMainAssistLabel() {
        val level = assistLevelOverride ?: lastT1?.assistLevel
        mainAssistLevelLabel?.text = level?.toString() ?: dash
    }

    private fun updateLightButtonVisual() {
        val icon = lightIconBtn ?: return
        val on = lightOn == true
        icon.imageTintList = android.content.res.ColorStateList.valueOf(if (on) COLOR_AMBER else COLOR_NEUTRAL)
    }

    /** Connection-status color (gray / amber / green), shown only via connectionIcon's tint. */
    private fun updateConnectionIcon() {
        val color = when (BikeLink.state) {
            BikeLink.State.DISCONNECTED -> COLOR_NEUTRAL
            BikeLink.State.CONNECTING -> COLOR_WARN
            BikeLink.State.CONNECTED -> COLOR_OK
        }
        connectionIcon?.imageTintList = android.content.res.ColorStateList.valueOf(color)
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
            batteryPercentLabel?.text = getString(R.string.battery_percent, pct)
        } else {
            batteryFillImage?.setImageLevel(0)
            batteryPercentLabel?.text = getString(R.string.battery_percent_unknown)
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

        val btUnlockLabel = root.findViewById<TextView>(R.id.btUnlockLabel)
        val btUnlockSlider = root.findViewById<SeekBar>(R.id.btUnlockSlider)
        val btUnlockInitial = lastT9?.btUnlockLevel ?: 1
        btUnlockLabel.text = getString(R.string.bt_unlock_value, btUnlockInitial)
        btUnlockSlider.progress = btUnlockInitial
        btUnlockSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                btUnlockLabel.text = getString(R.string.bt_unlock_value, value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) { sendNow(Protocol.setBtUnlock(seekBar.progress)) }
        })

        val brightnessLabel = root.findViewById<TextView>(R.id.brightnessLabel)
        val brightnessSlider = root.findViewById<SeekBar>(R.id.brightnessSlider)
        // No telemetry readback for brightness on this firmware, so this starts from
        // whatever you last set it to in this session rather than a confirmed value.
        brightnessLabel.text = getString(R.string.brightness_value, localBrightnessGuess)
        brightnessSlider.progress = localBrightnessGuess
        brightnessSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                brightnessLabel.text = getString(R.string.brightness_value, value)
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
            if (v == null) log(getString(R.string.enter_timeout_minutes)) else sendNow(Protocol.setAutoOffMinutes(v))
        }

        root.findViewById<CheckBox>(R.id.showBatteryPercentCheckbox).apply {
            isChecked = showBatteryPercent
            setOnCheckedChangeListener { _, checked ->
                showBatteryPercent = checked
                updateBatteryViews()
            }
        }

        root.findViewById<CheckBox>(R.id.backgroundExecutionCheckbox).apply {
            backgroundCheckbox = this
            isChecked = BackgroundMode.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                if (checked) enableBackgroundMode() else BackgroundMode.setEnabled(this@MainActivity, false)
            }
        }

        root.findViewById<Button>(R.id.developerNavBtn).setOnClickListener { navigateTo(Screen.DEVELOPER) }

        return root
    }

    /** Asks for the permissions background mode needs, then turns it on. */
    private fun enableBackgroundMode() {
        val missing = BackgroundMode.missingPermissions(this)
        if (missing.isEmpty()) {
            BackgroundMode.setEnabled(this, true)
        } else {
            backgroundPermissionLauncher.launch(missing.toTypedArray())
        }
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
            if (lastT1?.light != true) sendNow(Protocol.setLight(true)) else log(getString(R.string.log_light_already_on))
        }
        root.findViewById<Button>(R.id.lightOffBtn).setOnClickListener {
            if (lastT1?.light != false) sendNow(Protocol.setLight(false)) else log(getString(R.string.log_light_already_off))
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
                log(getString(R.string.log_nav_update_error, e.message ?: ""))
            }
        }

        root.findViewById<Button>(R.id.unitsKmBtn2).setOnClickListener { sendNow(Protocol.setUnitsMiles(false)) }
        root.findViewById<Button>(R.id.unitsMiBtn2).setOnClickListener { sendNow(Protocol.setUnitsMiles(true)) }
        val autoOffInput2 = root.findViewById<EditText>(R.id.autoOffInput2)
        root.findViewById<Button>(R.id.setAutoOffBtn2).setOnClickListener {
            val v = autoOffInput2.text.toString().toIntOrNull()
            if (v == null) log(getString(R.string.enter_timeout_minutes)) else sendNow(Protocol.setAutoOffMinutes(v))
        }

        val telemetryCheckbox = root.findViewById<CheckBox>(R.id.telemetryLogCheckbox)
        telemetryCheckbox.isChecked = BikeLink.showTelemetryLog
        telemetryCheckbox.setOnCheckedChangeListener { _, checked -> BikeLink.showTelemetryLog = checked }

        val lv = root.findViewById<TextView>(R.id.logView)
        lv.text = BikeLink.logBuffer.toString()
        logView = lv
        logScroll = root.findViewById(R.id.logScroll)
        root.findViewById<Button>(R.id.copyLogBtn).setOnClickListener {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.log_clip_label), BikeLink.logBuffer.toString()))
            log(getString(R.string.log_copied))
        }
        root.findViewById<Button>(R.id.clearLogBtn).setOnClickListener { BikeLink.clearLog(); lv.text = "" }

        return root
    }

    // ---------- Shared small helpers ----------

    private fun updateRawViews() {
        rawT1View?.text = BikeLink.lastT1Raw?.let { formatRawBytes(it, T1_KNOWN_BYTES, rawHexMode) } ?: getString(R.string.raw_no_t1)
        rawT9View?.text = BikeLink.lastT9Raw?.let { formatRawBytes(it, T9_KNOWN_BYTES, rawHexMode) } ?: getString(R.string.raw_no_t9)
    }

    private fun log(msg: String) = BikeLink.log(msg)

    private fun sendNow(frame: ByteArray) = BikeLink.send(frame)

    // ---------- Permissions + manual scan ----------

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

    private fun startScan() {
        BikeLink.scanAndConnect(timeoutMs = 15_000, filtered = false)
    }

    override fun onDestroy() {
        BikeLink.removeListener(linkListener)
        // Leaving the app drops a connection started here, as before. A connection kept alive by
        // the background service is left alone, and a rotation does not disconnect either.
        if (isFinishing && !BikeService.isRunning) BikeLink.disconnect()
        super.onDestroy()
    }
}
