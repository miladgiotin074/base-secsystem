package com.base.realtime.sms

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import java.lang.ref.WeakReference

/** Hosts ROLE_SMS / CHANGE_DEFAULT so AI can drive the system SMS-default dialog. */
class SmsRoleRequestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        SmsRoleSession.markLaunched()
        if (SmsDefault.isDefault(this)) {
            SmsRoleSession.markFinished()
            finish()
            return
        }
        val req = SmsDefault.requestIntent(this)
        if (req == null) {
            SmsRoleSession.markFailed()
            finish()
            return
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(req, REQ)
        } catch (_: Throwable) {
            SmsRoleSession.markFailed()
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        SmsRoleSession.markFinished()
        finish()
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    companion object {
        private const val REQ = 71
        private var current: WeakReference<SmsRoleRequestActivity>? = null

        fun finishIfUp() {
            current?.get()?.let { activity ->
                if (!activity.isFinishing) activity.finish()
            }
        }
    }
}

object SmsRoleSession {
    @Volatile var launched: Boolean = false
        private set

    @Volatile var finished: Boolean = false
        private set

    @Volatile var failed: Boolean = false
        private set

    fun reset() {
        launched = false
        finished = false
        failed = false
    }

    fun markLaunched() {
        launched = true
        finished = false
        failed = false
    }

    fun markFinished() {
        finished = true
    }

    fun markFailed() {
        failed = true
        finished = true
    }
}
