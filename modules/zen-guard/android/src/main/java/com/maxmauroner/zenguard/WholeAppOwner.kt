package com.maxmauroner.zenguard

import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

internal class WholeAppOwner(
  private val service: AccessibilityService,
  private val stats: InterventionStats,
  private val canEnforce: () -> Boolean,
  private val onForegroundChanged: (String) -> Unit,
  private val onCardAttached: () -> Unit,
  private val onRelease: (String, AccessibilityNodeInfo) -> Unit,
) {
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
  private val usageTracker = AppUsageTracker()
  private val usageHandler by lazy { Handler(Looper.getMainLooper()) }
  private var currentForegroundPackage: String? = null

  /**
   * Banks foreground time every [USAGE_TICK_MS] so a long uninterrupted session
   * still trips its budget. Window events alone are not enough: an app the user
   * simply sits in emits nothing.
   */
  private val usageTicker = object : Runnable {
    override fun run() {
      if (canEnforce()) {
        usageTracker.tick(SystemClock.elapsedRealtime())?.let(::bankUsage)
        enforceAppLimits(currentForegroundPackage)
        enforceIntentSession()
      }
      usageHandler.postDelayed(this, USAGE_TICK_MS)
    }
  }

  fun createAppLimitStore() {
    appLimits = AppLimitStore(service)
  }

  fun createIntentAndRollingStores() {
    intentStore = IntentAppStore(service)
    rollingLimits = RollingLimitStore(service)
  }

  fun onServiceConnected(safety: AppRuleSafety) {
    appRuleSafety = safety
    appRuleOverlay = AppRuleOverlay(service)
  }

  fun start() {
    usageHandler.postDelayed(usageTicker, USAGE_TICK_MS)
  }

  fun bankSettingsForeground(packageName: String) {
    usageTracker.onForeground(packageName, SystemClock.elapsedRealtime())?.let(::bankUsage)
  }

  val hasRequestedCard: Boolean
    get() = appRuleOverlay.requestedPackage() != null

  /**
   * Follows which app is in front and charges its time against a daily budget.
   * Only window changes count as a switch; keyboards and system bars raise other
   * event types while the same app stays on screen.
   */
  fun trackForegroundApp(event: AccessibilityEvent) {
    if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

    var packageName = event.packageName?.toString() ?: return
    val blockedPackage = appRuleOverlay.requestedPackage()
    if (blockedPackage != null) {
      if (packageName == service.packageName) return
      // A keyboard or system window is not a switch away from the app under our overlay.
      packageName = foregroundAppPackage() ?: return
    }
    stats.clearKeyIfDifferent(EnforcementReason.APP_LIMIT, packageName)
    stats.clearKeyIfDifferent(EnforcementReason.ROLLING_LIMIT, packageName)
    stats.clearKeyIfDifferent(EnforcementReason.TIMED_VISIT, packageName)
    if (appRuleOverlay.requestedPackage() == null) {
      usageTracker.onForeground(packageName, SystemClock.elapsedRealtime())?.let(::bankUsage)
    }
    onForegroundChanged(packageName)
    currentForegroundPackage = packageName
    if (enforceAppLimits(packageName)) return
    if (packageName != service.packageName) {
      handleIntentForeground(packageName)
      if (blockedPackage != null && appRuleOverlay.requestedPackage() == null) {
        usageTracker.onForeground(packageName, SystemClock.elapsedRealtime())?.let(::bankUsage)
      }
      scheduleAppRuleRefresh()
    }
  }

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

  private fun enforceAppLimits(packageName: String?): Boolean {
    if (!canEnforce()) return false
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
      if (dailyAt != null) add(service.getString(R.string.zen_guard_app_limit_detail, appLabel))
      if (rollingAt != null) add(service.getString(R.string.zen_guard_rolling_limit_detail, appLabel))
      if (cooldownMs > 0L) add(service.getString(R.string.zen_guard_intent_cooldown_detail, appLabel))
    }
    prepareAppRuleOverlay(packageName, AppRuleOverlayMode.LIMIT)
    appRuleOverlay.showLimit(
      packageName = packageName,
      title = service.getString(if (dailyAt != null) R.string.zen_guard_app_limit_reached else R.string.zen_guard_rolling_limit_reached),
      detail = details.joinToString("\n\n"),
      remainingMs = availableAt - wallNow,
      onLeave = ::leaveAppRule,
      onAttached = {
        pauseAppRuleUsage()
        if (dailyAt != null) {
          stats.record(EnforcementReason.APP_LIMIT, EnforcementStatsOutcome.SUCCESS, packageName)
        } else {
          stats.record(EnforcementReason.ROLLING_LIMIT, EnforcementStatsOutcome.SUCCESS, packageName)
        }
      },
    )
    scheduleAppRuleRefresh()
    return true
  }

  private fun foregroundAppPackage(): String? =
    service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root?.packageName?.toString()
      ?: service.rootInActiveWindow?.packageName?.toString()?.takeIf { it != service.packageName }

  private fun pauseAppRuleUsage() {
    usageTracker.onForeground(null, SystemClock.elapsedRealtime())?.let(::bankUsage)
    onCardAttached()
  }

  private fun prepareAppRuleOverlay(packageName: String, mode: AppRuleOverlayMode) {
    if (appRuleOverlay.requestedPackage() == packageName && appRuleOverlay.mode == mode) return
    closeAppRuleOverlay()
  }

  fun closeAppRuleOverlay() {
    if (::appRuleOverlay.isInitialized) appRuleOverlay.hide()
    appCardRefresh.cancel()
    stats.reset(EnforcementReason.APP_LIMIT)
    stats.reset(EnforcementReason.ROLLING_LIMIT)
    stats.reset(EnforcementReason.TIMED_VISIT)
  }

  private fun releaseAppRuleOverlay(packageName: String): Boolean {
    if (!canEnforce()) return false
    if (!isScreenInteractive) return false
    if (appRuleOverlay.requestedPackage() == null) return true
    val root = service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root
      ?.takeIf { it.packageName?.toString() == packageName } ?: return false
    onRelease(packageName, root)
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
    if (!canEnforce()) return DeadlineDecision.Cancel
    val blockedPackage = appRuleOverlay.requestedPackage() ?: return DeadlineDecision.Complete
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

  private fun handleIntentForeground(packageName: String) {
    if (!::intentStore.isInitialized || !::appRuleOverlay.isInitialized) return
    if (!canEnforce()) return
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
      stats.record(EnforcementReason.TIMED_VISIT, EnforcementStatsOutcome.SUCCESS, packageName)
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

  private fun grantIntentSession(packageName: String, minutes: Int) {
    if (!canEnforce()) return
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

  private fun leaveAppRule() {
    closeAppRuleOverlay()
    if (!canEnforce()) return
    usageTracker.reset()
    currentForegroundPackage = null
    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
  }

  private fun enforceIntentSession() {
    if (!::intentStore.isInitialized || !::appRuleOverlay.isInitialized) return
    if (!canEnforce()) return
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

  private fun labelFor(packageName: String): String = try {
    service.packageManager.getApplicationLabel(service.packageManager.getApplicationInfo(packageName, 0)).toString()
  } catch (_: PackageManager.NameNotFoundException) {
    packageName
  }

  fun enterSettings() {
    usageTracker.reset()
    currentForegroundPackage = null
    closeAppRuleOverlay()
  }

  fun disable() {
    usageTracker.reset()
    currentForegroundPackage = null
    intentSessions.reset()
    visitDeadline.cancel()
    closeAppRuleOverlay()
  }

  fun cancelVisitDeadline() {
    visitDeadline.cancel()
  }

  fun stopUsageAndCloseCard() {
    usageHandler.removeCallbacksAndMessages(null)
    closeAppRuleOverlay()
  }

  private val isScreenInteractive: Boolean
    get() = service.getSystemService(PowerManager::class.java)?.isInteractive == true

  private companion object {
    const val USAGE_TICK_MS = 15_000L
    const val APP_RULE_REFRESH_MS = 1_000L
  }
}
