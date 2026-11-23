package com.char_noir.ekd01

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Optional GPS recording (opt-in, off by default). Positions go only into the local database
 * ([RideDb], table track_points) and are never sent anywhere. Uses the phone's GPS chip through the
 * platform [LocationManager]; no Google services and no network involved.
 *
 * Recording runs only while a ride (a connection to the bike) is open: [BikeRecorder] starts and
 * stops it. The two switches live in Settings: [isEnabled] (record) and [showMap] (draw OSM tiles).
 */
object GpsTracker {

    private const val PREFS = "ekd01_prefs"
    private const val KEY_RECORD = "gps_record"
    private const val KEY_MAP = "gps_show_map"

    private const val MIN_INTERVAL_MS = 3_000L
    private const val MIN_DISTANCE_M = 5f
    /** Fixes less accurate than this are dropped (typical for the first seconds after a cold start). */
    private const val MAX_ACCURACY_M = 50f

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_RECORD, false)
    fun showMap(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_MAP, false)

    /** Turns recording on or off; takes effect right away for a ride in progress. */
    fun setEnabled(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_RECORD, value).apply()
        BikeRecorder.refreshGps()
    }

    fun setShowMap(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_MAP, value).apply()
    }

    fun hasLocationPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** "Allow all the time" (Android 10+). Older versions have no such permission and need none. */
    fun hasBackgroundPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** True when the background service may be declared as a location service (see BikeService). */
    fun canRunInService(ctx: Context): Boolean = hasLocationPermission(ctx) && hasBackgroundPermission(ctx)

    // ---- Location updates (called on the recorder thread; callbacks arrive on the main thread) ----

    private var manager: LocationManager? = null
    private var listener: LocationListener? = null

    private val callback = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_M) return
            BikeRecorder.onLocation(location)
        }
        // Needed on Android 9 and older, where these have no default implementation.
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @Synchronized
    fun start(ctx: Context) {
        if (listener != null || !isEnabled(ctx) || !hasLocationPermission(ctx)) return
        val lm = ctx.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        try {
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                BikeLink.log(ctx.getString(R.string.log_gps_off))
                return
            }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, MIN_INTERVAL_MS, MIN_DISTANCE_M, callback, Looper.getMainLooper())
            manager = lm
            listener = callback
            BikeLink.log(ctx.getString(R.string.log_gps_started))
        } catch (e: SecurityException) {
            BikeLink.log(ctx.getString(R.string.log_gps_failed, e.message ?: ""))
        } catch (e: Exception) {
            BikeLink.log(ctx.getString(R.string.log_gps_failed, e.message ?: ""))
        }
    }

    @Synchronized
    fun stop() {
        val lm = manager ?: return
        try { lm.removeUpdates(callback) } catch (_: Exception) {}
        manager = null
        listener = null
    }
}
