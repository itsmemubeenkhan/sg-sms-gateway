package com.socialgeniex.smsgateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Incoming SMS (SMS_RECEIVED) -> forwarded to the server's /inbound endpoint
 * so replies land in the SocialGeniex team inbox.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val appCtx = context.applicationContext
        val prefs = Prefs(appCtx)
        if (!prefs.isLinked) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val api = ApiClient(prefs.baseUrl!!, prefs.deviceToken!!)
                val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                // Group multi-part messages back together per sender.
                val grouped = msgs.groupBy { it.originatingAddress ?: "unknown" }
                for ((from, parts) in grouped) {
                    val body = parts.joinToString("") { it.messageBody ?: "" }
                    if (body.isEmpty()) continue
                    try {
                        api.inbound(from, body)
                        LogStore.add("Incoming SMS from $from forwarded")
                    } catch (t: Throwable) {
                        LogStore.add("Couldn't forward SMS: ${PlainErrors.msg(t)}")
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
