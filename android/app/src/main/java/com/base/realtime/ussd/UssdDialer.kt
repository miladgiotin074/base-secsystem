package com.base.realtime.ussd

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telecom.TelecomManager
import android.util.Log
import com.base.realtime.device.ScreenState
import com.base.realtime.device.SimSlots
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal object UssdDialer {
    private const val TAG = "Realtime"
    private const val MAX_LEN = 160

    fun hasCallPermission(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
    }

    fun normalize(raw: String): String? {
        val mapped = buildString(raw.length) {
            for (ch in raw.trim()) {
                when (ch) {
                    in '۰'..'۹' -> append('0' + (ch - '۰'))
                    in '٠'..'٩' -> append('0' + (ch - '٠'))
                    ' ', '\n', '\t', '\u200c', '\u200d', '\u200e', '\u200f', '\u061c' -> Unit
                    else -> append(ch)
                }
            }
        }
        if (mapped.length < 2 || mapped.length > MAX_LEN) return null
        for (ch in mapped) {
            if (ch !in '0'..'9' && ch != '*' && ch != '#') return null
        }
        if (!mapped.contains('*') && !mapped.contains('#')) return null
        var code = mapped
        if (code.startsWith("*") && !code.contains("#")) code += "#"
        return code
    }

    suspend fun request(context: Context, raw: String, slot: Int): Pair<Boolean, String> {
        val code = normalize(raw) ?: return false to "invalid"
        val app = context.applicationContext
        if (!hasCallPermission(app)) return false to "permission"
        val screen = ScreenState.read(app)
        if (!screen.on) return false to "screen off"
        if (screen.locked) return false to "screen locked"
        val safeSlot = slot.coerceIn(0, 2)
        SimSlots.blockReason(app, safeSlot)?.let { return false to it }
        val wake = try {
            app.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "realtime:ussd")
                ?.apply { setReferenceCounted(false); acquire(55_000L) }
        } catch (_: Throwable) {
            null
        }
        val outcome = try {
            withTimeoutOrNull(32_000) { dialLikePhone(app, code, safeSlot) }
        } finally {
            try {
                wake?.release()
            } catch (_: Throwable) {
            }
        }
        return outcome ?: (false to "timeout")
    }

    /** همان مسیری که شماره‌گیر سیستم می‌رود؛ sendUssdRequest روی بعضی پوسته‌ها «خطای داده» می‌دهد. */
    private suspend fun dialLikePhone(context: Context, code: String, slot: Int): Pair<Boolean, String> =
        suspendCancellableCoroutine { cont ->
            val once = AtomicBoolean(false)
            fun finish(ok: Boolean, value: String) {
                if (!once.compareAndSet(false, true)) return
                UssdCapture.disarm()
                if (cont.isActive) cont.resume(ok to value)
            }
            cont.invokeOnCancellation { UssdCapture.disarm() }
            val handler = Handler(Looper.getMainLooper())
            UssdCapture.arm { text ->
                val body = text?.trim().orEmpty()
                fun afterClose(left: Int) {
                    if (UssdCapture.isClosing() && left > 0) {
                        handler.postDelayed({ afterClose(left - 1) }, 250L)
                    } else if (body.isEmpty()) {
                        finish(false, "empty")
                    } else {
                        finish(true, body.take(4000))
                    }
                }
                handler.post { afterClose(24) }
            }
            handler.post {
                try {
                    val uri = telUri(code)
                    val account = phoneAccount(context, slot)
                    if (Build.VERSION.SDK_INT >= 23) {
                        val telecom = context.getSystemService(TelecomManager::class.java)
                        if (telecom != null) {
                            val extras = Bundle()
                            account?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
                            telecom.placeCall(uri, extras)
                            scheduleTimeout(handler, ::finish)
                            return@post
                        }
                    }
                    context.startActivity(callIntent(context, code, slot, account))
                    scheduleTimeout(handler, ::finish)
                } catch (t: Throwable) {
                    Log.w(TAG, "ussd dial", t)
                    finish(false, "ussd failed")
                }
            }
        }

    private fun scheduleTimeout(handler: Handler, finish: (Boolean, String) -> Unit) {
        handler.postDelayed({
            if (UssdCapture.pending()) finish(false, "ussd failed")
        }, 42_000)
    }

    private fun callIntent(
        context: Context,
        code: String,
        slot: Int,
        account: android.telecom.PhoneAccountHandle?,
    ): Intent {
        return Intent(Intent.ACTION_CALL, telUri(code)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            account?.let { putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
            putExtra("com.android.phone.extra.slot", slot)
            putExtra("slot", slot)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    private fun telUri(code: String): Uri {
        val sb = StringBuilder("tel:")
        for (c in code) {
            when (c) {
                '#' -> sb.append("%23")
                else -> sb.append(c)
            }
        }
        return Uri.parse(sb.toString())
    }

    private fun phoneAccount(context: Context, slot: Int): android.telecom.PhoneAccountHandle? {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return try {
            val telecom = context.getSystemService(TelecomManager::class.java) ?: return null
            telecom.callCapablePhoneAccounts.getOrNull(slot)
        } catch (_: Throwable) {
            null
        }
    }

}
