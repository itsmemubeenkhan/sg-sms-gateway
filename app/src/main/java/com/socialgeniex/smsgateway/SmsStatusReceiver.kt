package com.socialgeniex.smsgateway

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager

/**
 * Receives the radio's per-part callbacks for outgoing SMS and feeds them
 * into [JobTracker], which decides sent / failed / delivered per job.
 */
class SmsStatusReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SMS_SENT = "com.socialgeniex.smsgateway.SMS_SENT"
        const val ACTION_SMS_DELIVERED = "com.socialgeniex.smsgateway.SMS_DELIVERED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getLongExtra("job_id", -1L)
        if (jobId < 0) return
        when (intent.action) {
            ACTION_SMS_SENT -> {
                val ok = resultCode == Activity.RESULT_OK
                JobTracker.onPartSent(jobId, ok, if (ok) null else sentErrorMessage(resultCode))
            }
            ACTION_SMS_DELIVERED -> {
                JobTracker.onPartDelivered(jobId, resultCode == Activity.RESULT_OK)
            }
        }
    }

    private fun sentErrorMessage(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_NO_SERVICE -> "No mobile signal"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "Airplane mode is on"
        SmsManager.RESULT_ERROR_NULL_PDU -> "Message couldn't be built"
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Mobile network refused the message"
        else -> "Message failed to send"
    }
}
