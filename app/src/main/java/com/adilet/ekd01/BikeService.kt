package com.adilet.ekd01

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * The always-on part of background mode (a foreground service with a small, silent notification).
 * It runs while "Background execution enabled" is on, and is restarted after a reboot, an app update,
 * when the app is opened, and when the system kills it.
 *
 * - Bike known (connected once before): keeps a background connect request pending ("armed"). The
 *   Bluetooth controller waits for the display, so this uses almost no power. When the display is
 *   switched on, the link comes up by itself; when it drops, the service arms again.
 * - A "tap to disconnect" in the app pauses re-arming for [MANUAL_PAUSE_MS].
 * - Bike not known yet: waits for a motion trigger ([ActivityTransitionReceiver]) and scans, or for
 *   you to connect once from the app (which saves the bike's address).
 * - A motion trigger while armed also runs a short filtered scan ([ATTEMPTS] x [SCAN_WINDOW_MS]), as a
 *   backstop in case the background connect does not fire.
 */
class BikeService : Service() {

    companion object {
        const val ATTEMPTS = 3
        const val SCAN_WINDOW_MS = 8_000L
        const val ATTEMPT_GAP_MS = 3_000L
        /** Found the display during a scan but never reached "authenticated": give up on this attempt. */
        const val CONNECT_TIMEOUT_MS = 15_000L

        const val REARM_DELAY_MS = 3_000L
        const val REARM_MAX_DELAY_MS = 60_000L
        const val MANUAL_PAUSE_MS = 10 * 60_000L

        private const val EXTRA_SCAN = "scan"
        private const val EXTRA_REARM = "rearm"
        const val CHANNEL_ID = "bike_connection_quiet"
        private const val OLD_CHANNEL_ID = "bike_connection"
        private const val NOTIFICATION_ID = 1

        @Volatile var isRunning = false
            private set

        /**
         * Safe to call from anywhere. [scan] = a motion trigger, also run a short scan.
         * [rearm] = the selected bike changed: start waiting for it now (also ends a manual-disconnect pause).
         */
        fun start(context: Context, scan: Boolean = false, rearm: Boolean = false) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BikeService::class.java).putExtra(EXTRA_SCAN, scan).putExtra(EXTRA_REARM, rearm)
                )
            } catch (e: Exception) {
                BikeLink.init(context)
                BikeLink.log(context.getString(R.string.log_service_start_failed, e.message ?: ""))
            }
        }
    }

    private enum class Phase { SCANNING, WAITING_SCAN, CONNECTING_SCAN, ARMED, CONNECTED }

    private val handler = Handler(Looper.getMainLooper())
    private var phase = Phase.ARMED
    private var attempt = 0
    private var rearmDelay = REARM_DELAY_MS
    private var started = false

    private val connectTimeout = Runnable { BikeLink.disconnect() } // -> DISCONNECTED -> next attempt
    private val rearm = Runnable { arm() }
    private val nextScanAttempt = Runnable { scanAttempt() }

    private val linkListener = object : BikeLink.Listener {
        override fun onStateChanged(state: BikeLink.State) {
            when (state) {
                BikeLink.State.CONNECTED -> {
                    handler.removeCallbacks(connectTimeout)
                    handler.removeCallbacks(rearm)
                    phase = Phase.CONNECTED
                    rearmDelay = REARM_DELAY_MS
                    showNotification(R.string.notif_connected)
                }
                BikeLink.State.DISCONNECTED -> when (phase) {
                    Phase.CONNECTED -> {
                        val manual = SystemClock.elapsedRealtime() - BikeLink.lastManualDisconnectAt < 5_000
                        scheduleRearm(if (manual) MANUAL_PAUSE_MS else REARM_DELAY_MS)
                    }
                    Phase.CONNECTING_SCAN -> {
                        handler.removeCallbacks(connectTimeout)
                        retryScanOrArm()
                    }
                    Phase.ARMED -> {
                        // The background connect ended without a link (error) or a manual scan found nothing.
                        scheduleRearm(rearmDelay)
                        rearmDelay = (rearmDelay * 2).coerceAtMost(REARM_MAX_DELAY_MS)
                    }
                    else -> {}
                }
                BikeLink.State.CONNECTING -> {}
            }
        }
    }

    /** Bluetooth switched off: forget everything pending. Switched on: arm again. */
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_OFF -> {
                    handler.removeCallbacksAndMessages(null)
                    BikeLink.disconnect()
                    phase = Phase.ARMED
                }
                BluetoothAdapter.STATE_ON -> if (phase == Phase.ARMED) scheduleRearm(REARM_DELAY_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        BikeLink.init(this)
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must happen promptly after startForegroundService(), every time (also on a system restart).
        if (!startInForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val scanRequested = intent?.getBooleanExtra(EXTRA_SCAN, false) == true
        val rearmRequested = intent?.getBooleanExtra(EXTRA_REARM, false) == true

        if (started) {
            // Already running: a motion trigger while waiting also does a quick scan.
            if (scanRequested && phase == Phase.ARMED && BikeLink.state == BikeLink.State.DISCONNECTED) startScanAttempts()
            else if (rearmRequested && BikeLink.state == BikeLink.State.DISCONNECTED && !BikeLink.isArmed) {
                // The selected bike changed: forget old timers and wait for the new one.
                handler.removeCallbacks(nextScanAttempt)
                handler.removeCallbacks(connectTimeout)
                rearmDelay = REARM_DELAY_MS
                scheduleRearm(REARM_DELAY_MS)
            }
            return START_STICKY
        }
        started = true

        if (!BackgroundMode.isEnabled(this) || !BackgroundMode.hasRequiredPermissions(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        BikeLink.addListener(linkListener)
        ContextCompat.registerReceiver(
            this, bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )

        when {
            BikeLink.state == BikeLink.State.CONNECTED -> {
                phase = Phase.CONNECTED
                showNotification(R.string.notif_connected)
            }
            BikeLink.state == BikeLink.State.CONNECTING -> phase = Phase.ARMED // a connect is in progress; re-arm when it ends
            BackgroundMode.savedAddress(this) == null -> {
                phase = Phase.ARMED
                showNotification(R.string.notif_no_bike)
                if (scanRequested) startScanAttempts()
            }
            scanRequested -> startScanAttempts()
            else -> arm()
        }
        return START_STICKY
    }

    // ---- Waiting for the bike (background connect) -----------------------------------------

    private fun scheduleRearm(delayMs: Long) {
        phase = Phase.ARMED
        showNotification(R.string.notif_waiting)
        handler.removeCallbacks(rearm)
        handler.postDelayed(rearm, delayMs)
    }

    private fun arm() {
        phase = Phase.ARMED
        if (BikeLink.state != BikeLink.State.DISCONNECTED || BikeLink.isArmed) return
        val armed = BikeLink.armAutoConnect()
        showNotification(
            when {
                armed -> R.string.notif_waiting
                BackgroundMode.savedAddress(this) == null -> R.string.notif_no_bike
                else -> R.string.notif_waiting // Bluetooth is off; the Bluetooth receiver arms again when it is switched on
            }
        )
    }

    // ---- Backstop: short filtered scan -------------------------------------------------------

    private fun startScanAttempts() {
        handler.removeCallbacks(rearm)
        attempt = 0
        scanAttempt()
    }

    private fun scanAttempt() {
        attempt++
        if (attempt > ATTEMPTS) { scheduleRearm(REARM_DELAY_MS); return }
        phase = Phase.SCANNING
        showNotification(R.string.notif_searching)
        BikeLink.scanAndConnect(SCAN_WINDOW_MS, filtered = true) { found ->
            if (phase != Phase.SCANNING) return@scanAndConnect
            if (found) {
                phase = Phase.CONNECTING_SCAN
                handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
            } else {
                retryScanOrArm()
            }
        }
    }

    private fun retryScanOrArm() {
        if (attempt >= ATTEMPTS) {
            scheduleRearm(REARM_DELAY_MS)
        } else {
            phase = Phase.WAITING_SCAN
            handler.postDelayed(nextScanAttempt, ATTEMPT_GAP_MS)
        }
    }

    // ---- Lifecycle ---------------------------------------------------------------------------

    override fun onDestroy() {
        val scanning = phase == Phase.SCANNING || phase == Phase.WAITING_SCAN || phase == Phase.CONNECTING_SCAN
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        BikeLink.removeListener(linkListener)
        try { unregisterReceiver(bluetoothReceiver) } catch (_: Exception) {}
        if (scanning || BikeLink.isArmed) BikeLink.disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- Notification ----------------------------------------------------------------------

    private fun buildNotification(textRes: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            // Importance is fixed once a channel exists, so the quieter (minimized) one has its own id.
            nm.deleteNotificationChannel(OLD_CHANNEL_ID)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(textRes))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .build()
    }

    /** Returns false if the system refused (for example a restart from the background that is not allowed). */
    private fun startInForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(R.string.notif_waiting), type)
        true
    } catch (e: Exception) {
        BikeLink.log(getString(R.string.log_service_foreground_refused, e.message ?: ""))
        false
    }

    private fun showNotification(textRes: Int) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(textRes))
    }
}
