package com.char_noir.ekd01

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.text.InputFilter
import android.text.InputType
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
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
    2 to "lock flags", 3 to "bike unlock", 4 to "light", 5 to "assist", 6 to "gear count", 7 to "battery",
    8 to "unlocked", 9 to "speed lo", 10 to "speed hi",
    11 to "trip b0", 12 to "trip b1", 13 to "trip b2", 14 to "trip b3",
    15 to "odo b0", 16 to "odo b1", 17 to "odo b2", 18 to "odo b3"
)
private val T9_KNOWN_BYTES: Map<Int, String> = mapOf(
    0 to "rideTime b0", 1 to "rideTime b1", 2 to "rideTime b2", 3 to "rideTime b3",
    4 to "avg lo", 5 to "avg hi",
    6 to "max lo", 7 to "max hi",
    12 to "brightness", 15 to "btUnlock"
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

    private enum class Screen { MAIN, SETTINGS, DEVELOPER, HISTORY, HISTORY_DETAIL, DEVICES, KNOCKS, LOG_HISTORY }
    private var currentScreen: Screen = Screen.MAIN

    // Bike selector: addresses of the bikes in the list (so a search does not offer them again), and
    // what to do once Bluetooth permissions are granted.
    private var knownBikeAddresses: Set<String> = emptySet()
    private var afterPermissions: (() -> Unit)? = null

    // History screens: which ride is open, and a thread for database reads.
    private var selectedSessionId: Long = -1
    private val dbExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

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
    private var analysisView: TextView? = null
    private var lastAnalysisAt = 0L
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
            updateAnalysis(throttle = true)
        }

        override fun onGesture(sequence: String) {
            Toast.makeText(this@MainActivity, getString(R.string.knock_toast, sequence), Toast.LENGTH_SHORT).show()
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

    // Asked when a knock whose action needs a permission (e.g. calling) is saved.
    private val knockPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (!granted.values.all { it }) Toast.makeText(this, R.string.knock_permission_denied, Toast.LENGTH_LONG).show()
    }

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) afterPermissions?.invoke() else log(getString(R.string.permissions_denied))
        afterPermissions = null
    }

    private val backgroundPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (BackgroundMode.hasRequiredPermissions(this)) {
            turnOnBackgroundMode()
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
        if (BackgroundMode.isEnabled(this)) {
            BackgroundMode.register(this)
            BikeService.start(this) // no-op if it is already running; makes sure it is after an update or a kill
        }
        navigateTo(Screen.MAIN)
    }

    override fun onBackPressed() {
        when (currentScreen) {
            Screen.MAIN -> super.onBackPressed()
            Screen.HISTORY_DETAIL -> navigateTo(Screen.HISTORY)
            Screen.HISTORY, Screen.DEVICES, Screen.KNOCKS -> navigateTo(Screen.SETTINGS)
            Screen.LOG_HISTORY -> navigateTo(Screen.DEVELOPER)
            else -> navigateTo(Screen.MAIN)
        }
    }

    private fun navigateTo(screen: Screen) {
        if (screen != Screen.DEVICES) BikeLink.stopDiscovery()
        currentScreen = screen
        setContentView(
            when (screen) {
                Screen.MAIN -> buildMainScreen()
                Screen.SETTINGS -> buildSettingsScreen()
                Screen.DEVELOPER -> buildDeveloperScreen()
                Screen.HISTORY -> buildHistoryScreen()
                Screen.HISTORY_DETAIL -> buildHistoryDetailScreen()
                Screen.DEVICES -> buildDevicesScreen()
                Screen.KNOCKS -> buildKnocksScreen()
                Screen.LOG_HISTORY -> buildLogHistoryScreen()
            }
        )
    }

    // ---------- Main screen: the clean, everyday interface (res/layout/screen_main.xml) ----------

    private fun buildMainScreen(): View {
        val root = layoutInflater.inflate(R.layout.screen_main, null)

        connectionIcon = root.findViewById(R.id.connectionIcon)
        updateConnectionIcon()
        connectionIcon?.setOnClickListener {
            if (BikeLink.state == BikeLink.State.DISCONNECTED) requestPermissionsAndScan() else BikeLink.disconnect(manual = true)
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

        val bikeName = root.findViewById<TextView>(R.id.bikeNameLabel)
        val bikeAddress = root.findViewById<TextView>(R.id.bikeAddressLabel)
        val active = BackgroundMode.savedAddress(this)
        if (active == null) {
            bikeName.text = getString(R.string.bike_none)
            bikeAddress.visibility = View.GONE
        } else {
            bikeName.text = bikeLabel(null, active)
            bikeAddress.text = active
            dbExecutor.execute {
                val nickname = try { RideDb.get(this).deviceNickname(active) } catch (e: Exception) { null }
                runOnUiThread { if (!isDestroyed && currentScreen == Screen.SETTINGS) bikeName.text = bikeLabel(nickname, active) }
            }
        }
        root.findViewById<Button>(R.id.chooseBikeBtn).setOnClickListener { navigateTo(Screen.DEVICES) }
        root.findViewById<Button>(R.id.openKnocksBtn).setOnClickListener { navigateTo(Screen.KNOCKS) }

        val intervalLabel = root.findViewById<TextView>(R.id.recordIntervalLabel)
        val intervalInput = root.findViewById<EditText>(R.id.recordIntervalInput)
        intervalLabel.text = getString(R.string.record_interval_value, BikeRecorder.intervalSeconds(this))
        root.findViewById<Button>(R.id.setRecordIntervalBtn).setOnClickListener {
            val v = intervalInput.text.toString().toIntOrNull()
            if (v == null || v !in BikeRecorder.MIN_INTERVAL_S..BikeRecorder.MAX_INTERVAL_S) {
                Toast.makeText(this, R.string.record_interval_invalid, Toast.LENGTH_SHORT).show()
            } else {
                BikeRecorder.setIntervalSeconds(this, v)
                intervalLabel.text = getString(R.string.record_interval_value, v)
                intervalInput.text.clear()
            }
        }

        root.findViewById<Button>(R.id.notificationSettingsBtn).setOnClickListener { openNotificationSettings() }

        root.findViewById<Button>(R.id.historyBtn).setOnClickListener { navigateTo(Screen.HISTORY) }
        root.findViewById<Button>(R.id.developerNavBtn).setOnClickListener { navigateTo(Screen.DEVELOPER) }

        return root
    }

    /** Opens the system settings for the service notification (the channel if it exists, otherwise the app's). */
    private fun openNotificationSettings() {
        val channel = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, BikeService.CHANNEL_ID)
        val app = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        try {
            startActivity(channel)
        } catch (_: Exception) {
            try { startActivity(app) } catch (_: Exception) {}
        }
    }

    /** Turns background mode on and, once, asks Android to not restrict the app's battery use (needed to stay alive for days). */
    private fun turnOnBackgroundMode() {
        BackgroundMode.setEnabled(this, true)
        val power = getSystemService(PowerManager::class.java)
        if (power != null && !power.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                // No such settings screen on this device: the user can still do it by hand.
            }
        }
    }

    /** Asks for the permissions background mode needs, then turns it on. */
    private fun enableBackgroundMode() {
        val missing = BackgroundMode.missingPermissions(this)
        if (missing.isEmpty()) {
            turnOnBackgroundMode()
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

        analysisView = root.findViewById(R.id.analysisView)
        root.findViewById<Button>(R.id.analysisClearBtn).setOnClickListener { TelemetryStats.clear(); updateAnalysis() }
        updateAnalysis()

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

        root.findViewById<Button>(R.id.bikeUnlockBtn).setOnClickListener { sendNow(Protocol.setBikeUnlocked(true)) }
        root.findViewById<Button>(R.id.bikeLockBtn).setOnClickListener { sendNow(Protocol.setBikeUnlocked(false)) }
        root.findViewById<Button>(R.id.msgNotifyOnBtn).setOnClickListener { sendNow(Protocol.setMessageNotification(true)) }
        root.findViewById<Button>(R.id.msgNotifyOffBtn).setOnClickListener { sendNow(Protocol.setMessageNotification(false)) }

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

        root.findViewById<CheckBox>(R.id.logHistoryCheckbox).apply {
            isChecked = LogStore.enabled
            setOnCheckedChangeListener { _, checked -> LogStore.setEnabled(this@MainActivity, checked) }
        }
        root.findViewById<Button>(R.id.openLogHistoryBtn).setOnClickListener { navigateTo(Screen.LOG_HISTORY) }

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

    // ---------- Log history: the saved log file, browsable (res/layout/screen_log_history.xml) ----------

    private var logHistoryChars = 100_000

    private fun buildLogHistoryScreen(): View {
        val root = layoutInflater.inflate(R.layout.screen_log_history, null)
        root.findViewById<Button>(R.id.backBtn).setOnClickListener { navigateTo(Screen.DEVELOPER) }
        val scroll = root.findViewById<ScrollView>(R.id.logHistoryScroll)
        val text = root.findViewById<TextView>(R.id.logHistoryText)
        val info = root.findViewById<TextView>(R.id.logHistoryInfo)
        val filterInput = root.findViewById<EditText>(R.id.logHistoryFilter)

        fun load() {
            val filter = filterInput.text.toString().trim()
            val chars = logHistoryChars
            dbExecutor.execute {
                val tail = LogStore.readTail(chars)
                val size = LogStore.sizeBytes()
                val shown = if (filter.isEmpty()) tail
                else tail.lineSequence().filter { it.contains(filter, ignoreCase = true) }.joinToString("\n")
                runOnUiThread {
                    if (isDestroyed || currentScreen != Screen.LOG_HISTORY) return@runOnUiThread
                    text.text = shown.ifEmpty { getString(R.string.log_history_empty) }
                    info.text = getString(R.string.log_history_info, tail.length / 1024, size / 1024)
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }

        root.findViewById<Button>(R.id.logHistoryRefreshBtn).setOnClickListener { load() }
        filterInput.setOnEditorActionListener { _, _, _ -> load(); true }
        root.findViewById<Button>(R.id.logHistoryOlderBtn).setOnClickListener {
            logHistoryChars = (logHistoryChars + 200_000).coerceAtMost(2_000_000)
            load()
        }
        root.findViewById<Button>(R.id.logHistoryShareBtn).setOnClickListener {
            val body = text.text.toString().takeLast(200_000) // keeps the share intent small
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body),
                    getString(R.string.log_history_share_title)
                )
            )
        }
        root.findViewById<Button>(R.id.logHistoryClearBtn).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.log_history_clear_title)
                .setPositiveButton(R.string.log_history_clear) { _, _ ->
                    dbExecutor.execute { LogStore.clear(); runOnUiThread { load() } }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        load()
        return root
    }

    // ---------- Knocks screen: assist-level knocks and the action each one runs (res/layout/screen_knocks.xml) ----------

    private fun buildKnocksScreen(): View {
        val root = layoutInflater.inflate(R.layout.screen_knocks, null)
        root.findViewById<Button>(R.id.backBtn).setOnClickListener { navigateTo(Screen.SETTINGS) }
        val list = root.findViewById<LinearLayout>(R.id.knockList)
        val knocks = KnockStore.all
        root.findViewById<TextView>(R.id.knocksEmpty).visibility = if (knocks.isEmpty()) View.VISIBLE else View.GONE
        for (k in knocks) list.addView(buildKnockItem(k, list))
        root.findViewById<Button>(R.id.newKnockBtn).setOnClickListener { showKnockDialog(null) }
        return root
    }

    private fun buildKnockItem(k: Knock, parent: android.view.ViewGroup): View {
        val item = layoutInflater.inflate(R.layout.item_knock, parent, false)
        val action = KnockActions.byId(k.actionId)
        item.findViewById<TextView>(R.id.knockSequence).text = k.sequenceText
        item.findViewById<TextView>(R.id.knockAction).text =
            (action?.let { getString(it.nameRes) } ?: k.actionId) + (k.parameter?.let { " · $it" } ?: "")
        item.findViewById<TextView>(R.id.knockSummary).text = getString(R.string.knock_gap_summary, k.gapMs)
        item.findViewById<CheckBox>(R.id.knockEnabled).apply {
            isChecked = k.enabled
            setOnCheckedChangeListener { _, checked -> KnockStore.setEnabled(this@MainActivity, k.id, checked) }
        }
        item.findViewById<Button>(R.id.knockEditBtn).setOnClickListener { showKnockDialog(k) }
        item.findViewById<Button>(R.id.knockDeleteBtn).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.knock_delete_title)
                .setMessage("${k.sequenceText}: " + (action?.let { getString(it.nameRes) } ?: k.actionId))
                .setPositiveButton(R.string.knock_delete) { _, _ ->
                    KnockStore.delete(this, k.id)
                    navigateTo(Screen.KNOCKS)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        return item
    }

    /** Editor for a new knock ([existing] = null) or an existing one. */
    private fun showKnockDialog(existing: Knock?) {
        val view = layoutInflater.inflate(R.layout.dialog_knock, null)
        val seqInput = view.findViewById<EditText>(R.id.knockSequenceInput)
        val gapInput = view.findViewById<EditText>(R.id.knockGapInput)
        val spinner = view.findViewById<Spinner>(R.id.knockActionSpinner)
        val paramInput = view.findViewById<EditText>(R.id.knockParamInput)
        val actions = KnockActions.all

        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, actions.map { getString(it.nameRes) })
        fun updateParameterField() {
            val a = actions[spinner.selectedItemPosition]
            paramInput.visibility = if (a.needsParameter) View.VISIBLE else View.GONE
            a.parameterHintRes?.let { paramInput.hint = getString(it) }
            paramInput.inputType = a.parameterInputType
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) = updateParameterField()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        seqInput.setText(existing?.sequenceText ?: "")
        gapInput.setText((existing?.gapMs ?: KnockStore.DEFAULT_GAP_MS).toString())
        existing?.let { e ->
            val i = actions.indexOfFirst { it.id == e.actionId }
            if (i >= 0) spinner.setSelection(i)
            paramInput.setText(e.parameter ?: "")
        }
        updateParameterField()

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.knock_edit_title)
            .setView(view)
            .setPositiveButton(R.string.save, null) // set below, so errors keep the dialog open
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                fun error(res: Int, vararg args: Any) = Toast.makeText(this, getString(res, *args), Toast.LENGTH_LONG).show()
                val sequence = KnockStore.parseSequence(seqInput.text.toString()) ?: return@setOnClickListener error(R.string.knock_error_sequence)
                val gap = gapInput.text.toString().toIntOrNull()
                if (gap == null || gap !in KnockStore.MIN_GAP_MS..KnockStore.MAX_GAP_MS) return@setOnClickListener error(R.string.knock_error_gap)
                val action = actions[spinner.selectedItemPosition]
                val parameter = if (action.needsParameter) action.normalizeParameter(paramInput.text.toString()) else null
                if (action.needsParameter && parameter == null) return@setOnClickListener error(R.string.knock_error_param)
                KnockStore.conflictWith(sequence, existing?.id)?.let { return@setOnClickListener error(R.string.knock_error_conflict, it.sequenceText) }

                KnockStore.upsert(this, Knock(existing?.id ?: KnockStore.newId(), sequence, gap, action.id, parameter, existing?.enabled ?: true))
                val missing = action.missingPermissions(this)
                if (missing.isNotEmpty()) knockPermissionLauncher.launch(missing.toTypedArray())
                dialog.dismiss()
                navigateTo(Screen.KNOCKS)
            }
        }
        dialog.show()
    }

    // ---------- Devices screen: choose, rename, add or forget bikes (res/layout/screen_devices.xml) ----------

    /** Nickname if there is one, otherwise "EKD01 " plus the last part of the address. */
    private fun bikeLabel(nickname: String?, address: String): String =
        nickname?.takeIf { it.isNotBlank() } ?: getString(R.string.device_unnamed, address.takeLast(5))

    private fun buildDevicesScreen(): View {
        val root = layoutInflater.inflate(R.layout.screen_devices, null)
        root.findViewById<Button>(R.id.backBtn).setOnClickListener { navigateTo(Screen.SETTINGS) }
        val list = root.findViewById<LinearLayout>(R.id.deviceList)
        val empty = root.findViewById<TextView>(R.id.devicesEmpty)
        val scanBtn = root.findViewById<Button>(R.id.scanBtn)
        val status = root.findViewById<TextView>(R.id.scanStatus)
        val found = root.findViewById<LinearLayout>(R.id.foundList)

        loadDeviceList(list, empty)

        scanBtn.setOnClickListener {
            requestBluetoothPermissions {
                found.removeAllViews()
                status.visibility = View.VISIBLE
                status.text = getString(R.string.devices_searching)
                scanBtn.isEnabled = false
                var newCount = 0
                val started = BikeLink.discoverDevices(
                    timeoutMs = 10_000,
                    onFound = { d ->
                        if (currentScreen == Screen.DEVICES && d.address !in knownBikeAddresses) {
                            newCount++
                            found.addView(buildFoundItem(d, found))
                        }
                    },
                    onDone = {
                        if (currentScreen == Screen.DEVICES) {
                            scanBtn.isEnabled = true
                            status.text = if (newCount > 0) getString(R.string.devices_found_count, newCount) else getString(R.string.devices_none_found)
                        }
                    }
                )
                if (!started) {
                    scanBtn.isEnabled = true
                    status.text = getString(R.string.devices_scan_failed)
                }
            }
        }
        return root
    }

    private fun loadDeviceList(list: LinearLayout, empty: TextView) {
        dbExecutor.execute {
            val devices: List<DeviceRow> = try {
                RideDb.get(this).loadDevices()
            } catch (e: Exception) {
                BikeLink.logDbError(e.message ?: e.javaClass.simpleName)
                emptyList()
            }
            runOnUiThread {
                if (isDestroyed || currentScreen != Screen.DEVICES) return@runOnUiThread
                knownBikeAddresses = devices.map { it.address }.toSet()
                list.removeAllViews()
                empty.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
                for (d in devices) list.addView(buildDeviceItem(d, list))
            }
        }
    }

    private fun buildDeviceItem(d: DeviceRow, parent: android.view.ViewGroup): View {
        val item = layoutInflater.inflate(R.layout.item_device, parent, false)
        val active = d.address.equals(BackgroundMode.savedAddress(this), ignoreCase = true)
        item.findViewById<TextView>(R.id.deviceName).text = bikeLabel(d.nickname, d.address)
        item.findViewById<TextView>(R.id.deviceAddress).text = d.address
        item.findViewById<TextView>(R.id.deviceActive).visibility = if (active) View.VISIBLE else View.GONE
        val last = d.lastConnectedAt
        item.findViewById<TextView>(R.id.deviceInfo).text =
            if (last == null) getString(R.string.device_no_rides)
            else getString(
                R.string.device_rides, d.sessionCount,
                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(last))
            )

        item.setOnClickListener {
            BackgroundMode.selectBike(this, d.address)
            navigateTo(Screen.DEVICES)
        }
        item.findViewById<Button>(R.id.renameBtn).setOnClickListener { showRenameDialog(d) }
        item.findViewById<Button>(R.id.forgetBtn).setOnClickListener { showForgetDialog(d) }
        return item
    }

    /** A bike seen by the search: tapping it adds it, selects it and connects. */
    private fun buildFoundItem(d: BikeLink.Discovered, parent: android.view.ViewGroup): View {
        val item = layoutInflater.inflate(R.layout.item_device, parent, false)
        item.findViewById<TextView>(R.id.deviceName).text = bikeLabel(null, d.address)
        item.findViewById<TextView>(R.id.deviceAddress).text = d.address
        item.findViewById<TextView>(R.id.deviceInfo).visibility = View.GONE
        item.findViewById<View>(R.id.deviceButtons).visibility = View.GONE
        item.setOnClickListener {
            BikeLink.stopDiscovery()
            dbExecutor.execute {
                try { RideDb.get(this).addDevice(d.address) } catch (e: Exception) { BikeLink.logDbError(e.message ?: e.javaClass.simpleName) }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    BackgroundMode.selectBike(this, d.address)
                    BikeLink.connectToAddress(d.address)
                    navigateTo(Screen.DEVICES)
                }
            }
        }
        return item
    }

    private fun showRenameDialog(d: DeviceRow) {
        val input = EditText(this).apply {
            setText(d.nickname ?: "")
            setSelection(text.length)
            hint = getString(R.string.device_rename_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(30))
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(R.string.device_rename_title)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString()
                dbExecutor.execute {
                    try { RideDb.get(this).renameDevice(d.address, name) } catch (e: Exception) { BikeLink.logDbError(e.message ?: e.javaClass.simpleName) }
                    runOnUiThread { if (!isDestroyed && currentScreen == Screen.DEVICES) navigateTo(Screen.DEVICES) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showForgetDialog(d: DeviceRow) {
        AlertDialog.Builder(this)
            .setTitle(R.string.device_forget_title)
            .setMessage(getString(R.string.device_forget_message, bikeLabel(d.nickname, d.address)))
            .setPositiveButton(R.string.device_forget) { _, _ ->
                if (d.address.equals(BackgroundMode.savedAddress(this), ignoreCase = true)) BackgroundMode.clearSelectedBike(this)
                BikeRecorder.forgetDevice(d.address) {
                    if (!isDestroyed && currentScreen == Screen.DEVICES) navigateTo(Screen.DEVICES)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- History screens: recorded rides (res/layout/screen_history*.xml, item_session.xml) ----------

    private fun buildHistoryScreen(): View {
        val root = layoutInflater.inflate(R.layout.screen_history, null)
        root.findViewById<Button>(R.id.backBtn).setOnClickListener { navigateTo(Screen.SETTINGS) }
        val list = root.findViewById<LinearLayout>(R.id.historyList)
        val empty = root.findViewById<TextView>(R.id.historyEmpty)
        val bikeView = root.findViewById<TextView>(R.id.historyBike)
        val active = BackgroundMode.savedAddress(this)
        dbExecutor.execute {
            var nickname: String? = null
            val sessions: List<SessionRow> = try {
                val db = RideDb.get(this)
                if (active != null) nickname = db.deviceNickname(active)
                db.loadSessions(active)
            } catch (e: Exception) {
                BikeLink.logDbError(e.message ?: e.javaClass.simpleName)
                emptyList()
            }
            runOnUiThread {
                if (isDestroyed || currentScreen != Screen.HISTORY) return@runOnUiThread
                if (active != null) {
                    bikeView.text = getString(R.string.history_for, bikeLabel(nickname, active))
                    bikeView.visibility = View.VISIBLE
                }
                empty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
                for (s in sessions) {
                    val item = buildSessionItem(s, list)
                    item.setOnClickListener { selectedSessionId = s.id; navigateTo(Screen.HISTORY_DETAIL) }
                    list.addView(item)
                }
            }
        }
        return root
    }

    private fun buildHistoryDetailScreen(): View {
        val root = layoutInflater.inflate(R.layout.screen_history_detail, null)
        root.findViewById<Button>(R.id.backBtn).setOnClickListener { navigateTo(Screen.HISTORY) }
        val header = root.findViewById<LinearLayout>(R.id.detailHeader)
        val samplesView = root.findViewById<TextView>(R.id.detailSamples)
        val chartList = root.findViewById<LinearLayout>(R.id.chartList)
        val sessionId = selectedSessionId
        dbExecutor.execute {
            var session: SessionRow? = null
            var samples: List<SampleRow> = emptyList()
            try {
                val db = RideDb.get(this)
                session = db.loadSession(sessionId)
                samples = db.loadSamples(sessionId)
            } catch (e: Exception) {
                BikeLink.logDbError(e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                if (isDestroyed || currentScreen != Screen.HISTORY_DETAIL) return@runOnUiThread
                session?.let { header.addView(buildSessionItem(it, header)) }
                samplesView.text = if (samples.isEmpty()) getString(R.string.history_no_samples)
                else samples.joinToString("\n") { formatSample(it) }
                addCharts(chartList, samples)
            }
        }
        return root
    }

    /** One graph (value against time) per recorded parameter. Parameters with no data are left out. */
    private fun addCharts(parent: LinearLayout, samples: List<SampleRow>) {
        val params: List<Pair<Int, (SampleRow) -> Double?>> = listOf(
            R.string.chart_battery to { r -> r.batteryPct?.toDouble() },
            R.string.chart_speed to { r -> r.speedKmh },
            R.string.chart_odo to { r -> r.odoKm },
            R.string.chart_trip_km to { r -> r.tripKm },
            R.string.chart_trip_time to { r -> r.tripTimeS?.let { it / 60.0 } },
            R.string.chart_avg to { r -> r.tripAvgKmh },
            R.string.chart_max to { r -> r.tripMaxKmh }
        )
        for ((titleRes, select) in params) {
            val xs = ArrayList<Long>()
            val ys = ArrayList<Double>()
            for (r in samples) {
                val v = select(r) ?: continue
                xs += r.ts
                ys += v
            }
            if (ys.isEmpty()) continue
            val card = layoutInflater.inflate(R.layout.item_chart, parent, false)
            card.findViewById<TextView>(R.id.chartTitle).text = getString(titleRes)
            card.findViewById<TextView>(R.id.chartRange).text =
                getString(R.string.chart_range, "%.1f".format(ys.minOrNull()!!), "%.1f".format(ys.maxOrNull()!!))
            card.findViewById<LineChartView>(R.id.chartView).setData(xs, ys)
            parent.addView(card)
        }
    }

    private fun percentText(value: Int?): String = value?.let { getString(R.string.battery_percent, it) } ?: dash

    /** A ride summary card (not clickable by itself; the list adds the click). */
    private fun buildSessionItem(s: SessionRow, parent: android.view.ViewGroup): View {
        val item = layoutInflater.inflate(R.layout.item_session, parent, false)
        val dateFormat = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        item.findViewById<TextView>(R.id.sessionTitle).text = dateFormat.format(java.util.Date(s.connectedAt))

        val end = s.disconnectedAt
        item.findViewById<TextView>(R.id.sessionDuration).text =
            if (end == null) getString(R.string.history_ongoing)
            else getString(R.string.history_duration, formatDuration(((end - s.connectedAt) / 1000).toInt()))

        item.findViewById<TextView>(R.id.sessionBattery).text =
            getString(R.string.history_battery, percentText(s.batteryStart), percentText(s.batteryEnd))

        val distance = item.findViewById<TextView>(R.id.sessionDistance)
        val odoStart = s.odoStartKm
        val odoEnd = s.odoEndKm
        if (odoStart != null && odoEnd != null) {
            distance.text = getString(R.string.history_distance, odoEnd - odoStart, odoEnd)
        } else {
            distance.visibility = View.GONE
        }

        val tripTime = s.tripTimeS
        val avg = s.avgKmh
        val max = s.maxKmh
        if (tripTime != null && avg != null && max != null) {
            item.findViewById<TextView>(R.id.sessionTrip).apply {
                text = getString(R.string.history_trip, formatDuration(tripTime), avg, max)
                visibility = View.VISIBLE
            }
        }

        if (s.chargeDetected) {
            item.findViewById<TextView>(R.id.sessionCharged).apply {
                text = getString(R.string.history_charged, percentText(s.prevBatteryEnd), percentText(s.batteryStart))
                visibility = View.VISIBLE
            }
        }
        item.isClickable = false
        return item
    }

    private fun formatSample(r: SampleRow): String {
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(r.ts))
        val line = getString(
            R.string.history_sample_line,
            time,
            percentText(r.batteryPct),
            r.speedKmh?.let { "%.1f".format(it) } ?: dash,
            r.odoKm?.let { "%.1f".format(it) } ?: dash,
            r.tripKm?.let { "%.2f".format(it) } ?: dash
        )
        return if (r.status == "disconnected") line + "  " + getString(R.string.history_disconnected_marker) else line
    }

    // ---------- Shared small helpers ----------

    /** Telemetry rates and the recent assist-level changes (Developer screen). [throttle] = at most twice a second. */
    private fun updateAnalysis(throttle: Boolean = false) {
        val view = analysisView ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (throttle && now - lastAnalysisAt < 500) return
        lastAnalysisAt = now
        val s = TelemetryStats.snapshot()
        fun rate(name: String, r: TelemetryStats.RateStats?) =
            if (r == null) getString(R.string.analysis_no_rate, name)
            else getString(R.string.analysis_rate, name, r.perSecond, r.avgMs, r.minMs, r.maxMs, r.count)
        val timeFormat = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        val sb = StringBuilder()
        sb.append(rate("T1", s.t1)).append('\n').append(rate("T9", s.t9)).append("\n\n")
        sb.append(getString(R.string.analysis_changes_title)).append('\n')
        if (s.changes.isEmpty()) sb.append(getString(R.string.analysis_none))
        for (c in s.changes) {
            sb.append(
                getString(
                    R.string.analysis_change_line,
                    timeFormat.format(java.util.Date(c.wallMs)),
                    c.from?.toString() ?: dash,
                    c.to,
                    c.sinceLastChangeMs?.let { getString(R.string.analysis_since_change, it) } ?: "",
                    c.packetGapMs?.let { getString(R.string.analysis_packet_gap, it) } ?: ""
                )
            ).append('\n')
        }
        view.text = sb.toString()
    }

    private fun updateRawViews() {
        rawT1View?.text = BikeLink.lastT1Raw?.let { formatRawBytes(it, T1_KNOWN_BYTES, rawHexMode) } ?: getString(R.string.raw_no_t1)
        rawT9View?.text = BikeLink.lastT9Raw?.let { formatRawBytes(it, T9_KNOWN_BYTES, rawHexMode) } ?: getString(R.string.raw_no_t9)
    }

    private fun log(msg: String) = BikeLink.log(msg)

    private fun sendNow(frame: ByteArray) = BikeLink.send(frame)

    // ---------- Permissions + manual scan ----------

    private fun requestPermissionsAndScan() = requestBluetoothPermissions { startScan() }

    /** Runs [action] once the Bluetooth permissions are granted (asking for them first if needed). */
    private fun requestBluetoothPermissions(action: () -> Unit) {
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
        if (missing.isEmpty()) {
            action()
        } else {
            afterPermissions = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startScan() {
        BikeLink.scanAndConnect(timeoutMs = 15_000, filtered = false)
    }

    override fun onDestroy() {
        BikeLink.removeListener(linkListener)
        BikeLink.stopDiscovery()
        dbExecutor.shutdown()
        // Leaving the app drops a connection started here, as before. A connection kept alive by
        // the background service is left alone, and a rotation does not disconnect either.
        if (isFinishing && !BikeService.isRunning) BikeLink.disconnect()
        super.onDestroy()
    }
}
