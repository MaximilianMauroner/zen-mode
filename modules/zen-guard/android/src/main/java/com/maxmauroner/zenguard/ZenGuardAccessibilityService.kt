package com.maxmauroner.zenguard

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

class ZenGuardAccessibilityService() : AccessibilityService() {
  /** JVM tests can supply the same local ledger without starting Android service callbacks. */
  internal constructor(statsStore: EnforcementStatsStore) : this() {
    stats.replaceStore(statsStore)
  }

  private lateinit var preferences: ZenGuardPreferences
  private val stats = InterventionStats(null)
  private val youtube = YouTubeOwner(this, stats)
  private val x = XOwner(this, stats, canEnforce = ::canRunEnforcementAction)
  private val instagram = InstagramOwner(this, stats, canEnforce = ::canRunEnforcementAction)
  /** Wall-clock injection keeps daily and rolling service tests independent of host time. */
  internal var appRuleWallClock: () -> Long = System::currentTimeMillis
  private lateinit var appLimits: AppLimitStore
  private lateinit var intentStore: IntentAppStore
  private lateinit var rollingLimits: RollingLimitStore
  private lateinit var appRuleSafety: AppRuleSafety
  private val intentSessions = IntentSessionTracker()
  private lateinit var appRuleOverlay: AppRuleOverlay
  private val appCardRefresh by lazy { DeadlineTask(Handler(Looper.getMainLooper()), decide = ::refreshAppRuleOverlay) }
  private val visitDeadline by lazy {
    DeadlineTask(Handler(Looper.getMainLooper())) {
      enforceIntentSession()
      DeadlineDecision.Complete
    }
  }
  private var browserIntervention: BrowserIntervention? = null
  private val usageTracker = AppUsageTracker()
  private val usageHandler by lazy { Handler(Looper.getMainLooper()) }
  private var currentForegroundPackage: String? = null
  private var screenReceiverRegistered = false
  private val screenStateReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action != Intent.ACTION_SCREEN_OFF || !hasActiveProtection()) return
      val nowMs = SystemClock.elapsedRealtime()
      x.screenOff(nowMs)
      instagram.screenOff(nowMs)
      x.publishHomeStatus()
      instagram.publishHomeStatus()
    }
  }

  /**
   * Banks foreground time every [USAGE_TICK_MS] so a long uninterrupted session
   * still trips its budget. Window events alone are not enough: an app the user
   * simply sits in emits nothing.
   */
  private val usageTicker = object : Runnable {
    override fun run() {
      if (canRunEnforcementAction()) {
        usageTracker.tick(SystemClock.elapsedRealtime())?.let(::bankUsage)
        enforceAppLimits(currentForegroundPackage)
        enforceIntentSession()
      }
      usageHandler.postDelayed(this, USAGE_TICK_MS)
    }
  }

  override fun onServiceConnected() {
    super.onServiceConnected()
    preferences = ZenGuardPreferences(this)
    stats.replaceStore(
      try {
        EnforcementStatsStore(this)
      } catch (_: RuntimeException) {
        null
      },
    )
    appLimits = AppLimitStore(this)
    instagram.createTally()
    intentStore = IntentAppStore(this)
    rollingLimits = RollingLimitStore(this)
    appRuleSafety = AppRuleSafety.resolve(this)
    appRuleOverlay = AppRuleOverlay(this)
    browserIntervention?.end()
    browserIntervention = BrowserIntervention(
      service = this,
      stats = stats,
      canEnforce = ::canRunEnforcementAction,
    )
    youtube.onServiceConnected(preferences)
    val homeFeedStatusStore = HomeFeedStatusStore(this)
    val nowElapsedMs = SystemClock.elapsedRealtime()
    instagram.restore(preferences, homeFeedStatusStore, nowElapsedMs)
    x.restore(preferences, homeFeedStatusStore, nowElapsedMs)
    instagram.publishHomeStatus()
    val screenFilter = IntentFilter(Intent.ACTION_SCREEN_OFF)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      registerReceiver(screenStateReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED)
    } else {
      @Suppress("DEPRECATION")
      registerReceiver(screenStateReceiver, screenFilter)
    }
    screenReceiverRegistered = true
    usageHandler.postDelayed(usageTicker, USAGE_TICK_MS)
    x.start()
    instagram.start()
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (!::preferences.isInitialized || event == null) return
    if (!hasActiveProtection()) {
      clearInactiveProtection()
      return
    }

    val eventPackage = event.packageName?.toString()
    if (::appRuleSafety.isInitialized && appRuleSafety.hasSystemSettingsWindow(
        activePackage = rootInActiveWindow?.packageName?.toString(),
        windowPackages = windows.map { it.root?.packageName?.toString() } + eventPackage,
      )
    ) {
      if (appRuleSafety.isSystemSettings(eventPackage) &&
        event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
      ) {
        usageTracker.onForeground(eventPackage!!, SystemClock.elapsedRealtime())?.let(::bankUsage)
      }
      enterSystemSettings()
      return
    }

    trackForegroundApp(event)
    if (appRuleOverlay.requestedPackage() != null) return
    // The blocker is an accessibility overlay owned by this package. Ignore its own focus and
    // content events; treating them as an external app would immediately remove the blocker.
    if (eventPackage == packageName) {
      if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
        if (windows.none { it.root?.packageName?.toString() == YOUTUBE_PACKAGE }) {
          youtube.reset()
        }
        if (windows.none { it.root?.packageName?.toString() == X_PACKAGE }) {
          x.pause()
        }
        if (windows.none { it.root?.packageName?.toString() == INSTAGRAM_PACKAGE }) {
          instagram.pause()
        }
      }
      if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
        windows.none { BrowserUrlDetector.supports(it.root?.packageName?.toString()) }
      ) browserIntervention?.end()
      return
    }
    if (BrowserUrlDetector.supports(eventPackage)) {
      browserIntervention?.observe(eventPackage!!)
    } else if ((event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
        event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) &&
      windows.none { BrowserUrlDetector.supports(it.root?.packageName?.toString()) }
    ) {
      browserIntervention?.end()
    }
    if (eventPackage != X_PACKAGE && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
      windows.none { it.root?.packageName?.toString() == X_PACKAGE }) x.pause()

    when (eventPackage) {
      YOUTUBE_PACKAGE -> {
        instagram.pause(InstagramPause.DEPARTED)
        youtube.observe(event)
      }
      INSTAGRAM_PACKAGE -> instagram.observe(event)
      X_PACKAGE -> {
        instagram.pause()
        x.observe(event)
      }
      else -> instagram.observeOtherApp(event)
    }
  }

  /**
   * Follows which app is in front and charges its time against a daily budget.
   * Only window changes count as a switch; keyboards and system bars raise other
   * event types while the same app stays on screen.
   */
  private fun trackForegroundApp(event: AccessibilityEvent) {
    if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

    var packageName = event.packageName?.toString() ?: return
    val blockedPackage = appRuleOverlay.requestedPackage()
    if (blockedPackage != null) {
      if (packageName == this.packageName) return
      // A keyboard or system window is not a switch away from the app under our overlay.
      packageName = foregroundAppPackage() ?: return
    }
    stats.clearKeyIfDifferent(EnforcementReason.APP_LIMIT, packageName)
    stats.clearKeyIfDifferent(EnforcementReason.ROLLING_LIMIT, packageName)
    stats.clearKeyIfDifferent(EnforcementReason.TIMED_VISIT, packageName)
    if (appRuleOverlay.requestedPackage() == null) {
      usageTracker.onForeground(packageName, SystemClock.elapsedRealtime())?.let(::bankUsage)
    }
    if (packageName != YOUTUBE_PACKAGE && packageName != this.packageName) youtube.reset()
    currentForegroundPackage = packageName
    if (enforceAppLimits(packageName)) return
    if (packageName != this.packageName) {
      handleIntentForeground(packageName)
      if (blockedPackage != null && appRuleOverlay.requestedPackage() == null) {
        usageTracker.onForeground(packageName, SystemClock.elapsedRealtime())?.let(::bankUsage)
      }
      scheduleAppRuleRefresh()
    }
  }

  /** Writes banked foreground time against today's total for that app. */
  private fun bankUsage(attribution: AppUsageTracker.Attribution) {
    if (!::appLimits.isInitialized || !::rollingLimits.isInitialized) return
    if (::appRuleSafety.isInitialized && appRuleSafety.isExempt(attribution.packageName)) return
    val nowWallMs = appRuleWallClock()
    if (appLimits.limits().containsKey(attribution.packageName)) {
      appLimits.addUsage(attribution.packageName, attribution.durationMs, nowWallMs)
    }
    if (rollingLimits.rules().containsKey(attribution.packageName)) {
      rollingLimits.addUsage(attribution.packageName, attribution.durationMs, nowWallMs)
    }
  }

  /** All active whole-app limits must finish before access returns. */
  private fun enforceAppLimits(packageName: String?): Boolean {
    if (!canRunEnforcementAction()) return false
    if (packageName == null || !::appRuleSafety.isInitialized || appRuleSafety.isExempt(packageName)) return false
    if (foregroundAppPackage()?.let { it != packageName } == true) return false
    val wallNow = appRuleWallClock()
    val dailyAt = appLimits.availableAgainAt(packageName, wallNow)
    val rollingAt = rollingLimits.availableAgainAt(packageName, wallNow)
    if (dailyAt == null) stats.reset(EnforcementReason.APP_LIMIT)
    if (rollingAt == null) stats.reset(EnforcementReason.ROLLING_LIMIT)
    if (dailyAt == null && rollingAt == null) return false

    endExpiredIntentSession(packageName)
    val budgetAt = maxOf(dailyAt ?: wallNow, rollingAt ?: wallNow)
    val visitRemainingMs = intentSessions.remainingMs(packageName, SystemClock.elapsedRealtime())
    val visitCooldownMs = (intentStore.intents()[packageName]?.cooldownMinutes ?: 0) * 60_000L
    val upcomingCooldownMs = if (visitRemainingMs > 0L && visitCooldownMs > 0L && budgetAt - wallNow >= visitRemainingMs) {
      visitRemainingMs + visitCooldownMs
    } else {
      0L
    }
    val cooldownMs = if (visitRemainingMs > 0L) upcomingCooldownMs else intentStore.cooldownRemainingMs(packageName, wallNow)
    val availableAt = maxOf(budgetAt, wallNow + cooldownMs)
    val appLabel = labelFor(packageName)
    val details = buildList {
      if (dailyAt != null) add(getString(R.string.zen_guard_app_limit_detail, appLabel))
      if (rollingAt != null) add(getString(R.string.zen_guard_rolling_limit_detail, appLabel))
      if (cooldownMs > 0L) add(getString(R.string.zen_guard_intent_cooldown_detail, appLabel))
    }
    prepareAppRuleOverlay(packageName, AppRuleOverlayMode.LIMIT)
    appRuleOverlay.showLimit(
      packageName = packageName,
      title = getString(if (dailyAt != null) R.string.zen_guard_app_limit_reached else R.string.zen_guard_rolling_limit_reached),
      detail = details.joinToString("\n\n"),
      remainingMs = availableAt - wallNow,
      onLeave = ::leaveAppRule,
      onAttached = {
        pauseAppRuleUsage()
        if (dailyAt != null) {
          recordAppLimitStats(packageName, EnforcementStatsOutcome.SUCCESS)
        } else {
          recordRollingLimitStats(packageName, EnforcementStatsOutcome.SUCCESS)
        }
      },
    )
    scheduleAppRuleRefresh()
    return true
  }

  /** Read only the top app window; never charge the blocker or trap another app under it. */
  private fun foregroundAppPackage(): String? =
    windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root?.packageName?.toString()
      ?: rootInActiveWindow?.packageName?.toString()?.takeIf { it != packageName }

  private fun pauseAppRuleUsage() {
    usageTracker.onForeground(null, SystemClock.elapsedRealtime())?.let(::bankUsage)
    instagram.pause()
  }

  private fun prepareAppRuleOverlay(packageName: String, mode: AppRuleOverlayMode) {
    if (appRuleOverlay.requestedPackage() == packageName && appRuleOverlay.mode == mode) return
    closeAppRuleOverlay()
  }

  private fun closeAppRuleOverlay() {
    if (::appRuleOverlay.isInitialized) appRuleOverlay.hide()
    appCardRefresh.cancel()
    stats.reset(EnforcementReason.APP_LIMIT)
    stats.reset(EnforcementReason.ROLLING_LIMIT)
    stats.reset(EnforcementReason.TIMED_VISIT)
  }

  /** Apply content rules to the verified app beneath the card before exposing it again. */
  private fun releaseAppRuleOverlay(packageName: String): Boolean {
    if (!canRunEnforcementAction()) return false
    // Keep the retry while off and reject queued Start callbacks even after card dismissal.
    if (!isScreenInteractive) return false
    if (appRuleOverlay.requestedPackage() == null) return true
    val root = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root
      ?.takeIf { it.packageName?.toString() == packageName } ?: return false
    when {
      BrowserUrlDetector.supports(packageName) -> browserIntervention?.observe(packageName, root)
      packageName == INSTAGRAM_PACKAGE -> instagram.observe(null, root)
      packageName == YOUTUBE_PACKAGE -> youtube.observe(null, root)
    }
    closeAppRuleOverlay()
    return true
  }

  private fun scheduleAppRuleRefresh() {
    if (appRuleOverlay.requestedPackage() == null) {
      appCardRefresh.cancel()
    } else {
      appCardRefresh.scheduleAt(SystemClock.elapsedRealtime() + APP_RULE_REFRESH_MS)
    }
  }

  private fun refreshAppRuleOverlay(): DeadlineDecision {
    if (!canRunEnforcementAction()) return DeadlineDecision.Cancel
    val blockedPackage = appRuleOverlay.requestedPackage() ?: return DeadlineDecision.Complete
    // Keep the boundary and paused accounting until the actual app can be verified.
    val foreground = foregroundAppPackage() ?: return DeadlineDecision.RetryAfter(APP_RULE_REFRESH_MS)
    if (foreground != blockedPackage) {
      currentForegroundPackage = foreground
      if (!enforceAppLimits(foreground)) handleIntentForeground(foreground)
      if (appRuleOverlay.requestedPackage() == null) {
        usageTracker.onForeground(foreground, SystemClock.elapsedRealtime())?.let(::bankUsage)
      }
    } else if (!enforceAppLimits(blockedPackage)) {
      handleIntentForeground(blockedPackage)
      if (appRuleOverlay.requestedPackage() == null) {
        usageTracker.onForeground(blockedPackage, SystemClock.elapsedRealtime())
      }
    }
    return if (appRuleOverlay.requestedPackage() == null) {
      DeadlineDecision.Complete
    } else {
      DeadlineDecision.RetryAfter(APP_RULE_REFRESH_MS)
    }
  }

  /**
   * Shows the Start or downtime card for an intent-gated app in place. An active
   * visit resumes quietly; an expired one begins the configured downtime.
   */
  private fun handleIntentForeground(packageName: String) {
    if (!::intentStore.isInitialized || !::appRuleOverlay.isInitialized) return
    if (!canRunEnforcementAction()) return
    if (!appRuleSafety.allowsTimedVisitEnforcement(packageName)) {
      closeAppRuleOverlay()
      return
    }
    val rule = intentStore.intents()[packageName]
    if (rule == null) {
      releaseAppRuleOverlay(packageName)
      return
    }

    val nowElapsedMs = SystemClock.elapsedRealtime()
    if (intentSessions.isActive(packageName, nowElapsedMs)) {
      releaseAppRuleOverlay(packageName)
      scheduleIntentDeadline(packageName)
      return
    }
    endExpiredIntentSession(packageName)
    val cooldownMs = intentStore.cooldownRemainingMs(packageName, appRuleWallClock())
    val mode = if (cooldownMs > 0L) AppRuleOverlayMode.COOLDOWN else AppRuleOverlayMode.ASK
    prepareAppRuleOverlay(packageName, mode)
    val onAttached = {
      pauseAppRuleUsage()
      recordTimedVisitStats(packageName, EnforcementStatsOutcome.SUCCESS)
      Unit
    }
    if (cooldownMs > 0L) {
      appRuleOverlay.showCooldown(
        packageName, labelFor(packageName), cooldownMs, ::leaveAppRule, onAttached,
      )
    } else {
      appRuleOverlay.showAsk(
        packageName,
        labelFor(packageName),
        rule.sessionMinutes,
        onStart = { grantIntentSession(packageName, rule.sessionMinutes) },
        onLeave = ::leaveAppRule,
        onAttached = onAttached,
      )
    }
    scheduleAppRuleRefresh()
  }

  private fun endExpiredIntentSession(packageName: String) {
    if (intentSessions.isActive(packageName, SystemClock.elapsedRealtime())) return
    if (intentSessions.end(packageName)) {
      intentStore.recordSessionEnd(packageName, appRuleWallClock())
      stats.reset(EnforcementReason.TIMED_VISIT)
    }
  }

  /** Starts a visit in the same app. Recheck other limits before accepting a queued tap. */
  private fun grantIntentSession(packageName: String, minutes: Int) {
    if (!canRunEnforcementAction()) return
    if (foregroundAppPackage() != packageName || enforceAppLimits(packageName)) return
    if (intentStore.cooldownRemainingMs(packageName, appRuleWallClock()) > 0L) return
    if (!releaseAppRuleOverlay(packageName)) return
    intentSessions.grant(packageName, minutes, SystemClock.elapsedRealtime())
    usageTracker.onForeground(packageName, SystemClock.elapsedRealtime())
    scheduleIntentDeadline(packageName)
  }

  private fun scheduleIntentDeadline(packageName: String) {
    val nowElapsedMs = SystemClock.elapsedRealtime()
    val remaining = intentSessions.remainingMs(packageName, nowElapsedMs)
    if (remaining > 0L) visitDeadline.scheduleAt(nowElapsedMs + remaining) else visitDeadline.cancel()
  }

  /** Leaves the current card without starting a visit or opening another app. */
  private fun leaveAppRule() {
    closeAppRuleOverlay()
    if (!canRunEnforcementAction()) return
    usageTracker.reset()
    currentForegroundPackage = null
    performGlobalAction(GLOBAL_ACTION_HOME)
  }

  /** An untouched timed visit still ends at its deadline and opens the downtime card. */
  private fun enforceIntentSession() {
    if (!::intentStore.isInitialized || !::appRuleOverlay.isInitialized) return
    if (!canRunEnforcementAction()) return
    val packageName = foregroundAppPackage() ?: return
    if (!appRuleSafety.allowsTimedVisitEnforcement(packageName)) return
    if (intentSessions.isActive(packageName, SystemClock.elapsedRealtime())) {
      scheduleIntentDeadline(packageName)
      return
    }
    if (!intentSessions.end(packageName)) return
    intentStore.recordSessionEnd(packageName, appRuleWallClock())
    stats.reset(EnforcementReason.TIMED_VISIT)
    if (!enforceAppLimits(packageName)) handleIntentForeground(packageName)
  }

  /** Human-readable app name, falling back to the package when unknown. */
  private fun labelFor(packageName: String): String = try {
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
  } catch (_: PackageManager.NameNotFoundException) {
    packageName
  }

  override fun onInterrupt() {
    closeAppRuleOverlay()
    browserIntervention?.end()
    x.pause()
    youtube.reset()
    stats.clear()
    instagram.pause()
  }

  override fun onDestroy() {
    visitDeadline.cancel()
    browserIntervention?.end()
    youtube.reset()
    x.destroy(hasActiveProtection())
    instagram.cancelNavigation()
    usageHandler.removeCallbacksAndMessages(null)
    closeAppRuleOverlay()
    instagram.destroy(hasActiveProtection())
    stats.clear()
    if (screenReceiverRegistered) {
      unregisterReceiver(screenStateReceiver)
      screenReceiverRegistered = false
    }
    super.onDestroy()
  }

  private val isScreenInteractive: Boolean
    get() = getSystemService(PowerManager::class.java)?.isInteractive == true

  private fun hasActiveProtection(): Boolean =
    ::preferences.isInitialized && preferences.hasCurrentConsent && preferences.protectionEnabled

  internal fun recordAppLimitStats(packageName: String, outcome: EnforcementStatsOutcome): Boolean =
    stats.record(EnforcementReason.APP_LIMIT, outcome, packageName)

  internal fun recordRollingLimitStats(packageName: String, outcome: EnforcementStatsOutcome): Boolean =
    stats.record(EnforcementReason.ROLLING_LIMIT, outcome, packageName)

  internal fun recordTimedVisitStats(packageName: String, outcome: EnforcementStatsOutcome): Boolean =
    stats.record(EnforcementReason.TIMED_VISIT, outcome, packageName)

  /** Clear every in-memory action path without inspecting another app's screen. */
  private fun clearInactiveProtection() {
    browserIntervention?.end()
    youtube.reset()
    stats.clear()
    x.disable()
    usageTracker.reset()
    currentForegroundPackage = null
    intentSessions.reset()
    visitDeadline.cancel()
    closeAppRuleOverlay()
    instagram.disable()
  }

  /**
   * Android Settings is the user's unconditional escape path from this service. Clear overlays,
   * delayed navigation, and tracked app-rule state without changing the in-app settings lock.
   */
  private fun enterSystemSettings() {
    browserIntervention?.end()
    youtube.reset()
    stats.clear()
    x.pause()
    usageTracker.reset()
    currentForegroundPackage = null
    closeAppRuleOverlay()
    instagram.enterSettings()
  }

  /** Re-check the live windows at execution time so queued callbacks cannot eject Settings. */
  private fun canRunEnforcementAction(): Boolean {
    if (!hasActiveProtection()) {
      clearInactiveProtection()
      return false
    }
    if (::appRuleSafety.isInitialized && appRuleSafety.hasSystemSettingsWindow(
        activePackage = rootInActiveWindow?.packageName?.toString(),
        windowPackages = windows.map { it.root?.packageName?.toString() },
      )
    ) {
      enterSystemSettings()
      return false
    }
    return true
  }

  companion object {
    private const val X_PACKAGE = "com.twitter.android"
    private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    private const val INSTAGRAM_PACKAGE = "com.instagram.android"
    private const val USAGE_TICK_MS = 15_000L
    private const val APP_RULE_REFRESH_MS = 1_000L
  }
}
