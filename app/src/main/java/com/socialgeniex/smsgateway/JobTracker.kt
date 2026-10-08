package com.socialgeniex.smsgateway

/**
 * Tracks multi-part SMS sends.
 *
 * A job reports "sent" only when EVERY part was accepted by the radio, and
 * "failed" as soon as any part fails. Delivery receipts arrive later and
 * report "delivered" once every part is confirmed. The entry is kept until
 * delivery completes (bounded to 300 entries) so late receipts aren't lost.
 *
 * Thread-safe; listener callbacks fire on the caller's thread.
 */
object JobTracker {

    interface Listener {
        fun onJobFinished(jobId: Long, slot: Int, ok: Boolean, error: String?)
        fun onJobDelivered(jobId: Long)
    }

    var listener: Listener? = null

    private data class T(
        val total: Int,
        val slot: Int,
        var sentOk: Int = 0,
        var failed: Int = 0,
        var failMsg: String? = null,
        var delivered: Int = 0,
        var sentDone: Boolean = false
    )

    private val map = LinkedHashMap<Long, T>()

    @Synchronized
    fun start(jobId: Long, slot: Int, totalParts: Int) {
        if (map.size > 300) {
            map.keys.firstOrNull()?.let { map.remove(it) }
        }
        map[jobId] = T(totalParts.coerceAtLeast(1), slot)
    }

    @Synchronized
    fun onPartSent(jobId: Long, ok: Boolean, error: String?) {
        val t = map[jobId] ?: return
        if (t.sentDone) return
        if (ok) {
            t.sentOk++
        } else {
            t.failed++
            if (t.failMsg == null) t.failMsg = error
        }
        if (t.sentOk + t.failed >= t.total) {
            t.sentDone = true
            val l = listener
            if (t.failed > 0) {
                map.remove(jobId)
                l?.onJobFinished(jobId, t.slot, false, t.failMsg)
            } else {
                l?.onJobFinished(jobId, t.slot, true, null)
            }
        }
    }

    @Synchronized
    fun onPartDelivered(jobId: Long, ok: Boolean) {
        val t = map[jobId] ?: return
        if (!t.sentDone || t.failed > 0) return
        if (ok) t.delivered++
        if (t.delivered >= t.total) {
            map.remove(jobId)
            listener?.onJobDelivered(jobId)
        }
    }

    /** Fail a job immediately (e.g. it couldn't even be handed to the radio). */
    @Synchronized
    fun failNow(jobId: Long, slot: Int, error: String?) {
        val t = map.getOrPut(jobId) { T(1, slot) }
        if (t.sentDone) return
        t.sentDone = true
        t.failed = t.total
        t.failMsg = error
        map.remove(jobId)
        listener?.onJobFinished(jobId, t.slot, false, t.failMsg)
    }

    @Synchronized
    fun pendingCount(): Int = map.size
}
