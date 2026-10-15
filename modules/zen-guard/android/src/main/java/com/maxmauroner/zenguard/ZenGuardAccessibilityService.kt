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
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import java.util.ArrayDeque

class ZenGuardAccessibilityService() : AccessibilityService() {
  /** JVM tests can supply the same local ledger without starting Android service callbacks. */
  internal constructor(statsStore: EnforcementStatsStore) : this() {
    enforcementStats = statsStore
  }

  private lateinit var preferences: ZenGuardPreferences
  private var enforcementStats: EnforcementStatsStore? = null
  private var statsActionSequence = 0L
  // Dedupe keys are in-memory lifecycle markers only. They are never persisted.
  private val statsDedupeKeys = mutableMapOf<EnforcementReason, String>()
  private val stateMachine = EnforcementStateMachine()
  private val youtubeShortsPager = YouTubeShortsPager()
  private val youtubeShortsExitResult = YouTubeShortsExitResult()
  private lateinit var youtubeShortsResultOverlay: YouTubeShortsResultOverlay
  private val xStateMachine = XGuardStateMachine()
  private var xHomeUsageState = HomeFeedUsageState.PAUSED
  private var xStorageAvailable = true
  private var instagramStorageAvailable = true
  private lateinit var xOverlay: XBreakOverlay
  private val xHandler by lazy { Handler(Looper.getMainLooper()) }
  private val xTicker = object : Runnable {
    override fun run() {
      if (hasActiveProtection()) {
        handleXEvent(null)
      } else if (::preferences.isInitialized) {
        clearInactiveProtection()
      }
      xHandler.postDelayed(this, 1_000L)
    }
  }
  private val instagramStateMachine = InstagramGuardStateMachine()
  private val instagramDebugTrace = if (BuildConfig.DEBUG) InstagramDebugTrace() else null
  private val navigationHandler by lazy { Handler(Looper.getMainLooper()) }
  private val instagramDeadlineHandler by lazy { Handler(Looper.getMainLooper()) }
  private val instagramReelsDeadlineCheck = Runnable { checkInstagramReelsDeadline() }
  private lateinit var instagramOverlay: InstagramBlockerOverlay
  private var instagramBlockReason: InstagramBlockReason? = null
  private var instagramNavigationSuppressedUntilMs = 0L
  /** Wall-clock injection keeps daily and rolling service tests independent of host time. */
  internal var appRuleWallClock: () -> Long = System::currentTimeMillis
  private lateinit var appLimits: AppLimitStore
  private lateinit var dailyTally: DailyTally
  private lateinit var intentStore: IntentAppStore
  private lateinit var rollingLimits: RollingLimitStore
  private lateinit var appRuleSafety: AppRuleSafety
  private val intentSessions = IntentSessionTracker()
  private lateinit var appRuleOverlay: AppRuleOverlay
  private val appRuleHandler by lazy { Handler(Looper.getMainLooper()) }
  private val appRuleRefresh = Runnable { refreshAppRuleOverlay() }
  private val intentDeadlineCheck = Runnable { enforceIntentSession() }
  private lateinit var adultSiteStore: AdultSiteRuleStore
  private var browserIntervention: BrowserIntervention? = null
  private lateinit var homeFeedStatusStore: HomeFeedStatusStore
  private val usageTracker = AppUsageTracker()
  private val usageHandler by lazy { Handler(Looper.getMainLooper()) }
  private var currentForegroundPackage: String? = null
  private var screenReceiverRegistered = false
  private val screenStateReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action != Intent.ACTION_SCREEN_OFF || !hasActiveProtection()) return
      val nowMs = SystemClock.elapsedRealtime()
      xStateMachine.pause(nowMs, xSettings())
      if (xHomeUsageState != HomeFeedUsageState.UNKNOWN) xHomeUsageState = HomeFeedUsageState.PAUSED
      instagramDeadlineHandler.removeCallbacks(instagramReelsDeadlineCheck)
      instagramStateMachine.onAppBackground(nowMs, preferences.instagramSettings())
      publishXHomeStatus()
      publishInstagramHomeStatus()
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
    enforcementStats = try {
      EnforcementStatsStore(this)
    } catch (_: RuntimeException) {
      null
    }
    appLimits = AppLimitStore(this)
    dailyTally = DailyTally(this)
    intentStore = IntentAppStore(this)
    rollingLimits = RollingLimitStore(this)
    appRuleSafety = AppRuleSafety.resolve(this)
    appRuleOverlay = AppRuleOverlay(this)
    adultSiteStore = AdultSiteRuleStore(this)
    browserIntervention?.end()
    browserIntervention = BrowserIntervention(
      service = this,
      canEnforce = ::canRunEnforcementAction,
      isEnabled = { adultSiteStore.enabled },
      recordAttachment = { recordEnforcement(EnforcementReason.BLOCKED_SITE) },
      recheck = { handleBrowserEvent(it) },
    )
    youtubeShortsResultOverlay = YouTubeShortsResultOverlay(this)
    homeFeedStatusStore = HomeFeedStatusStore(this)
    val nowElapsedMs = SystemClock.elapsedRealtime()
    val storedInstagram = homeFeedStatusStore.instagram(nowElapsedMs)
    instagramStorageAvailable = storedInstagram.storageState == HomeFeedStorageState.AVAILABLE
    instagramStateMachine.restore(
      HomeFeedRuntimeState(
        usedMs = storedInstagram.usedMs,
        blockedUntilElapsedMs = storedInstagram.blockedUntilElapsedMs,
        usageState = storedInstagram.usageState,
        lockoutState = storedInstagram.lockoutState,
        storageState = storedInstagram.storageState,
        capturedAtElapsedMs = nowElapsedMs,
      ),
    )
    val storedX = homeFeedStatusStore.x(nowElapsedMs)
    xStorageAvailable = storedX.storageState == HomeFeedStorageState.AVAILABLE
    xHomeUsageState = if (xStorageAvailable) storedX.usageState else HomeFeedUsageState.UNKNOWN
    xStateMachine.restore(
      HomeFeedRuntimeState(
        usedMs = storedX.usedMs,
        blockedUntilElapsedMs = storedX.blockedUntilElapsedMs,
        usageState = storedX.usageState,
        lockoutState = storedX.lockoutState,
        storageState = storedX.storageState,
        capturedAtElapsedMs = nowElapsedMs,
      ),
    )
    if (xStorageAvailable) publishXHomeStatus()
    publishInstagramHomeStatus()
    val screenFilter = IntentFilter(Intent.ACTION_SCREEN_OFF)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      registerReceiver(screenStateReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED)
    } else {
      @Suppress("DEPRECATION")
      registerReceiver(screenStateReceiver, screenFilter)
    }
    screenReceiverRegistered = true
    usageHandler.postDelayed(usageTicker, USAGE_TICK_MS)
    xOverlay = XBreakOverlay(this) {
      if (canRunEnforcementAction()) {
        xStateMachine.leaveBlockedSurface()
        xOverlay.hide()
        resetStatsDedupe(EnforcementReason.X_HOME)
        performGlobalAction(GLOBAL_ACTION_HOME)
      }
    }
    xHandler.postDelayed(xTicker, 1_000L)
    instagramOverlay = InstagramBlockerOverlay(
      service = this,
      onLeave = {
        if (canRunEnforcementAction()) {
          val reason = instagramBlockReason
          resetInstagramStatsDedupe(reason)
          instagramStateMachine.leaveBlockedSurface()
          instagramBlockReason = null
          instagramOverlay.hide()
          when (reason) {
            InstagramBlockReason.HOME_LIMIT -> performGlobalAction(GLOBAL_ACTION_HOME)
            InstagramBlockReason.EXPLORE -> openInstagramMessages()
            InstagramBlockReason.REELS_ENTRY,
            InstagramBlockReason.REELS_SWIPE,
            InstagramBlockReason.REELS_WINDOW_EXPIRED,
            -> openInstagramMessages()
            else -> performGlobalAction(GLOBAL_ACTION_BACK)
          }
        }
      },
      onOpenMessages = {
        if (canRunEnforcementAction()) {
          resetInstagramStatsDedupe(instagramBlockReason)
          instagramStateMachine.leaveBlockedSurface()
          instagramBlockReason = null
          instagramOverlay.hide()
          openInstagramMessages()
        }
      },
      onContinue = {
        if (canRunEnforcementAction()) {
          val continued = instagramStateMachine.continueReels(SystemClock.elapsedRealtime(), preferences.instagramSettings())
          if (continued) {
            resetInstagramStatsDedupe(instagramBlockReason)
            instagramBlockReason = null
            dailyTally.recordContinue(System.currentTimeMillis())
            scheduleInstagramReelsDeadline()
          }
          continued
        } else false
      },
      readStats = ::instagramBlockerStats,
    )
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
          resetYouTubeEnforcement()
        }
        if (windows.none { it.root?.packageName?.toString() == X_PACKAGE }) {
          clearXEnforcement(preserveHomeLockout = true)
        }
        if (windows.none { it.root?.packageName?.toString() == INSTAGRAM_PACKAGE }) {
          clearInstagramEnforcement(preserveHomeSession = true)
        }
      }
      if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
        windows.none { BrowserUrlDetector.supports(it.root?.packageName?.toString()) }
      ) browserIntervention?.end()
      return
    }
    if (BrowserUrlDetector.supports(eventPackage)) {
      handleBrowserEvent(eventPackage!!)
    } else if ((event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
        event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) &&
      windows.none { BrowserUrlDetector.supports(it.root?.packageName?.toString()) }
    ) {
      browserIntervention?.end()
    }
    if (eventPackage != X_PACKAGE && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
      windows.none { it.root?.packageName?.toString() == X_PACKAGE }) clearXEnforcement(preserveHomeLockout = true)

    when (eventPackage) {
      YOUTUBE_PACKAGE -> {
        instagramNavigationSuppressedUntilMs = 0L
        clearInstagramEnforcement(preserveHomeSession = true)
        handleYouTubeEvent(event)
      }
      INSTAGRAM_PACKAGE -> handleInstagramEvent(event)
      X_PACKAGE -> {
        clearInstagramEnforcement(preserveHomeSession = true)
        handleXEvent(event)
      }
      else -> handleNonInstagramEvent(event)
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
    if (statsDedupeKeys[EnforcementReason.APP_LIMIT] != packageName) resetStatsDedupe(EnforcementReason.APP_LIMIT)
    if (statsDedupeKeys[EnforcementReason.ROLLING_LIMIT] != packageName) resetStatsDedupe(EnforcementReason.ROLLING_LIMIT)
    if (statsDedupeKeys[EnforcementReason.TIMED_VISIT] != packageName) resetStatsDedupe(EnforcementReason.TIMED_VISIT)
    if (appRuleOverlay.requestedPackage() == null) {
      usageTracker.onForeground(packageName, SystemClock.elapsedRealtime())?.let(::bankUsage)
    }
    if (packageName != YOUTUBE_PACKAGE && packageName != this.packageName) resetYouTubeEnforcement()
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
    if (dailyAt == null) resetStatsDedupe(EnforcementReason.APP_LIMIT)
    if (rollingAt == null) resetStatsDedupe(EnforcementReason.ROLLING_LIMIT)
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
    clearInstagramEnforcement(preserveHomeSession = true)
  }

  private fun prepareAppRuleOverlay(packageName: String, mode: AppRuleOverlayMode) {
    if (appRuleOverlay.requestedPackage() == packageName && appRuleOverlay.mode == mode) return
    closeAppRuleOverlay()
  }

  private fun closeAppRuleOverlay() {
    if (::appRuleOverlay.isInitialized) appRuleOverlay.hide()
    appRuleHandler.removeCallbacks(appRuleRefresh)
    resetStatsDedupe(EnforcementReason.APP_LIMIT)
    resetStatsDedupe(EnforcementReason.ROLLING_LIMIT)
    resetStatsDedupe(EnforcementReason.TIMED_VISIT)
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
      BrowserUrlDetector.supports(packageName) -> handleBrowserEvent(packageName, root)
      packageName == INSTAGRAM_PACKAGE -> handleInstagramEvent(null, root)
      packageName == YOUTUBE_PACKAGE -> handleYouTubeEvent(null, root)
    }
    closeAppRuleOverlay()
    return true
  }

  private fun scheduleAppRuleRefresh() {
    appRuleHandler.removeCallbacks(appRuleRefresh)
    if (appRuleOverlay.requestedPackage() != null) appRuleHandler.postDelayed(appRuleRefresh, 1_000L)
  }

  private fun refreshAppRuleOverlay() {
    if (!canRunEnforcementAction()) return
    val blockedPackage = appRuleOverlay.requestedPackage() ?: return
    val foreground = foregroundAppPackage()
    if (foreground == null) {
      // Keep the boundary and paused accounting until the actual app can be verified.
      scheduleAppRuleRefresh()
      return
    }
    if (foreground != blockedPackage) {
      currentForegroundPackage = foreground
      if (!enforceAppLimits(foreground)) handleIntentForeground(foreground)
      if (appRuleOverlay.requestedPackage() == null) {
        usageTracker.onForeground(foreground, SystemClock.elapsedRealtime())?.let(::bankUsage)
      }
      scheduleAppRuleRefresh()
      return
    }
    if (enforceAppLimits(blockedPackage)) return
    handleIntentForeground(blockedPackage)
    if (appRuleOverlay.requestedPackage() == null && foreground == blockedPackage) {
      usageTracker.onForeground(blockedPackage, SystemClock.elapsedRealtime())
    }
    scheduleAppRuleRefresh()
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
      resetStatsDedupe(EnforcementReason.TIMED_VISIT)
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
    appRuleHandler.removeCallbacks(intentDeadlineCheck)
    val remaining = intentSessions.remainingMs(packageName, SystemClock.elapsedRealtime())
    if (remaining > 0L) appRuleHandler.postDelayed(intentDeadlineCheck, remaining)
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
      // Uptime-based dispatch can round ahead of the elapsed-time grant deadline.
      scheduleIntentDeadline(packageName)
      return
    }
    if (!intentSessions.end(packageName)) return
    intentStore.recordSessionEnd(packageName, appRuleWallClock())
    resetStatsDedupe(EnforcementReason.TIMED_VISIT)
    if (!enforceAppLimits(packageName)) handleIntentForeground(packageName)
  }

  /** Human-readable app name, falling back to the package when unknown. */
  private fun labelFor(packageName: String): String = try {
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
  } catch (_: PackageManager.NameNotFoundException) {
    packageName
  }

  /** A card handoff has a verified root but no scroll event or inferred pager transition. */
  private fun handleYouTubeEvent(event: AccessibilityEvent?, observedRoot: AccessibilityNodeInfo? = rootInActiveWindow) {
    val root = observedRoot ?: return
    if (root.packageName?.toString() != YOUTUBE_PACKAGE) return
    val nowMs = System.currentTimeMillis()
    preferences.recordEvent(nowMs)
    val nodes = snapshot(root)
    val result = ShortsDetector.detect(nodes)
    if (!result.isShortsViewer) {
      val surface = YouTubeSurfaceDetector.detect(nodes)
      val homeTab = root.findAccessibilityNodeInfosByViewId("$YOUTUBE_PACKAGE:id/pivot_bar")
        .firstOrNull()?.getChild(0)?.getChild(0)
      val homeSelected = homeTab?.isSelected == true || homeTab?.getChild(0)?.isSelected == true
      youtubeShortsExitResult.confirmed(surface, homeSelected, SystemClock.elapsedRealtime())?.let { exit ->
        recordYouTubeShortsStats(exit.pageIndex, EnforcementStatsOutcome.SUCCESS)
        youtubeShortsResultOverlay.show()
      }
      stateMachine.reset()
      youtubeShortsPager.reset()
      resetStatsDedupe(EnforcementReason.YOUTUBE_SHORTS)
      return
    }
    preferences.recordDetection(nowMs, result.reason)
    if (preferences.observationMode || !preferences.shortsEnabled) {
      resetYouTubeEnforcement()
      resetStatsDedupe(EnforcementReason.YOUTUBE_SHORTS)
      return
    }
    // The pager's collection row stays constant during playback and changes with the video.
    // Titles, like counts, comments, and playback progress must never consume the allowance.
    val page = root.findAccessibilityNodeInfosByViewId("$YOUTUBE_PACKAGE:id/reel_player_page_container")
      .firstOrNull { it.isVisibleToUser }
    val pagerTransitionIndex = youtubeShortsPager.stablePageIndex(
      isViewScrolled = event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED,
      sourceViewId = event?.source?.viewIdResourceName,
      fromIndex = event?.fromIndex ?: -1,
      toIndex = event?.toIndex ?: -1,
      scrollY = event?.scrollY ?: -1,
    )
    val pageIndex = page?.collectionItemInfo?.rowIndex ?: pagerTransitionIndex
    if (stateMachine.next(
        isShorts = true,
        pageIndex = pageIndex,
        nowMs = SystemClock.elapsedRealtime(),
        pagerTransitionIndex = pagerTransitionIndex,
      ) == EnforcementAction.LEAVE_SHORTS
    ) {
      // Back/Home can put Premium Shorts into PiP. Use YouTube's own Home tab instead.
      val tabs = root.findAccessibilityNodeInfosByViewId("$YOUTUBE_PACKAGE:id/pivot_bar").firstOrNull()
      val homeTab = tabs?.getChild(0)?.getChild(0)
      if (homeTab?.isClickable == true && homeTab.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
        youtubeShortsExitResult.requested(stateMachine.pendingExitPageIndex(), SystemClock.elapsedRealtime())
      }
    }
  }

  private fun clearXEnforcement(preserveHomeLockout: Boolean = false) {
    if (preserveHomeLockout && xStorageAvailable) {
      xStateMachine.pause(SystemClock.elapsedRealtime(), xSettings())
      xHomeUsageState = if (xStateMachine.requiresFreshHomeObservation()) {
        HomeFeedUsageState.UNKNOWN
      } else {
        HomeFeedUsageState.PAUSED
      }
    } else {
      xStateMachine.reset()
      xHomeUsageState = if (xStorageAvailable) HomeFeedUsageState.PAUSED else HomeFeedUsageState.UNKNOWN
    }
    resetStatsDedupe(EnforcementReason.X_HOME)
    resetStatsDedupe(EnforcementReason.X_VIDEOS)
    if (::xOverlay.isInitialized) xOverlay.hide()
    publishXHomeStatus()
  }

  /** One-second ticks count a still Home feed; events identify only the full-screen video pager. */
  private fun handleXEvent(event: AccessibilityEvent?) {
    if (!canRunEnforcementAction()) return
    val nowMs = SystemClock.elapsedRealtime()
    var settings = xSettings()
    if (!isScreenInteractive) {
      xStateMachine.pause(nowMs, settings)
      xHomeUsageState = if (xStateMachine.requiresFreshHomeObservation()) {
        HomeFeedUsageState.UNKNOWN
      } else {
        HomeFeedUsageState.PAUSED
      }
      publishXHomeStatus()
      return
    }
    val root = rootInActiveWindow ?: run {
      xStateMachine.markUnverifiableGap(nowMs, settings)
      xHomeUsageState = HomeFeedUsageState.UNKNOWN
      publishXHomeStatus()
      return
    }
    if (root.packageName?.toString() != X_PACKAGE) {
      if (xOverlay.isShowing && windows.any { it.root?.packageName?.toString() == X_PACKAGE }) return
      xStateMachine.pause(nowMs, settings)
      xHomeUsageState = if (xStateMachine.requiresFreshHomeObservation()) {
        HomeFeedUsageState.UNKNOWN
      } else {
        HomeFeedUsageState.PAUSED
      }
      publishXHomeStatus()
      return
    }
    val surface = XDetector.detect(snapshot(root))
    val pager = if (surface == XSurface.VIDEO) findXNode(root) { isXVideoPager(it) } else null
    if (surface == XSurface.HOME) preferences.recordXSignal(ZenGuardPreferences.X_HOME_SIGNAL)
    if (pager != null) preferences.recordXSignal(ZenGuardPreferences.X_VIDEO_SIGNAL)
    if (preferences.xObservationMode) { clearXEnforcement(); return }
    settings = xSettings()
    if (!xStorageAvailable && surface == XSurface.HOME && settings.homeEnabled && event != null) {
      if (homeFeedStatusStore.recoverX(nowMs)) {
        xStorageAvailable = true
        xStateMachine.recoverStorage(nowMs)
      }
    }
    val source = event?.source
    val advanced = pager != null && event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
      source != null && XVideoAdvanceDetector.isAdvance(
        XVideoScrollSignal(
          sourceIsPager = isXVideoPager(source),
          sourceOwnedByPager = isXVideoPagerOrDescendant(source),
          scrollY = event.scrollY,
          fromIndex = event.fromIndex,
          toIndex = event.toIndex,
        ),
      )
    val action = xStateMachine.next(surface, nowMs, settings, advanced)
    xHomeUsageState = when {
      action == XAction.HOME_UNAVAILABLE || surface == XSurface.UNKNOWN -> HomeFeedUsageState.UNKNOWN
      surface == XSurface.HOME && settings.homeEnabled && !xStateMachine.requiresFreshHomeObservation() -> HomeFeedUsageState.ACTIVE
      xStateMachine.requiresFreshHomeObservation() -> HomeFeedUsageState.UNKNOWN
      else -> HomeFeedUsageState.PAUSED
    }
    when (action) {
      XAction.HOME_BREAK -> {
        val wasShowing = xOverlay.isShowing
        xHomeUsageState = HomeFeedUsageState.PAUSED
        xOverlay.show(preferences.xHomeMinutes)
        val lockoutDeadline = xStateMachine.homeRuntimeState(nowMs).blockedUntilElapsedMs
        recordXHomeStats(wasShowing, xOverlay.isShowing, EnforcementStatsOutcome.SUCCESS, lockoutDeadline)
      }
      XAction.HOME_UNAVAILABLE -> {
        xHomeUsageState = HomeFeedUsageState.UNKNOWN
        xOverlay.showUnavailable()
      }
      XAction.LEAVE_VIDEO -> {
        xOverlay.hide()
        // X's own Back control closes its viewer without docking playback into PiP.
        val back = findXNode(root) { it.contentDescription?.toString() == "Back" }
        var target = back
        var leftVideo = false
        var attempts = 0
        while (target != null && attempts++ < MAX_PARENT_CHAIN) {
          val candidate = target ?: break
          if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            leftVideo = true
            break
          }
          target = candidate.parent
        }
        if (leftVideo) {
          val callbackKey = event?.eventTime?.toString() ?: SystemClock.elapsedRealtime().toString()
          recordXVideoStats(callbackKey, EnforcementStatsOutcome.SUCCESS)
          Toast.makeText(this, R.string.zen_guard_x_video_blocked, Toast.LENGTH_SHORT).show()
        }
      }
      XAction.NONE -> if (surface != XSurface.UNKNOWN) {
        xOverlay.hide()
        resetStatsDedupe(EnforcementReason.X_HOME)
      }
    }
    publishXHomeStatus(showUnavailableOnFailure = surface == XSurface.HOME && settings.homeEnabled)
  }

  /** X's observed pager is the full-screen scroll node two levels under VideoTab. */
  private fun isXVideoPager(node: AccessibilityNodeInfo): Boolean =
    node.isVisibleToUser && node.isScrollable && node.parent?.parent?.viewIdResourceName == "VideoTab"

  /** X can emit the pager's scroll event from one of its descendants. */
  private fun isXVideoPagerOrDescendant(node: AccessibilityNodeInfo): Boolean {
    var candidate: AccessibilityNodeInfo? = node
    var depth = 0
    while (candidate != null && depth++ <= MAX_PARENT_CHAIN) {
      if (isXVideoPager(candidate)) return true
      candidate = candidate.parent
    }
    return false
  }

  private fun findXNode(root: AccessibilityNodeInfo, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
    val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
    queue.add(root to 0)
    var visited = 0
    while (queue.isNotEmpty() && visited++ < MAX_NODES) {
      val (node, depth) = queue.removeFirst()
      if (!node.isVisibleToUser) continue
      if (matches(node)) return node
      if (depth < MAX_DEPTH) for (index in 0 until node.childCount) node.getChild(index)?.let { queue.add(it to depth + 1) }
    }
    return null
  }

  /**
   * Keyboard, system-bar, and other transient windows also emit accessibility events while
   * Instagram stays in the foreground. Do not treat those events as an app switch. Clear the
   * guard only after a window event confirms that no Instagram window remains visible.
   */
  private fun handleNonInstagramEvent(event: AccessibilityEvent) {
    if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
      event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
    ) {
      return
    }

    val instagramWindowVisible = windows.any { window ->
      window.root?.packageName?.toString() == INSTAGRAM_PACKAGE
    }
    if (!instagramWindowVisible) {
      instagramNavigationSuppressedUntilMs = 0L
      clearInstagramEnforcement(preserveHomeSession = true)
    }
  }

  /** Null [event] re-reads a deadline or card handoff without inventing a click or scroll. */
  private fun handleInstagramEvent(event: AccessibilityEvent?, observedRoot: AccessibilityNodeInfo? = rootInActiveWindow) {
    val root = observedRoot ?: return
    // While our accessibility overlay is attached, Android can still deliver queued Instagram
    // events even though rootInActiveWindow points at the overlay (or another transient window).
    // Never classify that tree as Instagram UNKNOWN and erase the DM provenance underneath it.
    if (root.packageName?.toString() != INSTAGRAM_PACKAGE) return
    val nodes = snapshot(root)
    val detection = InstagramDetector.detect(nodes)
    if (detection.surface == InstagramSurface.UNKNOWN) {
      instagramDebugTrace?.record(
        event = event,
        nodes = nodes,
        detection = detection,
        observationMode = preferences.instagramObservationMode,
        exploreBlocked = preferences.instagramExploreBlocked,
        action = null,
      )
      instagramStateMachine.onTransientSurfaceLost(SystemClock.elapsedRealtime())
      return
    }

    preferences.recordInstagramDetection(System.currentTimeMillis(), detection)

    val nowMs = SystemClock.elapsedRealtime()
    if (nowMs < instagramNavigationSuppressedUntilMs) {
      if (detection.surface == InstagramSurface.DIRECT_MESSAGES && isMessagesInbox(nodes)) {
        instagramNavigationSuppressedUntilMs = 0L
        instagramBlockReason = null
        instagramOverlay.hide()
      }
      instagramDebugTrace?.record(
        event = event,
        nodes = nodes,
        detection = detection,
        observationMode = preferences.instagramObservationMode,
        exploreBlocked = preferences.instagramExploreBlocked,
        action = InstagramGuardAction.None,
      )
      return
    }

    if (preferences.instagramObservationMode) {
      instagramDebugTrace?.record(
        event = event,
        nodes = nodes,
        detection = detection,
        observationMode = true,
        exploreBlocked = preferences.instagramExploreBlocked,
        action = InstagramGuardAction.None,
      )
      return
    }

    if (shouldRecoverInstagramStorage(
        storageAvailable = instagramStorageAvailable,
        surface = detection.surface,
        isForeground = isScreenInteractive,
      )
    ) {
      if (homeFeedStatusStore.recoverInstagram(nowMs)) {
        instagramStorageAvailable = true
        instagramStateMachine.recoverStorage(nowMs)
      }
    }

    val reelPagerVisible = hasReelPager(nodes)
    val dmThreadClicked = event != null && isDmThreadClick(event, nodes)
    val guardInput = InstagramGuardInput(
      surface = detection.surface,
      nowMs = nowMs,
      isForeground = isScreenInteractive,
      dmThreadClicked = dmThreadClicked,
      dmThreadVisible = isDmThreadVisible(nodes),
      reelPagerVisible = reelPagerVisible,
      reelPagerScrolled = event != null && isReelPagerScroll(event, nodes, reelPagerVisible),
    )
    val action = instagramStateMachine.next(guardInput, preferences.instagramSettings())
    instagramDebugTrace?.record(
      event = event,
      nodes = nodes,
      detection = detection,
      observationMode = false,
      exploreBlocked = preferences.instagramExploreBlocked,
      action = action,
      input = guardInput,
    )

    when (action) {
      is InstagramGuardAction.ShowBlocker -> {
        // The state machine re-emits the same blocker on every event while it
        // stays up. Count the stop only when the block appears, not per event.
        val isNewBlock = instagramBlockReason == null
        instagramBlockReason = action.reason
        val wallNow = System.currentTimeMillis()
        if (isNewBlock) dailyTally.recordStop(wallNow)
        instagramOverlay.show(
          action = action,
          reelsMinutes = preferences.instagramReelsMinutes,
          debugInfo = if (BuildConfig.DEBUG) {
            val debugState = instagramStateMachine.debugState(nowMs)
            InstagramBlockerDebugInfo(
              surface = detection.surface,
              reason = action.reason,
              event = event?.let { AccessibilityEvent.eventTypeToString(it.eventType) } ?: "deadline",
              eventPackage = event?.packageName?.toString().orEmpty().ifEmpty { "none" },
              rootPackage = root.packageName?.toString().orEmpty().ifEmpty { "none" },
              source = event?.source?.viewIdResourceName?.substringAfterLast('/').orEmpty().ifEmpty { "none" },
              limits = "home=${preferences.instagramHomeMinutes}m reels=${preferences.instagramReelsMinutes}m wait=${preferences.instagramWaitSeconds}s",
              dmThreadVisible = guardInput.dmThreadVisible,
              dmThreadClicked = guardInput.dmThreadClicked,
              reelPagerVisible = guardInput.reelPagerVisible,
              reelPagerScrolled = guardInput.reelPagerScrolled,
              dmProvenanceAgeMs = debugState.dmProvenanceAgeMs,
              dmThreadActive = debugState.dmThreadActive,
              homeElapsedMs = debugState.homeElapsedMs,
              reelsWindowRemainingMs = debugState.reelsWindowRemainingMs,
            )
          } else {
            null
          },
          onAttached = {
            instagramDeadlineHandler.removeCallbacks(instagramReelsDeadlineCheck)
            recordInstagramBlockStats(action.reason, EnforcementStatsOutcome.SUCCESS)
          },
        )
      }
      InstagramGuardAction.None -> {
        if (instagramBlockReason != null) {
          instagramBlockReason = null
          instagramOverlay.hide()
        }
        resetInstagramStatsDedupe()
      }
    }
    publishInstagramHomeStatus()
  }

  private fun instagramBlockerStats(): InstagramBlockerStats {
    val wallNow = System.currentTimeMillis()
    val tally = dailyTally.counts(wallNow)
    return InstagramBlockerStats(
      home = instagramStateMachine.homeRuntimeState(SystemClock.elapsedRealtime()),
      homeAllowanceMinutes = preferences.instagramHomeMinutes,
      stoppedToday = tally.stopped,
      continuedToday = tally.continued,
      dailyCountsResetInMs = millisUntilLocalMidnight(wallNow),
    )
  }

  override fun onInterrupt() {
    closeAppRuleOverlay()
    browserIntervention?.end()
    clearXEnforcement(preserveHomeLockout = true)
    resetYouTubeEnforcement()
    clearStatsDedupeState()
    clearInstagramEnforcement(preserveHomeSession = true)
  }

  private fun openInstagramMessages() {
    if (!canRunEnforcementAction()) return
    navigationHandler.removeCallbacksAndMessages(null)
    instagramNavigationSuppressedUntilMs =
      SystemClock.elapsedRealtime() + NAVIGATION_SUPPRESSION_MS
    val movedBack = performGlobalAction(GLOBAL_ACTION_BACK)
    if (!movedBack) InstagramIntentHelper.openMain(this)
    navigationHandler.postDelayed({ ensureMessagesVisible(0) }, NAVIGATION_RETRY_MS)
  }

  override fun onDestroy() {
    appRuleHandler.removeCallbacksAndMessages(null)
    browserIntervention?.end()
    resetYouTubeEnforcement()
    xHandler.removeCallbacksAndMessages(null)
    if (::preferences.isInitialized && hasActiveProtection()) {
      // Keep the last durable X boundary so a reconnect can reconcile the time
      // spent while this service instance was gone. A disabled guard is cleared
      // normally and must not retain a stale session.
      publishXHomeStatus()
    } else {
      clearXEnforcement()
    }
    navigationHandler.removeCallbacksAndMessages(null)
    usageHandler.removeCallbacksAndMessages(null)
    instagramDeadlineHandler.removeCallbacksAndMessages(null)
    closeAppRuleOverlay()
    clearInstagramEnforcement(preserveHomeSession = hasActiveProtection())
    clearStatsDedupeState()
    if (screenReceiverRegistered) {
      unregisterReceiver(screenStateReceiver)
      screenReceiverRegistered = false
    }
    super.onDestroy()
  }

  private val isScreenInteractive: Boolean
    get() = getSystemService(PowerManager::class.java)?.isInteractive == true

  /**
   * An untouched Reel emits no accessibility events, so the window granted by Continue would
   * otherwise end only at the next swipe. Recheck the live tree when the window ends.
   */
  private fun scheduleInstagramReelsDeadline() {
    instagramDeadlineHandler.removeCallbacks(instagramReelsDeadlineCheck)
    val remainingMs = instagramStateMachine.reelsWindowRemainingMs(SystemClock.elapsedRealtime()) ?: return
    instagramDeadlineHandler.postDelayed(instagramReelsDeadlineCheck, remainingMs)
  }

  /**
   * Acts only while the policy still holds the ended window. Leaving Reels, the app, or the
   * screen clears that window, so a stale callback cannot block another surface. Retry transient
   * trees until a blocker attaches or the policy clears the window. The normal handler still
   * requires a live Instagram root and a visible Reels pager.
   */
  private fun checkInstagramReelsDeadline() {
    val remainingMs = instagramStateMachine.reelsWindowRemainingMs(SystemClock.elapsedRealtime()) ?: return
    if (!canRunEnforcementAction() || preferences.instagramObservationMode || !isScreenInteractive ||
      instagramOverlay.isShowing
    ) return
    // Handler delays use uptime and the policy uses elapsed time; separate rounding can run this 1 ms early.
    if (remainingMs > 0L) {
      instagramDeadlineHandler.postDelayed(instagramReelsDeadlineCheck, remainingMs)
      return
    }
    handleInstagramEvent(null)
    if (!instagramOverlay.isShowing &&
      instagramStateMachine.reelsWindowRemainingMs(SystemClock.elapsedRealtime()) == 0L
    ) {
      instagramDeadlineHandler.postDelayed(instagramReelsDeadlineCheck, INSTAGRAM_REELS_DEADLINE_RETRY_MS)
    }
  }

  private fun clearInstagramEnforcement(preserveHomeSession: Boolean = false) {
    instagramDeadlineHandler.removeCallbacks(instagramReelsDeadlineCheck)
    if (preserveHomeSession) {
      instagramStateMachine.onAppBackground(SystemClock.elapsedRealtime(), preferences.instagramSettings())
    } else {
      instagramStateMachine.onSurfaceLost()
    }
    instagramBlockReason = null
    if (::instagramOverlay.isInitialized) instagramOverlay.hide()
    resetInstagramStatsDedupe()
    publishInstagramHomeStatus()
  }

  private fun publishInstagramHomeStatus() {
    if (!::homeFeedStatusStore.isInitialized) return
    val nowElapsedMs = SystemClock.elapsedRealtime()
    val runtime = instagramStateMachine.homeRuntimeState(nowElapsedMs)
    if (homeFeedStatusStore.recordInstagram(runtime)) return

    // Do not continue a confident in-memory Home allowance after the durable boundary failed.
    // The state machine keeps Instagram's existing blocking semantics while the UI observes the
    // store's explicit unavailable state.
    instagramStorageAvailable = false
    instagramStateMachine.markStorageUnavailable()
  }

  private fun publishXHomeStatus(showUnavailableOnFailure: Boolean = false) {
    if (!::homeFeedStatusStore.isInitialized || !xStorageAvailable) return
    val nowElapsedMs = SystemClock.elapsedRealtime()
    val runtime = xStateMachine.homeRuntimeState(nowElapsedMs)
    if (homeFeedStatusStore.recordX(runtime.copy(usageState = xHomeUsageState))) return

    // Once a durable boundary cannot be confirmed, continuing to expose X would let process death
    // replay an older allowance. Stop the Home rule and keep the UI explicitly unavailable.
    xStorageAvailable = false
    xHomeUsageState = HomeFeedUsageState.UNKNOWN
    xStateMachine.markStorageUnavailable()
    if (showUnavailableOnFailure && ::xOverlay.isInitialized) xOverlay.showUnavailable()
  }

  /**
   * Each X feed enforces only on a surface the service has already seen. A feed
   * switched on later therefore starts on its own signal, and never pauses a
   * feed that is already running.
   */
  private fun xSettings() = XSettings(
    homeEnabled = preferences.xHomeEnabled && preferences.hasXSignal(ZenGuardPreferences.X_HOME_SIGNAL),
    videosEnabled = preferences.xVideosEnabled && preferences.hasXSignal(ZenGuardPreferences.X_VIDEO_SIGNAL),
    homeAllowanceMs = preferences.xHomeMinutes * 60_000L,
  )

  private fun hasActiveProtection(): Boolean =
    ::preferences.isInitialized && preferences.hasCurrentConsent && preferences.protectionEnabled

  /**
   * Service-level stats gate. Only a completed action reaches the ledger, and a stable in-memory
   * key prevents repeated callbacks from invoking recordEnforcement twice. The persisted action key
   * remains fresh for each accepted event; deduplication belongs to this service lifecycle state.
   */
  internal fun recordStatsAction(
    reason: EnforcementReason,
    outcome: EnforcementStatsOutcome,
    dedupeKey: String? = null,
    durableActionKey: String? = null,
  ): Boolean {
    if (outcome != EnforcementStatsOutcome.SUCCESS) return false
    if (dedupeKey != null && statsDedupeKeys[reason] == dedupeKey) return false
    if (dedupeKey != null) statsDedupeKeys[reason] = dedupeKey
    return recordEnforcement(reason, durableActionKey)
  }

  internal fun recordYouTubeShortsStats(pageIndex: Int?, outcome: EnforcementStatsOutcome): Boolean {
    if (pageIndex == null) return false
    return recordStatsAction(EnforcementReason.YOUTUBE_SHORTS, outcome, "page:$pageIndex")
  }

  internal fun recordXHomeStats(
    wasShowing: Boolean,
    overlayAttached: Boolean,
    outcome: EnforcementStatsOutcome,
    lockoutDeadlineElapsedMs: Long? = null,
  ): Boolean {
    if (wasShowing || !overlayAttached) return false
    val durableKey = lockoutDeadlineElapsedMs?.takeIf { it >= 0L }?.let { "x_home_lockout:$it" }
    return recordStatsAction(EnforcementReason.X_HOME, outcome, "overlay", durableKey)
  }

  internal fun recordXVideoStats(callbackKey: String, outcome: EnforcementStatsOutcome): Boolean =
    recordStatsAction(EnforcementReason.X_VIDEOS, outcome, callbackKey)

  internal fun recordInstagramBlockStats(reason: InstagramBlockReason, outcome: EnforcementStatsOutcome): Boolean {
    val statsReason = when (reason) {
      InstagramBlockReason.REELS_ENTRY,
      InstagramBlockReason.REELS_SWIPE,
      InstagramBlockReason.REELS_WINDOW_EXPIRED,
      -> EnforcementReason.INSTAGRAM_REELS
      InstagramBlockReason.HOME_LIMIT -> EnforcementReason.INSTAGRAM_HOME
      InstagramBlockReason.EXPLORE -> EnforcementReason.INSTAGRAM_EXPLORE
    }
    return recordStatsAction(statsReason, outcome, reason.name)
  }

  internal fun recordAppLimitStats(packageName: String, outcome: EnforcementStatsOutcome): Boolean =
    recordStatsAction(EnforcementReason.APP_LIMIT, outcome, packageName)

  internal fun recordRollingLimitStats(packageName: String, outcome: EnforcementStatsOutcome): Boolean =
    recordStatsAction(EnforcementReason.ROLLING_LIMIT, outcome, packageName)

  internal fun recordTimedVisitStats(packageName: String, outcome: EnforcementStatsOutcome): Boolean =
    recordStatsAction(EnforcementReason.TIMED_VISIT, outcome, packageName)

  private fun resetStatsDedupe(reason: EnforcementReason) {
    statsDedupeKeys.remove(reason)
  }

  private fun resetInstagramStatsDedupe() {
    resetStatsDedupe(EnforcementReason.INSTAGRAM_REELS)
    resetStatsDedupe(EnforcementReason.INSTAGRAM_HOME)
    resetStatsDedupe(EnforcementReason.INSTAGRAM_EXPLORE)
  }

  internal fun resetInstagramStatsDedupe(reason: InstagramBlockReason?) {
    val statsReason = when (reason) {
      InstagramBlockReason.REELS_ENTRY,
      InstagramBlockReason.REELS_SWIPE,
      InstagramBlockReason.REELS_WINDOW_EXPIRED,
      -> EnforcementReason.INSTAGRAM_REELS
      InstagramBlockReason.HOME_LIMIT -> EnforcementReason.INSTAGRAM_HOME
      InstagramBlockReason.EXPLORE -> EnforcementReason.INSTAGRAM_EXPLORE
      null -> return
    }
    resetStatsDedupe(statsReason)
  }

  private fun clearStatsDedupeState() {
    statsDedupeKeys.clear()
  }

  private fun recordEnforcement(reason: EnforcementReason, durableActionKey: String? = null): Boolean {
    val store = enforcementStats ?: return false
    statsActionSequence = if (statsActionSequence == Long.MAX_VALUE) 1L else statsActionSequence + 1L
    val nowMs = System.currentTimeMillis()
    return store.record(reason, durableActionKey ?: "${reason.key}:$nowMs:$statsActionSequence", nowMs)
  }

  /** Clear every in-memory action path without inspecting another app's screen. */
  private fun clearInactiveProtection() {
    browserIntervention?.end()
    resetYouTubeEnforcement()
    clearStatsDedupeState()
    clearXEnforcement()
    usageTracker.reset()
    currentForegroundPackage = null
    intentSessions.reset()
    appRuleHandler.removeCallbacks(intentDeadlineCheck)
    closeAppRuleOverlay()
    navigationHandler.removeCallbacksAndMessages(null)
    instagramNavigationSuppressedUntilMs = 0L
    clearInstagramEnforcement()
  }

  /**
   * Android Settings is the user's unconditional escape path from this service. Clear overlays,
   * delayed navigation, and tracked app-rule state without changing the in-app settings lock.
   */
  private fun enterSystemSettings() {
    browserIntervention?.end()
    resetYouTubeEnforcement()
    clearStatsDedupeState()
    clearXEnforcement(preserveHomeLockout = true)
    usageTracker.reset()
    currentForegroundPackage = null
    closeAppRuleOverlay()
    navigationHandler.removeCallbacksAndMessages(null)
    instagramNavigationSuppressedUntilMs = 0L
    clearInstagramEnforcement(preserveHomeSession = true)
  }

  private fun resetYouTubeEnforcement() {
    stateMachine.reset()
    youtubeShortsPager.reset()
    youtubeShortsExitResult.clear()
    if (::youtubeShortsResultOverlay.isInitialized) youtubeShortsResultOverlay.hide()
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

  /** Reads text and descriptions only from exact address-bar nodes; page text is never copied. */
  private fun handleBrowserEvent(browserPackage: String, observedRoot: AccessibilityNodeInfo? = null) {
    val intervention = browserIntervention ?: return
    if (!canRunEnforcementAction()) return
    if (!adultSiteStore.enabled) {
      browserIntervention?.end()
      return
    }
    val root = observedRoot ?: browserRoot(browserPackage) ?: return
    if (root.packageName?.toString() != browserPackage) return
    val detection = BrowserUrlDetector.detect(browserPackage, browserAddressBars(root, browserPackage)) ?: return
    adultSiteStore.recordBrowserSignal(detection.browserMask)
    intervention.observe(
      browserPackage,
      blocked = AdultSitePolicy.isBlocked(detection.host, adultSiteStore.customHosts()),
    )
  }

  private fun browserRoot(browserPackage: String): AccessibilityNodeInfo? =
    windows.firstNotNullOfOrNull { window ->
      window.root?.takeIf { it.packageName?.toString() == browserPackage }
    } ?: rootInActiveWindow?.takeIf { it.packageName?.toString() == browserPackage }

  private fun browserAddressBars(root: AccessibilityNodeInfo, browserPackage: String): List<BrowserNodeSignal> {
    data class Pending(val node: AccessibilityNodeInfo, val depth: Int)
    val pending = ArrayDeque<Pending>()
    val result = ArrayList<BrowserNodeSignal>(2)
    pending.add(Pending(root, 0))
    var visited = 0
    while (pending.isNotEmpty() && visited++ < MAX_BROWSER_NODES) {
      val (node, depth) = pending.removeFirst()
      if (!node.isVisibleToUser) continue
      val viewId = node.viewIdResourceName.orEmpty()
      if (BrowserUrlDetector.isAddressBar(browserPackage, viewId)) {
        result.add(
          BrowserNodeSignal(viewId, node.text?.toString().orEmpty(), true, node.contentDescription?.toString().orEmpty()),
        )
      }
      if (depth >= MAX_BROWSER_DEPTH) continue
      for (index in 0 until node.childCount) node.getChild(index)?.let { pending.add(Pending(it, depth + 1)) }
    }
    return result
  }

  /**
   * A DM tab or inbox visit is not provenance. Accept only a click whose source is a likely thread
   * row/control and whose tree also contains independent DM structure.
   */
  private fun isDmThreadClick(event: AccessibilityEvent, nodes: List<NodeSignal>): Boolean {
    if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return false
    val source = event.source ?: return false
    if (!source.isVisibleToUser || !source.isClickable) return false

    val sourceSignals = nodeSignalsAlongParentChain(source)
    val sourceIsThreadTarget = sourceSignals.any { signal ->
      signal.containsAny(DM_THREAD_CLICK_TARGETS)
    }
    if (!sourceIsThreadTarget) return false

    val treeIds = nodes.map { it.viewId.lowercase() }
    val hasDmContainer = treeIds.any { it.containsAny(DM_CONTAINER_IDS) }
    val hasThreadContext = treeIds.any { it.containsAny(DM_THREAD_CONTEXT_IDS) }
    return hasDmContainer && hasThreadContext
  }

  /** Detects an open conversation even when Instagram omits the media-tile click event. */
  private fun isDmThreadVisible(nodes: List<NodeSignal>): Boolean {
    val ids = nodes.map { it.viewId.lowercase() }
    val hasThreadContent = ids.any { it.containsAny(DM_THREAD_CONTENT_IDS) }
    val hasThreadChrome = ids.any { it.containsAny(DM_THREAD_CHROME_IDS) }
    return hasThreadContent && hasThreadChrome
  }

  /**
   * Returns to the Messages tab after a blocker. Instagram's direct-inbox deep link is accepted
   * by some releases but ignored by others, so use Android Back first and click the visible tab
   * after a package launch when the deep link did not produce the requested surface.
   */
  private fun ensureMessagesVisible(attempt: Int) {
    if (!canRunEnforcementAction()) return
    if (!isScreenInteractive || attempt > MAX_NAVIGATION_ATTEMPTS) return

    val root = rootInActiveWindow
    val rootIsInstagram = root?.packageName?.toString() == INSTAGRAM_PACKAGE
    if (rootIsInstagram) {
      val nodes = snapshot(root)
      val detection = InstagramDetector.detect(nodes)
      if (detection.surface == InstagramSurface.DIRECT_MESSAGES && isMessagesInbox(nodes)) {
        instagramNavigationSuppressedUntilMs = 0L
        return
      }
      if (clickDirectTab(root)) {
        navigationHandler.postDelayed({ ensureMessagesVisible(attempt + 1) }, NAVIGATION_RETRY_MS)
        return
      }

      // Back out of an open conversation or the full-screen Reel viewer before trying the tab.
      // A single Back from a Reel opened in a DM usually lands in the thread, not the inbox.
      if (detection.surface == InstagramSurface.DIRECT_MESSAGES && isDmThreadVisible(nodes) ||
        detection.surface == InstagramSurface.REELS_VIEWER
      ) {
        if (performGlobalAction(GLOBAL_ACTION_BACK)) {
          navigationHandler.postDelayed({ ensureMessagesVisible(attempt + 1) }, NAVIGATION_RETRY_MS)
          return
        }
      }
    }

    if (attempt == MAIN_LAUNCH_ATTEMPT) {
      InstagramIntentHelper.openMain(this)
    }
    navigationHandler.postDelayed({ ensureMessagesVisible(attempt + 1) }, NAVIGATION_RETRY_MS)
  }

  /** A direct-message thread and the Messages inbox share the same coarse surface signal. */
  private fun isMessagesInbox(nodes: List<NodeSignal>): Boolean {
    val ids = nodes.map { it.viewId.lowercase() }
    return ids.any { id -> INBOX_ID_FRAGMENTS.any(id::contains) }
  }

  private fun clickDirectTab(root: AccessibilityNodeInfo): Boolean {
    data class PendingNode(val node: AccessibilityNodeInfo, val depth: Int)

    val queue = ArrayDeque<PendingNode>()
    queue.add(PendingNode(root, 0))
    while (queue.isNotEmpty()) {
      val (node, depth) = queue.removeFirst()
      if (!node.isVisibleToUser) continue
      val viewId = node.viewIdResourceName?.lowercase().orEmpty()
      if (viewId.substringAfterLast('/') == "direct_tab") {
        var target: AccessibilityNodeInfo? = node
        repeat(MAX_PARENT_CHAIN) {
          val candidate = target ?: return@repeat
          if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
          target = candidate.parent
        }
      }

      if (depth >= MAX_DEPTH) continue
      for (index in 0 until node.childCount) {
        node.getChild(index)?.let { queue.add(PendingNode(it, depth + 1)) }
      }
    }
    return false
  }

  /** A scroll counts only when the event source itself is the full-screen Reel pager. */
  private fun isReelPagerScroll(
    event: AccessibilityEvent,
    nodes: List<NodeSignal>,
    pagerVisible: Boolean,
  ): Boolean {
    if (!pagerVisible || event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return false
    val sourceId = event.source?.viewIdResourceName?.lowercase().orEmpty()
    if (sourceId.isBlank()) return false
    return nodes.any { node ->
      node.viewId.lowercase() == sourceId && InstagramDetector.isReelsPager(sourceId)
    }
  }

  private fun hasReelPager(nodes: List<NodeSignal>): Boolean = nodes.any { node ->
    InstagramDetector.isReelsPager(node.viewId.lowercase())
  }

  private fun nodeSignalsAlongParentChain(source: AccessibilityNodeInfo): List<String> {
    val signals = ArrayList<String>(MAX_PARENT_CHAIN)
    var current: AccessibilityNodeInfo? = source
    repeat(MAX_PARENT_CHAIN) {
      val node = current ?: return@repeat
      signals += node.viewIdResourceName.orEmpty().lowercase()
      current = node.parent
    }
    return signals
  }

  private fun snapshot(root: AccessibilityNodeInfo): List<NodeSignal> {
    data class PendingNode(val node: AccessibilityNodeInfo, val depth: Int)

    val result = ArrayList<NodeSignal>(128)
    val queue = ArrayDeque<PendingNode>()
    queue.add(PendingNode(root, 0))

    while (queue.isNotEmpty() && result.size < MAX_NODES) {
      val (node, depth) = queue.removeFirst()
      if (!node.isVisibleToUser) continue
      result.add(
        NodeSignal(
          viewId = node.viewIdResourceName.orEmpty(),
          text = node.text?.toString().orEmpty(),
          description = node.contentDescription?.toString().orEmpty(),
          selected = node.isSelected,
        ),
      )

      if (depth >= MAX_DEPTH) continue
      for (index in 0 until node.childCount) {
        node.getChild(index)?.let { queue.add(PendingNode(it, depth + 1)) }
      }
    }

    return result
  }

  companion object {
    private const val X_PACKAGE = "com.twitter.android"
    private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    private const val INSTAGRAM_PACKAGE = "com.instagram.android"
    private const val MAX_NODES = 500
    private const val MAX_DEPTH = 36
    private const val MAX_PARENT_CHAIN = 8
    private const val NAVIGATION_RETRY_MS = 250L
    private const val NAVIGATION_SUPPRESSION_MS = 3_000L
    private const val MAIN_LAUNCH_ATTEMPT = 3
    private const val MAX_NAVIGATION_ATTEMPTS = 12
    private const val USAGE_TICK_MS = 15_000L
    private const val INSTAGRAM_REELS_DEADLINE_RETRY_MS = 1_000L
    private const val MAX_BROWSER_NODES = 180
    private const val MAX_BROWSER_DEPTH = 18

    private val INBOX_ID_FRAGMENTS = listOf(
      "direct_inbox",
      "inbox_refreshable_thread_list_recyclerview",
      "direct_inbox_action_bar",
    )

    private val DM_THREAD_CLICK_TARGETS = listOf(
      "message_list",
      "message_content",
      "message_group",
      "portrait_xma_container",
      "generic_xma_container",
    )

    private val DM_CONTAINER_IDS = listOf(
      "direct_inbox",
      "inbox_refreshable_thread_list_recyclerview",
      "message_list",
    )

    private val DM_THREAD_CONTEXT_IDS = listOf(
      "row_inbox",
      "thread_row",
      "direct_thread",
      "inbox_thread",
      "message_thread",
      "thread_header",
      "thread_composer",
      "message_composer",
    )

    private val DM_THREAD_CONTENT_IDS = listOf(
      "thread_fragment_container",
      "message_list",
      "message_content",
      "message_content_portrait_xma_container",
    )

    private val DM_THREAD_CHROME_IDS = listOf(
      "header_title",
      "header_subtitle",
      "message_composer",
      "thread_composer",
      "row_thread_composer",
    )
  }
}

private fun String.containsAny(fragments: List<String>): Boolean = fragments.any(::contains)
