package com.socialgeniex.smsgateway

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.SmsManager
import android.telephony.SubscriptionManager

/** SIM discovery and per-SIM SmsManager routing. Never throws. */
object SimHelper {

    data class SimInfo(
        val slot: Int,
        val label: String,
        val number: String?,
        val subscriptionId: Int
    )

    /** Active SIMs. Returns an empty list when phone permission is missing. */
    @SuppressLint("MissingPermission")
    fun getSims(context: Context): List<SimInfo> {
        return try {
            val sm = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
            sm.activeSubscriptionInfoList?.mapNotNull { sub ->
                val slot = sub.simSlotIndex
                if (slot < 0) return@mapNotNull null
                val label = sub.displayName?.toString()?.takeIf { it.isNotBlank() }
                    ?: "SIM ${slot + 1}"
                val number = try {
                    @Suppress("DEPRECATION")
                    sub.number
                } catch (e: SecurityException) {
                    null
                }
                SimInfo(slot, label, number, sub.subscriptionId)
            } ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /** Which SIM slot to use: the job's slot, else the default, else the first SIM. */
    fun effectiveSlot(context: Context, requested: Int?, defaultSlot: Int): Int {
        val sims = getSims(context)
        if (sims.isEmpty()) return defaultSlot
        if (requested != null && sims.any { it.slot == requested }) return requested
        if (sims.any { it.slot == defaultSlot }) return defaultSlot
        return sims.first().slot
    }

    /** SmsManager bound to the given SIM slot (falls back to the default manager). */
    fun smsManagerFor(context: Context, slot: Int): SmsManager {
        val sim = getSims(context).find { it.slot == slot }
        return if (sim != null) {
            SmsManager.getSmsManagerForSubscriptionId(sim.subscriptionId)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }
}
