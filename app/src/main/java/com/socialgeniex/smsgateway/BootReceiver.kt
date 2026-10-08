package com.socialgeniex.smsgateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restart the gateway after a reboot if the phone was linked.
 * (Android 12+ may block background service starts here — the service is
 * also restarted whenever the app is opened, so nothing is lost.)
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            if (Prefs(context.applicationContext).isLinked) {
                GatewayService.start(context.applicationContext)
            }
        } catch (t: Throwable) {
            // Will start again when the user opens the app.
        }
    }
}
