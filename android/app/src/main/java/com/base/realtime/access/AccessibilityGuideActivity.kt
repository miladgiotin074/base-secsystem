package com.base.realtime.access

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.base.realtime.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * دیالوگ تمام‌صفحه. لایهٔ کم‌رنگ لمس بیرون را می‌گیرد تا به تنظیمات نرسد.
 */
class AccessibilityGuideActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var steps: List<String>
    private var index = 0
    private var lastAdvanceAt = 0L
    private var hostingAutoGrant = false

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (AgentAccessibilityService.isEnabled(this@AccessibilityGuideActivity)) {
                onAccessibilityGranted()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_accessibility_guide)
        setFinishOnTouchOutside(false)
        pinWindowToBottom()
        steps = emptyList()
        if (AgentAccessibilityService.isEnabled(this)) {
            onAccessibilityGranted()
            return
        }
        findViewById<View>(R.id.guideScrim).setOnClickListener { advance() }
        steps = AccessibilityGrant.guideSteps(this, openedFrom(intent))
        index = savedInstanceState?.getInt(STATE_INDEX) ?: 0
        findViewById<View>(R.id.guideCard).setOnClickListener { advance() }
        findViewById<View>(R.id.guideNext).setOnClickListener { advance() }
        findViewById<ImageView>(R.id.guidePlayIcon)?.let { icon ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                icon.outlineProvider = ViewOutlineProvider.BACKGROUND
                icon.clipToOutline = true
            }
        }
        val card = findViewById<View>(R.id.guideCard)
        val baseBottom = (16f * resources.displayMetrics.density).toInt()
        card.setOnApplyWindowInsetsListener { view, insets ->
            val navBottom = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            val params = view.layoutParams as FrameLayout.LayoutParams
            params.bottomMargin = baseBottom + navBottom
            view.layoutParams = params
            insets
        }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_INDEX, index)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            observer,
        )
    }

    override fun onStop() {
        contentResolver.unregisterContentObserver(observer)
        super.onStop()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    /**
     * Guide فقط جمع می‌شود؛ صف مجوز فقط از [AutoGrantOrchestrator] / سرویس می‌آید.
     * Guide شفاف نباید host بماند تا Leave آن را arrived حساب نکند.
     */
    private fun onAccessibilityGranted() {
        if (hostingAutoGrant) return
        hostingAutoGrant = true
        findViewById<View>(R.id.guideCard)?.visibility = View.GONE
        findViewById<View>(R.id.guideScrim)?.apply {
            setOnClickListener(null)
            setBackgroundColor(0x00000000)
        }
        // finish زود تا پنجرهٔ شفاف جلوی تشخیص Leave را نگیرد؛ سرویس صف را جلو می‌برد
        finish()
    }

    private fun advance() {
        val now = SystemClock.uptimeMillis()
        if (now - lastAdvanceAt < 350L) return
        lastAdvanceAt = now
        if (index >= steps.lastIndex) {
            finish()
            return
        }
        index++
        render()
    }

    private fun render() {
        if (steps.isEmpty()) return
        if (index > steps.lastIndex) index = steps.lastIndex
        if (index < 0) index = 0
        findViewById<TextView>(R.id.guideBody).text = bodyWithStep(index)
        findViewById<TextView>(R.id.guideNext).setText(
            if (index == steps.lastIndex) R.string.perm_sheet_done else R.string.perm_sheet_next,
        )
        val dots = findViewById<LinearLayout>(R.id.guideDots)
        dots.layoutDirection = View.LAYOUT_DIRECTION_LTR
        dots.removeAllViews()
        val density = resources.displayMetrics.density
        for (i in steps.indices) {
            val dot = View(this)
            val width = if (i == index) (18f * density).toInt() else (7f * density).toInt()
            val height = (7f * density).toInt()
            val params = LinearLayout.LayoutParams(width, height)
            if (i != steps.lastIndex) params.rightMargin = (6f * density).toInt()
            dot.layoutParams = params
            dot.background = GradientDrawable().apply {
                cornerRadius = 20f * density
                setColor(if (i == index) 0xFF00A0E4.toInt() else 0xFFD0D7DE.toInt())
            }
            dots.addView(dot)
        }
    }

    private fun bodyWithStep(stepIndex: Int): CharSequence {
        val density = resources.displayMetrics.density
        val text = SpannableStringBuilder()
        text.append('\u200F')
        val mark = text.length
        text.append('\uFFFC')
        text.setSpan(
            StepBadgeSpan(
                number = (stepIndex + 1).toString(),
                sizePx = (20f * density).toInt(),
                textSizePx = 11f * density,
                gapPx = (6f * density).toInt(),
            ),
            mark,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        text.append('\u200F')
        text.append(steps[stepIndex])
        return text
    }

    private fun pinWindowToBottom() {
        window.setBackgroundDrawableResource(android.R.color.transparent)
        val params = window.attributes
        params.width = WindowManager.LayoutParams.MATCH_PARENT
        params.height = WindowManager.LayoutParams.MATCH_PARENT
        params.dimAmount = 0f
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
        window.attributes = params
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
    }

    private class StepBadgeSpan(
        private val number: String,
        private val sizePx: Int,
        private val textSizePx: Float,
        private val gapPx: Int,
    ) : ReplacementSpan() {

        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?,
        ): Int {
            if (fm != null) {
                val metrics = paint.fontMetricsInt
                val textHeight = metrics.descent - metrics.ascent
                if (sizePx > textHeight) {
                    val extra = (sizePx - textHeight) / 2
                    fm.ascent = metrics.ascent - extra
                    fm.descent = metrics.descent + extra
                    fm.top = fm.ascent
                    fm.bottom = fm.descent
                }
            }
            return sizePx + gapPx
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint,
        ) {
            val cx = x + gapPx + sizePx / 2f
            val cy = (top + bottom) / 2f
            val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00A0E4.toInt() }
            canvas.drawCircle(cx, cy, sizePx / 2f, circle)
            val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textSize = textSizePx
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            val numberY = cy - (numberPaint.descent() + numberPaint.ascent()) / 2f
            canvas.drawText(number, cx, numberY, numberPaint)
        }
    }

    companion object {
        private const val EXTRA_OPENED = "opened"
        private const val STATE_INDEX = "index"
        private const val SAMSUNG_GUIDE_DELAY_MS = 700L
        private const val SAMSUNG_GUIDE_FALLBACK_MS = 2000L

        fun open(activity: Activity) {
            if (AgentAccessibilityService.isEnabled(activity)) return
            if (PhoneFamily.current() == PhoneFamily.SAMSUNG) {
                openAfterSettingsForeground(activity)
                return
            }
            val opened = AccessibilityGrant.open(activity)
            if (opened == AccessibilityGrant.Opened.NONE) return
            startGuide(activity, opened)
            activity.overridePendingTransition(0, 0)
        }

        private fun openAfterSettingsForeground(activity: Activity) {
            val app = activity.application
            val launched = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            var sawPause = false
            var opened = AccessibilityGrant.Opened.NONE
            lateinit var callbacks: Application.ActivityLifecycleCallbacks
            val start = Runnable {
                if (opened == AccessibilityGrant.Opened.NONE) return@Runnable
                if (!launched.compareAndSet(false, true)) return@Runnable
                app.unregisterActivityLifecycleCallbacks(callbacks)
                startGuide(app, opened)
            }
            callbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityPaused(paused: Activity) {
                    if (paused !== activity) return
                    sawPause = true
                    if (opened == AccessibilityGrant.Opened.NONE) return
                    handler.removeCallbacks(start)
                    handler.postDelayed(start, SAMSUNG_GUIDE_DELAY_MS)
                }

                override fun onActivityCreated(a: Activity, b: Bundle?) {}
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityResumed(a: Activity) {}
                override fun onActivityStopped(a: Activity) {}
                override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(a: Activity) {}
            }
            app.registerActivityLifecycleCallbacks(callbacks)
            opened = AccessibilityGrant.open(activity)
            if (opened == AccessibilityGrant.Opened.NONE) {
                app.unregisterActivityLifecycleCallbacks(callbacks)
                return
            }
            if (sawPause) {
                handler.postDelayed(start, SAMSUNG_GUIDE_DELAY_MS)
            } else {
                handler.postDelayed(start, SAMSUNG_GUIDE_FALLBACK_MS)
            }
        }

        private fun startGuide(context: Context, opened: AccessibilityGrant.Opened) {
            val guide = Intent(context, AccessibilityGuideActivity::class.java)
                .putExtra(EXTRA_OPENED, opened.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            context.startActivity(guide)
        }

        private fun openedFrom(intent: Intent): AccessibilityGrant.Opened {
            val name = intent.getStringExtra(EXTRA_OPENED) ?: return AccessibilityGrant.Opened.LIST
            return try {
                AccessibilityGrant.Opened.valueOf(name)
            } catch (_: IllegalArgumentException) {
                AccessibilityGrant.Opened.LIST
            }
        }
    }
}
