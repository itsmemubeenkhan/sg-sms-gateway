package com.socialgeniex.smsgateway

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** All persistent state: link info, server settings, daily stats and per-SIM quotas. */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("sg_sms_gateway", Context.MODE_PRIVATE)

    var baseUrl: String?
        get() = sp.getString("base_url", null)
        set(v) = sp.edit().putString("base_url", v).apply()

    var deviceToken: String?
        get() = sp.getString("device_token", null)
        set(v) = sp.edit().putString("device_token", v).apply()

    var gatewayId: Long
        get() = sp.getLong("gateway_id", 0L)
        set(v) = sp.edit().putLong("gateway_id", v).apply()

    var deviceName: String?
        get() = sp.getString("device_name", null)
        set(v) = sp.edit().putString("device_name", v).apply()

    var fcmToken: String?
        get() = sp.getString("fcm_token", null)
        set(v) = sp.edit().putString("fcm_token", v).apply()

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    var defaultSimSlot: Int
        get() = sp.getInt("default_sim_slot", 0)
        set(v) = sp.edit().putInt("default_sim_slot", v).apply()

    /** Seconds between server checks (from server settings, min 10). */
    var pollIntervalSec: Int
        get() = sp.getInt("poll_interval", 20)
        set(v) = sp.edit().putInt("poll_interval", v).apply()

    /** Pause between messages in ms (from server settings). */
    var delayMs: Long
        get() = sp.getLong("delay_ms", 2000L)
        set(v) = sp.edit().putLong("delay_ms", v).apply()

    /** Max messages per SIM per day, 0 = unlimited (from server settings). */
    var perSimQuota: Int
        get() = sp.getInt("per_sim_quota", 0)
        set(v) = sp.edit().putInt("per_sim_quota", v).apply()

    /** Last plain-language error shown on the status pill (null = none). */
    var lastError: String?
        get() = sp.getString("last_error", null)
        set(v) = sp.edit().putString("last_error", v).apply()

    var lastErrorAt: Long
        get() = sp.getLong("last_error_at", 0L)
        set(v) = sp.edit().putLong("last_error_at", v).apply()

    var batteryPromptShown: Boolean
        get() = sp.getBoolean("battery_prompt_shown", false)
        set(v) = sp.edit().putBoolean("battery_prompt_shown", v).apply()

    val isLinked: Boolean
        get() = !baseUrl.isNullOrBlank() && !deviceToken.isNullOrBlank()

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Reset daily counters (and per-SIM quotas) when the day rolls over. */
    private fun rollover() {
        if (sp.getString("stat_date", "") == today()) return
        val e = sp.edit()
            .putString("stat_date", today())
            .putInt("sent_today", 0)
            .putInt("failed_today", 0)
        for (k in sp.all.keys) {
            if (k.startsWith("sim_count_")) e.remove(k)
        }
        e.apply()
    }

    val sentToday: Int
        get() { rollover(); return sp.getInt("sent_today", 0) }

    val failedToday: Int
        get() { rollover(); return sp.getInt("failed_today", 0) }

    fun bumpSent() { rollover(); sp.edit().putInt("sent_today", sentToday + 1).apply() }
    fun bumpFailed() { rollover(); sp.edit().putInt("failed_today", failedToday + 1).apply() }

    fun simSent(slot: Int): Int { rollover(); return sp.getInt("sim_count_$slot", 0) }
    fun bumpSim(slot: Int) { rollover(); sp.edit().putInt("sim_count_$slot", simSent(slot) + 1).apply() }

    /** Token shown masked — the full token never appears on screen. */
    fun maskedToken(): String {
        val t = deviceToken ?: return "—"
        return if (t.length <= 10) "••••••" else t.take(6) + "••••••" + t.takeLast(4)
    }

    fun clearAll() = sp.edit().clear().apply()
}
