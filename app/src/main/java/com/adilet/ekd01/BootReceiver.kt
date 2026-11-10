package com.adilet.ekd01

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a reboot or an app update: start the background service again and redo the activity-transition registration. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        BikeLink.init(context)
        if (!BackgroundMode.isEnabled(context) || !BackgroundMode.hasRequiredPermissions(context)) return
        BackgroundMode.register(context)
        BikeService.start(context)
    }
}
