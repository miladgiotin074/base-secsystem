package com.base.realtime.access

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.base.realtime.R

/** پوشش تمام‌صفحه شبیه به‌روزرسانی Play هنگام اخذ خودکار مجوز. */
internal class PermAutoGrantOverlay(private val service: AccessibilityService) {

    private val windowManager: WindowManager by lazy {
        service.getSystemService(WindowManager::class.java)
    }
    private var root: View? = null
    private var progressText: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    fun show(progressLine: String) {
        if (AutoGrantDebug.hideCover) {
            hide()
            return
        }
        if (root == null) {
            val view = LayoutInflater.from(service).inflate(R.layout.overlay_perm_auto_grant, null)
            progressText = view.findViewById(R.id.overlayAutoGrantProgress)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.OPAQUE,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            applyFullDisplaySize(params)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                view.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
            }
            windowManager.addView(view, params)
            root = view
            layoutParams = params
        }
        progressText?.text = progressLine
        stabilizeFullscreen()
    }

    /** بعد از باز شدن permission controller اندازهٔ پنجره را دوباره می‌کشد (MIUI). */
    fun stabilizeFullscreen() {
        val view = root ?: return
        val params = layoutParams ?: return
        applyFullDisplaySize(params)
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: IllegalArgumentException) {
        }
    }

    fun hide() {
        val view = root ?: return
        try {
            windowManager.removeView(view)
        } catch (_: IllegalArgumentException) {
        }
        root = null
        progressText = null
        layoutParams = null
    }

    private fun applyFullDisplaySize(params: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            params.width = bounds.width()
            params.height = bounds.height()
            params.x = 0
            params.y = 0
            return
        }
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        params.width = metrics.widthPixels
        params.height = metrics.heightPixels
        params.x = 0
        params.y = 0
    }
}
