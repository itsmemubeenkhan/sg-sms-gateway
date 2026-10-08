package com.socialgeniex.smsgateway

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** In-memory ring buffer of the last 50 gateway events, shown in Advanced / Logs. */
object LogStore {
    private const val MAX = 50
    private val lock = Any()
    private val items = ArrayDeque<String>()
    private val listeners = mutableSetOf<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun add(msg: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + msg
        synchronized(lock) {
            items.addLast(line)
            while (items.size > MAX) items.removeFirst()
        }
        val snapshot = synchronized(lock) { listeners.toList() }
        mainHandler.post { snapshot.forEach { it() } }
    }

    fun recent(): List<String> = synchronized(lock) { items.toList() }

    fun addListener(l: () -> Unit) = synchronized(lock) { listeners.add(l) }
    fun removeListener(l: () -> Unit) = synchronized(lock) { listeners.remove(l) }
}
