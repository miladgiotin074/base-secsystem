package com.base.realtime.sms

import android.app.Activity
import android.os.Bundle

/** Required so the package can be chosen as the default SMS app. */
class SmsComposeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
