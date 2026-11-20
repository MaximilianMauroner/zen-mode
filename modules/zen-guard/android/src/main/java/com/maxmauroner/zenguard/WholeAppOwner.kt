package com.maxmauroner.zenguard

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
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
  private lateinit var earnedTime: EarnedTimeStore
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
  private val youtubePlaybackCheck by lazy {
    DeadlineTask(Handler(Looper.getMainLooper())) {
      if (!intentSessions.isWatchingOneVideo(YOUTUBE_PACKAGE, SystemClock.elapsedRealtime()) || observeYouTubeVisit()) {
        DeadlineDecision.Complete
      } else {
        DeadlineDecision.RetryAfter(YOUTUBE_PLAYBACK_CHECK_MS)
      }
    }
  }
  private val usageTracker = AppUsageTracker()
  private val usageHandler by lazy { Handler(Looper.getMainLooper()) }
  private var currentForegroundPackage: String? = null
  private var taskbarVisitAwaitingForeground: String? = null
  private var closingPackage: String? = null
  private var timedEndPackage: String? = null

  /**
   * Banks foreground time every [USAGE_TICK_MS] so a long uninterrupted session
   * still trips its budget. Window events alone are not enough: an app the user
   * simply sits in emits nothing.
   */
  private val usageTicker = object : Runnable {
    override fun run() {
      if (canEnforce()) {
        confirmTaskbarVisitForeground()
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

  fun createAppRuleStores() {
    intentStore = IntentAppStore(service)
    rollingLimits = RollingLimitStore(service)
    earnedTime = EarnedTimeStore(service)
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
  fun trackForegroundApp(event: AccessibilityEvent): Boolean {
    confirmTaskbarVisitForeground()
    if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return false

    var packageName = event.packageName?.toString() ?: return false
    if (closingPackage == packageName) return true
    closingPackage = null
    val blockedPackage = appRuleOverlay.requestedPackage()
    if (blockedPackage != null) {
      if (packageName == service.packageName) return false
      // A keyboard or system window is not a switch away from the app under our overlay.
      packageName = foregroundAppPackage() ?: return false
    }
    stats.clearKeyIfDifferent(EnforcementReason.APP_LIMIT, packageName)
    stats.clearKeyIfDifferent(EnforcementReason.ROLLING_LIMIT, packageName)
    stats.clearKeyIfDifferent(EnforcementReason.TIMED_VISIT, packageName)
    stats.clearKeyIfDifferent(EnforcementReason.EARNED_TIME, packageName)
    if (appRuleOverlay.requestedPackage() == null) {
      usageTracker.onForeground(packageName, SystemClock.elapsedRealtime())?.let(::bankUsage)
    }
    onForegroundChanged(packageName)
    currentForegroundPackage = packageName
    if (enforceAppLimits(packageName)) return true
    if (packageName != service.packageName) {
      handleIntentForeground(packageName)
      if (blockedPackage != null && appRuleOverlay.requestedPackage() == null) {
        usageTracker.onForeground(packageName, SystemClock.elapsedRealtime())?.let(::bankUsage)
      }
      scheduleAppRuleRefresh()
    }
    return false
  }

  fun isClosingPackage(packageName: String?): Boolean = packageName != null && closingPackage == packageName

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
    earnedTime.addUsage(attribution.packageName, attribution.durationMs)
  }

  private fun enforceAppLimits(packageName: String?): Boolean {
    if (!canEnforce()) return false
    if (packageName == null || !::appRuleSafety.isInitialized || appRuleSafety.isExempt(packageName)) return false
    if (foregroundAppPackage()?.let { it != packageName } == true) return false
    val wallNow = appRuleWallClock()
    val dailyAt = appLimits.availableAgainAt(packageName, wallNow)
    val rollingAt = rollingLimits.availableAgainAt(packageName, wallNow)
    runCatching {
      if (Diagnostics.isRecording(service)) Diagnostics.record(service, "app_rules", org.json.JSONObject()
        .put("dailyRule", appLimits.limits().containsKey(packageName)).put("dailyBlocked", dailyAt != null)
        .put("rollingRule", rollingLimits.rules().containsKey(packageName)).put("rollingBlocked", rollingAt != null)
        .put("timedVisitRule", intentStore.intents().containsKey(packageName))
        .put("earnedTimeRule", earnedTime.rules().containsKey(packageName)))
    }
    if (dailyAt == null) stats.reset(EnforcementReason.APP_LIMIT)
    if (rollingAt == null) stats.reset(EnforcementReason.ROLLING_LIMIT)
    if (dailyAt == null && rollingAt == null) return enforceEarnedTime(packageName)
    stats.reset(EnforcementReason.EARNED_TIME)

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
      if (wallNow + cooldownMs > budgetAt) add(service.getString(R.string.zen_guard_intent_cooldown_detail, appLabel))
    }
    if (dailyAt != null && showAlternativeEndScreen(packageName, appLabel, EnforcementReason.APP_LIMIT)) return true
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

  private fun showAlternativeEndScreen(packageName: String, appLabel: String, reason: EnforcementReason): Boolean {
    return when (ZenGuardPreferences(service).endScreen) {
      EndScreen.FOCUS -> false
      EndScreen.OFF -> {
        pauseAppRuleUsage()
        stats.record(reason, EnforcementStatsOutcome.SUCCESS, packageName)
        closingPackage = packageName
        leaveAppRule(preserveStats = true)
        true
      }
      EndScreen.KIDS -> {
        prepareAppRuleOverlay(packageName, AppRuleOverlayMode.KIDS_END)
        appRuleOverlay.showKids(packageName, appLabel, ::leaveAppRule) {
          pauseAppRuleUsage()
          stats.record(reason, EnforcementStatsOutcome.SUCCESS, packageName)
        }
        if (reason == EnforcementReason.TIMED_VISIT) timedEndPackage = packageName
        scheduleAppRuleRefresh()
        true
      }
    }
  }

  /**
   * Closes an app until its habit app has earned it time. A habit app that can no longer be
   * launched leaves no way to earn, so the rule is not enforced until that app returns.
   */
  private fun enforceEarnedTime(packageName: String): Boolean {
    val rule = earnedTime.rules()[packageName]
    val habitLaunch = rule?.let { service.packageManager.getLaunchIntentForPackage(it.habitPackage) }
    if (rule == null || habitLaunch == null || earnedTime.balanceMs(packageName) > 0L) {
      stats.reset(EnforcementReason.EARNED_TIME)
      return false
    }
    endExpiredIntentSession(packageName)
    val habitLabel = labelFor(rule.habitPackage)
    prepareAppRuleOverlay(packageName, AppRuleOverlayMode.EARN)
    appRuleOverlay.showEarn(
      packageName = packageName,
      title = service.getString(R.string.zen_guard_earn_title, habitLabel),
      detail = service.getString(R.string.zen_guard_earn_detail, rule.habitMinutes, habitLabel, rule.rewardMinutes, labelFor(packageName)),
      habitAction = service.getString(R.string.zen_guard_earn_open, habitLabel),
      onOpenHabit = { openHabit(rule.habitPackage) },
      onLeave = ::leaveAppRule,
      onAttached = {
        pauseAppRuleUsage()
        stats.record(EnforcementReason.EARNED_TIME, EnforcementStatsOutcome.SUCCESS, packageName)
      },
    )
    scheduleAppRuleRefresh()
    return true
  }

  private fun openHabit(habitPackage: String) {
    val launch = service.packageManager.getLaunchIntentForPackage(habitPackage)
    if (launch == null || !canEnforce()) return leaveAppRule()
    closeAppRuleOverlay()
    usageTracker.reset()
    currentForegroundPackage = null
    try {
      service.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
      leaveAppRule()
    }
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

  fun closeAppRuleOverlay(preserveStats: Boolean = false) {
    timedEndPackage = null
    if (::appRuleOverlay.isInitialized) appRuleOverlay.hide()
    appCardRefresh.cancel()
    if (!preserveStats) {
      stats.reset(EnforcementReason.APP_LIMIT)
      stats.reset(EnforcementReason.ROLLING_LIMIT)
      stats.reset(EnforcementReason.TIMED_VISIT)
      stats.reset(EnforcementReason.EARNED_TIME)
    }
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
    } else if (!appCardRefresh.isPending) {
      appCardRefresh.scheduleAt(SystemClock.elapsedRealtime() + APP_RULE_REFRESH_MS)
    }
  }

  private fun refreshAppRuleOverlay(): DeadlineDecision {
    if (!canEnforce()) return DeadlineDecision.Cancel
    val blockedPackage = appRuleOverlay.requestedPackage() ?: return DeadlineDecision.Complete
    appRuleOverlay.showEndTimes(appRuleWallClock())
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
    if (timedEndPackage == packageName && appRuleOverlay.requestedPackage() == packageName) return
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
    bankPendingUsage()
    val wallNow = appRuleWallClock()
    val cooldownMs = intentStore.cooldownRemainingMs(packageName, wallNow)
    val capLeftMs = appLimits.leftTodayMs(packageName, wallNow)
    val choices = visitChoices(rule.sessionMinutes, appLimits.minutesLeftToday(packageName, wallNow))
    if (cooldownMs <= 0L && choices.isEmpty()) {
      enforceAppLimits(packageName)
      return
    }
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
        choices,
        wallNow,
        capLeftMs,
        onStart = { minutes -> grantIntentSession(packageName, minutes) },
        onLeave = ::leaveAppRule,
        onAttached = onAttached,
        onWatchOneVideo = if (packageName == YOUTUBE_PACKAGE && YouTubeNotificationAccess.isGranted(service)) {
          { grantIntentSession(packageName, choices.last(), watchOneVideo = true) }
        } else null,
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

  private fun grantIntentSession(packageName: String, minutes: Int, watchOneVideo: Boolean = false) {
    if (!canEnforce() || !isScreenInteractive) return
    if (watchOneVideo && !YouTubeNotificationAccess.isGranted(service)) return
    if (appRuleOverlay.requestedPackage() != packageName || appRuleOverlay.mode != AppRuleOverlayMode.ASK) return
    val windows = service.windows
    val appWindow = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
    if (appWindow == null) {
      if (currentForegroundPackage != packageName || !taskbarOccludesApp(windows)) return
    } else if (appWindow.root?.packageName?.toString() != packageName) {
      return
    }
    if (enforceAppLimits(packageName)) return
    val wallNow = appRuleWallClock()
    if (intentStore.cooldownRemainingMs(packageName, wallNow) > 0L) return
    val rule = intentStore.intents()[packageName] ?: return
    val longest = visitChoices(rule.sessionMinutes, appLimits.minutesLeftToday(packageName, wallNow)).lastOrNull() ?: return
    if (appWindow == null) {
      if (!canEnforce() || !isScreenInteractive) return
      closeAppRuleOverlay()
    } else if (!releaseAppRuleOverlay(packageName)) {
      return
    }
    intentSessions.grant(packageName, minOf(minutes, longest), SystemClock.elapsedRealtime(), watchOneVideo)
    if (appWindow == null) {
      taskbarVisitAwaitingForeground = packageName
    } else {
      usageTracker.onForeground(packageName, SystemClock.elapsedRealtime())
    }
    scheduleIntentDeadline(packageName)
    if (watchOneVideo && !observeYouTubeVisit()) {
      youtubePlaybackCheck.scheduleAt(SystemClock.elapsedRealtime() + YOUTUBE_PLAYBACK_CHECK_MS)
    }
  }

  private fun taskbarOccludesApp(windows: List<AccessibilityWindowInfo>): Boolean {
    if (!appRuleOverlay.isShowing() || service.rootInActiveWindow?.packageName?.toString() != service.packageName) return false
    val chooser = windows.firstOrNull {
      it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.isActive &&
        it.root?.packageName?.toString() == service.packageName
    } ?: return false
    val screenBounds = Rect().also(chooser::getBoundsInScreen)
    if (screenBounds.isEmpty) return false
    // First-run Taskbar can hide the app from Accessibility until the user dismisses it.
    return windows.any {
      it.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
        it.root?.packageName?.toString() == PIXEL_LAUNCHER &&
        Rect().also(it::getBoundsInScreen) == screenBounds
    }
  }

  private fun confirmTaskbarVisitForeground() {
    val pending = taskbarVisitAwaitingForeground ?: return
    if (!isScreenInteractive) return
    val foreground = foregroundAppPackage() ?: return
    taskbarVisitAwaitingForeground = null
    if (foreground != pending) return
    val now = SystemClock.elapsedRealtime()
    if (!intentSessions.isActive(pending, now)) return
    currentForegroundPackage = pending
    usageTracker.onForeground(pending, now)?.let(::bankUsage)
  }

  fun observeYouTubeVisit(): Boolean {
    if (!intentSessions.isWatchingOneVideo(YOUTUBE_PACKAGE, SystemClock.elapsedRealtime())) return false
    if (!canEnforce() || foregroundAppPackage() != YOUTUBE_PACKAGE) return false
    val root = service.rootInActiveWindow ?: return false
    if (!YouTubeSurfaceDetector.showsRegularPlayer(root)) return false
    val video = YouTubeNotificationAccess.playingVideo(service) ?: return false
    if (!intentSessions.startsSecondVideo(YOUTUBE_PACKAGE, video)) return false
    finishIntentSession(YOUTUBE_PACKAGE)
    return true
  }

  private fun scheduleIntentDeadline(packageName: String) {
    val nowElapsedMs = SystemClock.elapsedRealtime()
    val remaining = intentSessions.remainingMs(packageName, nowElapsedMs)
    if (remaining > 0L) visitDeadline.scheduleAt(nowElapsedMs + remaining) else visitDeadline.cancel()
  }

  private fun leaveAppRule() = leaveAppRule(preserveStats = false)

  private fun leaveAppRule(preserveStats: Boolean) {
    closeAppRuleOverlay(preserveStats)
    if (!canEnforce()) return
    usageTracker.reset()
    taskbarVisitAwaitingForeground = null
    if (!preserveStats) currentForegroundPackage = null
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
    finishIntentSession(packageName)
  }

  private fun finishIntentSession(packageName: String) {
    if (!intentSessions.end(packageName)) return
    if (packageName == YOUTUBE_PACKAGE) youtubePlaybackCheck.cancel()
    visitDeadline.cancel()
    intentStore.recordSessionEnd(packageName, appRuleWallClock())
    stats.reset(EnforcementReason.TIMED_VISIT)
    bankPendingUsage()
    if (!enforceAppLimits(packageName) &&
      !showAlternativeEndScreen(packageName, labelFor(packageName), EnforcementReason.TIMED_VISIT)) {
      handleIntentForeground(packageName)
    }
  }

  /** A daily cap is judged on banked time, and the tick banks only every [USAGE_TICK_MS]. */
  private fun bankPendingUsage() {
    usageTracker.tick(SystemClock.elapsedRealtime())?.let(::bankUsage)
  }

  private fun labelFor(packageName: String): String = try {
    service.packageManager.getApplicationLabel(service.packageManager.getApplicationInfo(packageName, 0)).toString()
  } catch (_: PackageManager.NameNotFoundException) {
    packageName
  }

  fun enterSettings() {
    usageTracker.reset()
    taskbarVisitAwaitingForeground = null
    currentForegroundPackage = null
    closingPackage = null
    closeAppRuleOverlay()
  }

  fun disable() {
    usageTracker.reset()
    taskbarVisitAwaitingForeground = null
    currentForegroundPackage = null
    closingPackage = null
    intentSessions.reset()
    youtubePlaybackCheck.cancel()
    visitDeadline.cancel()
    closeAppRuleOverlay()
  }

  fun cancelVisitDeadline() {
    visitDeadline.cancel()
    youtubePlaybackCheck.cancel()
  }

  fun stopUsageAndCloseCard() {
    usageHandler.removeCallbacksAndMessages(null)
    closeAppRuleOverlay()
  }

  private val isScreenInteractive: Boolean
    get() = service.getSystemService(PowerManager::class.java)?.isInteractive == true

  private companion object {
    const val PIXEL_LAUNCHER = "com.google.android.apps.nexuslauncher"
    const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    const val YOUTUBE_PLAYBACK_CHECK_MS = 1_000L
    const val USAGE_TICK_MS = 15_000L
    const val APP_RULE_REFRESH_MS = 1_000L
  }
}
