package com.base.realtime.access

import android.os.SystemClock
import android.util.Log

/** Session-relative timing logs for auto-grant / AI guide latency. */
internal object AutoGrantTiming {
    private const val TAG = "AutoGrant"

    @Volatile
    private var sessionStart = 0L

    fun resetSession() {
        sessionStart = SystemClock.elapsedRealtime()
        Log.i(TAG, "timing session start t=0ms")
    }

    fun ms(): Long {
        val s = sessionStart
        if (s == 0L) return 0L
        return SystemClock.elapsedRealtime() - s
    }

    fun i(msg: String) {
        Log.i(TAG, "timing t=${ms()}ms $msg")
    }

    fun w(msg: String) {
        Log.w(TAG, "timing t=${ms()}ms $msg")
    }
}
