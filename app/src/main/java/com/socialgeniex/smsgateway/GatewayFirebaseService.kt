package com.socialgeniex.smsgateway

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * OPTIONAL push wake-ups: the server can send a push to trigger an
 * immediate poll instead of waiting for the next poll interval.
 *
 * The app compiles and runs WITHOUT google-services.json:
 *  - Firebase auto-init is disabled in AndroidManifest.xml
 *    (tools:node="remove" on FirebaseInitProvider)
 *  - every call below is guarded, so a missing Firebase setup is a no-op.
 *
 * To enable push for real:
 *  1. Add google-services.json to app/
 *  2. Apply the google-services Gradle plugin in app/build.gradle
 *  3. Remove the tools:node="remove" on FirebaseInitProvider in the manifest
 */
class GatewayFirebaseService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        try {
            if (Prefs(this).isLinked) GatewayService.wake(this)
        } catch (t: Throwable) {
            // Push is optional — never crash the app over it.
        }
    }

    override fun onNewToken(token: String) {
        try {
            Prefs(this).fcmToken = token
        } catch (t: Throwable) {
            // Ignore.
        }
    }
}
