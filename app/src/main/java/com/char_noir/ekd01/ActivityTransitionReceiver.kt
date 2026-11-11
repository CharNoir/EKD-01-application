package com.char_noir.ekd01

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/** Woken by Play services when you start travelling in a vehicle or on a bicycle. */
class ActivityTransitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        BikeLink.init(context)
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return

        val entered = result.transitionEvents.firstOrNull {
            it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER &&
                (it.activityType == DetectedActivity.IN_VEHICLE || it.activityType == DetectedActivity.ON_BICYCLE)
        } ?: return

        if (!BackgroundMode.isEnabled(context) || !BackgroundMode.hasRequiredPermissions(context)) return

        val what = if (entered.activityType == DetectedActivity.ON_BICYCLE) "ON_BICYCLE" else "IN_VEHICLE"
        BikeLink.log(context.getString(R.string.log_background_trigger, what))
        BikeService.start(context, scan = true)
    }
}
