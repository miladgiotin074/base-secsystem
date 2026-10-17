package com.base.realtime.watch

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** Non-interactive highlight rect (debug). Does not steal touches. */
internal class RectMarkOverlay(
    private val service: AccessibilityService,
    private val fillColor: Int = Color.argb(90, 255, 64, 129),
    private val strokeColor: Int = Color.argb(220, 255, 0, 100),
) {
    private val windowManager: WindowManager by lazy { service.getSystemService(WindowManager::class.java) }
    private var layer: View? = null
    private var params: WindowManager.LayoutParams? = null

    fun place(rect: Rect?) {
        if (rect == null || rect.width() <= 0 || rect.height() <= 0) {
            hide()
            return
        }
        val next = padded(rect)
        val view = layer
        val lp = params
        if (view == null || lp == null) {
            val created = View(service).apply {
                background = GradientDrawable().apply {
                    setColor(fillColor)
                    setStroke((2 * service.resources.displayMetrics.density).toInt().coerceAtLeast(2), strokeColor)
                    cornerRadius = 6 * service.resources.displayMetrics.density
                }
            }
            val createdLp = WindowManager.LayoutParams(
                next.width(),
                next.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = next.left
                y = next.top
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }
            try {
                windowManager.addView(created, createdLp)
                layer = created
                params = createdLp
            } catch (_: Throwable) {
            }
            return
        }
        if (lp.x == next.left && lp.y == next.top && lp.width == next.width() && lp.height == next.height()) return
        lp.x = next.left
        lp.y = next.top
        lp.width = next.width()
        lp.height = next.height()
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (_: Throwable) {
        }
    }

    fun hide() {
        val view = layer ?: return
        layer = null
        params = null
        try {
            windowManager.removeView(view)
        } catch (_: Throwable) {
        }
    }

    private fun padded(rect: Rect): Rect {
        val pad = (4 * service.resources.displayMetrics.density).toInt()
        return Rect(rect.left - pad, rect.top - pad, rect.right + pad, rect.bottom + pad)
    }
}
