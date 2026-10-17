package com.base.realtime.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Required for default-SMS eligibility; MMS body is ignored. */
class MmsWapPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) = Unit
}
