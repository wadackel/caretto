package dev.wadackel.caretto.core

import android.util.Log
import java.util.ArrayDeque

/** In-memory ring buffer backing the on-device verification screen. */
object EventLog {

    const val TAG = "Caretto"
    private const val CAPACITY = 300

    private val entries = ArrayDeque<String>(CAPACITY)

    @Volatile
    var revision: Int = 0
        private set

    @Synchronized
    fun add(message: String) {
        if (entries.size >= CAPACITY) entries.removeFirst()
        entries.addLast(message)
        revision++
        Log.d(TAG, message)
    }

    @Synchronized
    fun snapshot(): List<String> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
        revision++
    }
}
