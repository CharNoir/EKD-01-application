package com.adilet.ekd01

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity

/**
 * Background mode: while enabled, Google Play services watches the phone's motion sensors in a
 * very low-power way and wakes [ActivityTransitionReceiver] when you start travelling in a vehicle
 * or on a bicycle. The receiver starts [BikeService], which looks for the display and connects.
 * Nothing of ours runs between those events.
 */
object BackgroundMode {

    private const val PREFS = "ekd01_prefs"
    private const val KEY_ENABLED = "background_enabled"
    private const val KEY_ADDRESS = "bike_address"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    /** Address of the display from the last successful connection; lets background scans use a hardware filter. */
    fun savedAddress(ctx: Context): String? = prefs(ctx).getString(KEY_ADDRESS, null)

    fun saveAddress(ctx: Context, address: String) {
        prefs(ctx).edit().putString(KEY_ADDRESS, address).apply()
    }

    /** Persists the setting and (un)registers the transition updates. Permissions must already be granted. */
    fun setEnabled(ctx: Context, enabled: Boolean) {
        BikeLink.init(ctx)
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            register(ctx)
        } else {
            unregister(ctx)
            if (BikeService.isRunning) {
                ctx.stopService(Intent(ctx, BikeService::class.java))
                BikeLink.disconnect()
            }
        }
    }

    // ---- Permissions -----------------------------------------------------------------------

    /** Without these the feature cannot work. */
    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /** Required ones plus the notification permission (Android 13+), which only affects whether the status notification is shown. */
    private fun requestablePermissions(): List<String> =
        requiredPermissions() + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    private fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun hasRequiredPermissions(ctx: Context): Boolean = requiredPermissions().all { granted(ctx, it) }

    fun missingPermissions(ctx: Context): List<String> = requestablePermissions().filterNot { granted(ctx, it) }

    // ---- Activity transition registration --------------------------------------------------

    private fun pendingIntent(ctx: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, 0, Intent(ctx, ActivityTransitionReceiver::class.java), flags)
    }

    private fun request(): ActivityTransitionRequest {
        val activities = listOf(DetectedActivity.IN_VEHICLE, DetectedActivity.ON_BICYCLE)
        return ActivityTransitionRequest(activities.map {
            ActivityTransition.Builder()
                .setActivityType(it)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build()
        })
    }

    /**
     * Registers (or re-registers, which is harmless) for ENTER transitions into IN_VEHICLE and
     * ON_BICYCLE. Registrations do not survive a reboot or an app update, so this is also called
     * from [BootReceiver] and on app start.
     */
    @SuppressLint("MissingPermission")
    fun register(ctx: Context) {
        BikeLink.init(ctx)
        if (!hasRequiredPermissions(ctx)) return
        ActivityRecognition.getClient(ctx.applicationContext)
            .requestActivityTransitionUpdates(request(), pendingIntent(ctx))
            .addOnSuccessListener { BikeLink.log(ctx.getString(R.string.log_background_registered)) }
            .addOnFailureListener { BikeLink.log(ctx.getString(R.string.log_background_register_failed, it.message ?: "")) }
    }

    @SuppressLint("MissingPermission")
    fun unregister(ctx: Context) {
        ActivityRecognition.getClient(ctx.applicationContext)
            .removeActivityTransitionUpdates(pendingIntent(ctx))
            .addOnSuccessListener { BikeLink.log(ctx.getString(R.string.log_background_unregistered)) }
    }
}
