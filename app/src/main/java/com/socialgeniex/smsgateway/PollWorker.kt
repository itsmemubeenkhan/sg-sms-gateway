package com.socialgeniex.smsgateway

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Backup poller: if the phone's OS kills the foreground GatewayService
 * (aggressive on Infinix/Oppo/Vivo/Xiaomi), this periodic worker restarts
 * it and triggers an immediate poll. Runs every 15 minutes (Android minimum).
 */
class PollWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return try {
            val prefs = Prefs(applicationContext)
            if (!prefs.isLinked) {
                cancelAll(applicationContext)
                return Result.success()
            }
            // Restart the foreground service if it died, then poll now.
            GatewayService.start(applicationContext)
            LogStore.add("Background check: service restarted, polling")
            GatewayService.wake(applicationContext)
            Result.success()
        } catch (t: Throwable) {
            LogStore.add("Background check failed: ${PlainErrors.msg(t)}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "sg_poll_worker"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<PollWorker>(15, TimeUnit.MINUTES)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                TAG, ExistingPeriodicWorkPolicy.KEEP, req
            )
            LogStore.add("Background polling scheduled (every 15 min)")
        }

        fun cancelAll(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(TAG)
        }
    }
}
