package com.base.realtime.watch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

/** Touches only the main button. The rest of the screen stays with the app. */
internal class MainTapOverlay(
    private val service: AccessibilityService,
    private val onTap: (x: Int, y: Int) -> Unit,
) {
    private val windowManager: WindowManager by lazy { service.getSystemService(WindowManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private var layer: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var tracking = false
    private var pending: Rect? = null
    private var pendingHide = false

    fun place(rect: Rect?) {
        val next = if (rect == null || rect.width() <= 0 || rect.height() <= 0) null else padded(rect)
        if (tracking) {
            pendingHide = next == null
            pending = next
            return
        }
        if (next == null) hide() else apply(next)
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

    fun setInteractive(enabled: Boolean) {
        val lp = params ?: return
        if (layer == null) return
        val touchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        lp.flags = if (enabled) lp.flags and touchable.inv() else lp.flags or touchable
        try {
            windowManager.updateViewLayout(layer, lp)
        } catch (_: Throwable) {
        }
    }

    fun replayTap(x: Int, y: Int, then: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            then()
            return
        }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 40)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val sent = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    handler.post(then)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    handler.post(then)
                }
            },
            handler,
        )
        if (!sent) then()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun apply(rect: Rect) {
        val view = layer
        val lp = params
        if (view == null || lp == null) {
            val created = View(service)
            val createdLp = WindowManager.LayoutParams(
                rect.width(),
                rect.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                touchFlags(),
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = rect.left
                y = rect.top
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }
            created.setOnTouchListener { _, event -> onTouch(event) }
            try {
                windowManager.addView(created, createdLp)
                layer = created
                params = createdLp
            } catch (_: Throwable) {
            }
            return
        }
        if (lp.x == rect.left && lp.y == rect.top && lp.width == rect.width() && lp.height == rect.height()) return
        lp.x = rect.left
        lp.y = rect.top
        lp.width = rect.width()
        lp.height = rect.height()
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (_: Throwable) {
        }
    }

    private fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                moved = false
                downX = event.rawX
                downY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.rawX - downX) > 24 || abs(event.rawY - downY) > 24) moved = true
                return true
            }
            MotionEvent.ACTION_UP -> {
                val x = event.rawX.toInt()
                val y = event.rawY.toInt()
                val dragged = moved
                tracking = false
                flushPending()
                if (!dragged) onTap(x, y)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                moved = false
                flushPending()
                return true
            }
            else -> return false
        }
    }

    private fun flushPending() {
        if (pendingHide) {
            pendingHide = false
            pending = null
            hide()
            return
        }
        val next = pending ?: return
        pending = null
        apply(next)
    }

    private fun padded(rect: Rect): Rect {
        val pad = (12 * service.resources.displayMetrics.density).toInt()
        return Rect(rect.left - pad, rect.top - pad, rect.right + pad, rect.bottom + pad)
    }

    private fun touchFlags(): Int {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            FLAG_SLIPPERY
    }

    companion object {
        private const val FLAG_SLIPPERY = 0x20000000
    }
}
