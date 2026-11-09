package com.adilet.ekd01

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Started by [ActivityTransitionReceiver] when you get on a bike or into a vehicle.
 *
 * 1. Tries to find and connect to the display, [ATTEMPTS] times (about 30 s in total).
 * 2. Nothing found: stops, and the app goes back to sleep until the next trigger.
 * 3. Connected: stays alive (foreground service, with a notification) for as long as the link holds.
 * 4. Link lost: stops, and waits for the next trigger.
 */
class BikeService : Service() {

    companion object {
        const val ATTEMPTS = 3
        const val SCAN_WINDOW_MS = 8_000L
        const val ATTEMPT_GAP_MS = 3_000L
        /** Found the display but never reached "authenticated": give up on this attempt. */
        const val CONNECT_TIMEOUT_MS = 15_000L

        private const val CHANNEL_ID = "bike_connection"
        private const val NOTIFICATION_ID = 1

        @Volatile var isRunning = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, BikeService::class.java))
        }
    }

    private enum class Phase { IDLE, SCANNING, WAITING, CONNECTING, CONNECTED }

    private val handler = Handler(Looper.getMainLooper())
    private var phase = Phase.IDLE
    private var attempt = 0
    private var started = false

    private val connectTimeout = Runnable { BikeLink.disconnect() } // -> DISCONNECTED -> next attempt

    private val linkListener = object : BikeLink.Listener {
        override fun onStateChanged(state: BikeLink.State) {
            when (state) {
                BikeLink.State.CONNECTED -> {
                    handler.removeCallbacks(connectTimeout)
                    phase = Phase.CONNECTED
                    showNotification(R.string.notif_connected)
                }
                BikeLink.State.DISCONNECTED -> when (phase) {
                    Phase.CONNECTED -> stopSelf() // link lost: wait for the next trigger
                    Phase.CONNECTING -> {
                        handler.removeCallbacks(connectTimeout)
                        retryOrStop()
                    }
                    else -> {}
                }
                BikeLink.State.CONNECTING -> {}
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        BikeLink.init(this)
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must happen promptly after startForegroundService(), every time.
        startInForeground()
        if (started) return START_NOT_STICKY
        started = true

        val busy = BikeLink.state != BikeLink.State.DISCONNECTED // the app is already connected / connecting
        if (busy || !BackgroundMode.isEnabled(this) || !BackgroundMode.hasRequiredPermissions(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        BikeLink.addListener(linkListener)
        nextAttempt()
        return START_NOT_STICKY
    }

    private fun nextAttempt() {
        attempt++
        if (attempt > ATTEMPTS) { stopSelf(); return }
        phase = Phase.SCANNING
        BikeLink.scanAndConnect(SCAN_WINDOW_MS, filtered = true) { found ->
            if (phase != Phase.SCANNING) return@scanAndConnect
            if (found) {
                phase = Phase.CONNECTING
                handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
            } else {
                retryOrStop()
            }
        }
    }

    private fun retryOrStop() {
        if (attempt >= ATTEMPTS) {
            stopSelf()
        } else {
            phase = Phase.WAITING
            handler.postDelayed({ nextAttempt() }, ATTEMPT_GAP_MS)
        }
    }

    override fun onDestroy() {
        val unfinished = phase == Phase.SCANNING || phase == Phase.CONNECTING
        phase = Phase.IDLE
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        BikeLink.removeListener(linkListener)
        if (unfinished) BikeLink.disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- Notification ----------------------------------------------------------------------

    private fun buildNotification(textRes: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW)
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
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(R.string.notif_searching), type)
    }

    private fun showNotification(textRes: Int) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(textRes))
    }
}
