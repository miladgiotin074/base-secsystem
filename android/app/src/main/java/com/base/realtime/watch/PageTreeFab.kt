package com.base.realtime.watch

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import android.widget.Toast

/** Floating dump button: prints full clean accessibility tree to log + clipboard. */
internal class PageTreeFab(private val service: AccessibilityService) {
    private val windowManager: WindowManager by lazy { service.getSystemService(WindowManager::class.java) }
    private var button: TextView? = null

    fun show() {
        if (button != null) return
        val density = service.resources.displayMetrics.density
        val size = (52 * density).toInt()
        val margin = (16 * density).toInt()
        val view = TextView(service).apply {
            text = "Tree"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(210, 33, 33, 33))
                setStroke((1.5f * density).toInt().coerceAtLeast(1), Color.argb(180, 255, 255, 255))
            }
            setOnClickListener { dumpNow() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = margin
            y = margin + (48 * density).toInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        try {
            windowManager.addView(view, lp)
            button = view
        } catch (_: Throwable) {
        }
    }

    fun hide() {
        val view = button ?: return
        button = null
        try {
            windowManager.removeView(view)
        } catch (_: Throwable) {
        }
    }

    private fun dumpNow() {
        val pkg = foregroundPackage()
        if (pkg.isNullOrBlank()) {
            Toast.makeText(service, "صفحه پیدا نشد", Toast.LENGTH_SHORT).show()
            return
        }
        val lines = PageDump.collectTreeFull(service, pkg)
        val body = buildString {
            appendLine("pkg=$pkg")
            appendLine("lines=${lines.size}")
            appendLine("---")
            for (line in lines) appendLine(line)
        }
        logChunks(body)
        try {
            val cm = service.getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("page-tree", body))
        } catch (_: Throwable) {
        }
        Toast.makeText(service, "ساختار صفحه: ${lines.size} خط", Toast.LENGTH_SHORT).show()
    }

    private fun foregroundPackage(): String? {
        val windows = service.windows ?: return null
        var active: String? = null
        try {
            for (window in windows) {
                if (!window.isActive || window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = window.root ?: continue
                val pkg = root.packageName?.toString()
                root.recycle()
                if (!pkg.isNullOrBlank()) active = pkg
            }
        } finally {
            for (window in windows) {
                try {
                    window.recycle()
                } catch (_: Throwable) {
                }
            }
        }
        return active
    }

    private fun logChunks(text: String) {
        Log.i(TAG, "===== page tree begin =====")
        var offset = 0
        while (offset < text.length) {
            val end = (offset + CHUNK).coerceAtMost(text.length)
            Log.i(TAG, text.substring(offset, end))
            offset = end
        }
        Log.i(TAG, "===== page tree end =====")
    }

    companion object {
        private const val TAG = "PageTree"
        private const val CHUNK = 3500
    }
}
