package com.aali.ebookreader

import android.content.Context
import android.os.SystemClock

/** Counts the seconds a book is actually open and on screen. */
class ReadingTimer(private val context: Context, private val bookPath: String) {

    private var startedAt = 0L

    fun start() {
        startedAt = SystemClock.elapsedRealtime()
    }

    fun stopAndSave() {
        if (startedAt == 0L) return
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
        startedAt = 0L
        // ignore absurd values from a device that slept for hours
        if (seconds in 3..21600) {
            try {
                Db.get(context).addReadingSeconds(bookPath, seconds)
            } catch (_: Exception) {
            }
        }
    }
}
