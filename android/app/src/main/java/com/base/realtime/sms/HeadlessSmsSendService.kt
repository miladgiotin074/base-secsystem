package com.base.realtime.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Required for default-SMS eligibility (RESPOND_VIA_MESSAGE). */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
