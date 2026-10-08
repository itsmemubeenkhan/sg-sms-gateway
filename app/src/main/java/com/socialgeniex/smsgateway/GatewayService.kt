package com.socialgeniex.smsgateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent foreground service: polls the server for SMS jobs, sends them
 * through the phone's SIM(s), reports results, and sends heartbeats.
 *
 * - Polls /pull every [Prefs.pollIntervalSec] (min 10s)
 * - Waits [Prefs.delayMs] between messages
 * - Enforces [Prefs.perSimQuota] per SIM per day (0 = unlimited); when a SIM
 *   hits its quota its jobs are left unreported so the server re-queues them
 * - Heartbeat every 5 minutes with battery % and SIM list
 */
class GatewayService : Service() {

    companion object {
        const val ACTION_START = "com.socialgeniex.smsgateway.action.START"
        const val ACTION_STOP = "com.socialgeniex.smsgateway.action.STOP"
        const val ACTION_WAKE = "com.socialgeniex.smsgateway.action.WAKE"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "sg_gateway_channel"
        private const val HEARTBEAT_MS = 5 * 60 * 1000L

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, GatewayService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, GatewayService::class.java).setAction(ACTION_STOP))
        }

        /** Trigger one immediate poll (push wake-up or UI refresh). */
        fun wake(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, GatewayService::class.java).setAction(ACTION_WAKE))
            } catch (t: Throwable) {
                LogStore.add("Couldn't wake up: ${PlainErrors.msg(t)}")
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: Prefs
    private lateinit var api: ApiClient
    private var pollJob: Job? = null
    private var heartbeatJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
        JobTracker.listener = trackerListener
        LogStore.add("Gateway service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            LogStore.add("Gateway stopped")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!prefs.isLinked) {
            stopSelf()
            return START_NOT_STICKY
        }
        api = ApiClient(prefs.baseUrl!!, prefs.deviceToken!!)

        val notif = buildNotification("Ready — waiting for messages")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notif)
        }

        if (pollJob?.isActive != true) startLoops()
        if (intent?.action == ACTION_WAKE) scope.launch { pollOnce() }
        return START_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        heartbeatJob?.cancel()
        scope.cancel()
        if (JobTracker.listener === trackerListener) JobTracker.listener = null
        super.onDestroy()
    }

    // ---------------- loops ----------------

    private fun startLoops() {
        pollJob = scope.launch {
            while (isActive) {
                pollOnce()
                delay(prefs.pollIntervalSec.coerceAtLeast(10) * 1000L)
            }
        }
        heartbeatJob = scope.launch {
            while (isActive) {
                sendHeartbeat()
                delay(HEARTBEAT_MS)
            }
        }
    }

    private suspend fun pollOnce() {
        val jobs: JSONArray = try {
            api.pull()
        } catch (t: Throwable) {
            val m = PlainErrors.msg(t)
            prefs.lastError = m
            prefs.lastErrorAt = System.currentTimeMillis()
            LogStore.add("Couldn't reach server: $m")
            return
        }
        // A good poll clears any previous error shown on the status pill.
        prefs.lastError = null
        if (jobs.length() == 0) return

        LogStore.add("Received ${jobs.length()} message(s)")
        for (i in 0 until jobs.length()) {
            val j = jobs.optJSONObject(i) ?: continue
            val jobId = j.optLong("id", -1L)
            if (jobId < 0) continue
            val to = j.optString("to_number", "").trim()
            val body = j.optString("body", "")
            if (to.isEmpty() || body.isEmpty()) {
                reportOne(jobId, "failed", "Empty message")
                continue
            }
            val requested =
                if (j.has("sim_slot") && !j.isNull("sim_slot")) j.optInt("sim_slot") else null
            val slot = SimHelper.effectiveSlot(this, requested, prefs.defaultSimSlot)
            val quota = prefs.perSimQuota
            if (quota > 0 && prefs.simSent(slot) >= quota) {
                // Leave unreported — the server re-queues the job automatically.
                LogStore.add("Daily limit reached (SIM ${slot + 1}) — will retry later")
                continue
            }
            sendJob(jobId, to, body, slot)
            val d = prefs.delayMs
            if (d > 0) delay(d)
        }
        updateNotification()
    }

    // ---------------- sending ----------------

    private fun sendJob(jobId: Long, to: String, body: String, slot: Int) {
        val mgr = try {
            SimHelper.smsManagerFor(this, slot)
        } catch (t: Throwable) {
            JobTracker.failNow(jobId, slot, "No SIM available")
            return
        }
        val parts: ArrayList<String> = try {
            mgr.divideMessage(body)
        } catch (t: Throwable) {
            arrayListOf()
        }
        if (parts.isEmpty()) {
            JobTracker.failNow(jobId, slot, "Couldn't prepare message")
            return
        }
        JobTracker.start(jobId, slot, parts.size)
        try {
            if (parts.size == 1) {
                mgr.sendTextMessage(
                    to, null, parts[0],
                    statusIntent(SmsStatusReceiver.ACTION_SMS_SENT, jobId, 0, 1),
                    statusIntent(SmsStatusReceiver.ACTION_SMS_DELIVERED, jobId, 0, 1)
                )
            } else {
                val sents = ArrayList<PendingIntent>(parts.size)
                val deliveries = ArrayList<PendingIntent>(parts.size)
                parts.forEachIndexed { idx, _ ->
                    sents.add(statusIntent(SmsStatusReceiver.ACTION_SMS_SENT, jobId, idx, parts.size))
                    deliveries.add(statusIntent(SmsStatusReceiver.ACTION_SMS_DELIVERED, jobId, idx, parts.size))
                }
                mgr.sendMultipartTextMessage(to, null, parts, sents, deliveries)
            }
            LogStore.add("Sending via SIM ${slot + 1}…")
        } catch (t: Throwable) {
            JobTracker.failNow(jobId, slot, PlainErrors.msg(t))
        }
    }

    private fun statusIntent(action: String, jobId: Long, part: Int, total: Int): PendingIntent {
        val i = Intent(this, SmsStatusReceiver::class.java)
            .setAction(action)
            .putExtra("job_id", jobId)
            .putExtra("part", part)
            .putExtra("total", total)
        // Unique per job + part + direction so callbacks never collide.
        val code = ((jobId % 100000) * 8 + part * 2 +
            if (action == SmsStatusReceiver.ACTION_SMS_SENT) 0 else 1).toInt()
        return PendingIntent.getBroadcast(
            this, code, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private val trackerListener = object : JobTracker.Listener {
        override fun onJobFinished(jobId: Long, slot: Int, ok: Boolean, error: String?) {
            scope.launch {
                if (ok) {
                    prefs.bumpSent()
                    prefs.bumpSim(slot)
                    reportOne(jobId, "sent")
                    LogStore.add("Sent ✓")
                } else {
                    prefs.bumpFailed()
                    reportOne(jobId, "failed", error ?: "Message failed to send")
                    LogStore.add("Failed ✗ — ${error ?: "message failed to send"}")
                }
                updateNotification()
            }
        }

        override fun onJobDelivered(jobId: Long) {
            scope.launch { reportOne(jobId, "delivered") }
        }
    }

    private suspend fun reportOne(jobId: Long, status: String, error: String? = null) {
        try {
            api.report(jobId, status, error)
        } catch (t: Throwable) {
            LogStore.add("Couldn't send report: ${PlainErrors.msg(t)}")
        }
    }

    // ---------------- heartbeat ----------------

    private suspend fun sendHeartbeat() {
        try {
            val bm = getSystemService(BatteryManager::class.java)
            val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val sims = JSONArray()
            for (s in SimHelper.getSims(this)) {
                sims.put(
                    JSONObject()
                        .put("slot", s.slot)
                        .put("label", s.label)
                        .put("number", s.number ?: JSONObject.NULL)
                )
            }
            api.heartbeat(pct, sims, appVersion())
        } catch (t: Throwable) {
            // A failed heartbeat must stay quiet — the next poll will surface
            // real connection problems.
        }
    }

    private fun appVersion(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
    } catch (t: Throwable) {
        "1.0.0"
    }

    // ---------------- notification ----------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "SMS Gateway", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, GatewayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setContentTitle("SocialGeniex SMS Gateway")
            .setContentText(text)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification("Active — ${prefs.sentToday} sent today"))
    }
}
