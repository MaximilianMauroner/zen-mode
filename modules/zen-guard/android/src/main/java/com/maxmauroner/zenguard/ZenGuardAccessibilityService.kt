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
import java.util.ArrayDeque

class ZenGuardAccessibilityService() : AccessibilityService() {
  /** JVM tests can supply the same local ledger without starting Android service callbacks. */
  internal constructor(statsStore: EnforcementStatsStore) : this() {
    stats.replaceStore(statsStore)
  }

  private lateinit var preferences: ZenGuardPreferences
  private val stats = InterventionStats(null)
  private val youtube = YouTubeOwner(this, stats)
  private val x = XOwner(this, stats, canEnforce = ::canRunEnforcementAction)
  private var instagramStorageAvailable = true
  private val instagramStateMachine = InstagramGuardStateMachine()
  private val instagramDebugTrace = if (BuildConfig.DEBUG) InstagramDebugTrace() else null
  private val navigationHandler by lazy { Handler(Looper.getMainLooper()) }
  private val reelsDeadline by lazy { DeadlineTask(Handler(Looper.getMainLooper()), decide = ::checkInstagramReelsDeadline) }
  private lateinit var instagramIntervention: InstagramIntervention
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
  private val appCardRefresh by lazy { DeadlineTask(Handler(Looper.getMainLooper()), decide = ::refreshAppRuleOverlay) }
  private val visitDeadline by lazy {
    DeadlineTask(Handler(Looper.getMainLooper())) {
      enforceIntentSession()
      DeadlineDecision.Complete
    }
  }
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
      x.screenOff(nowMs)
      reelsDeadline.cancel()
      instagramStateMachine.onAppBackground(nowMs, preferences.instagramSettings())
      x.publishHomeStatus()
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
    stats.replaceStore(
      try {
        EnforcementStatsStore(this)
      } catch (_: RuntimeException) {
        null
      },
    )
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
      recordAttachment = { stats.record(EnforcementReason.BLOCKED_SITE, EnforcementStatsOutcome.SUCCESS) },
      recheck = { handleBrowserEvent(it) },
    )
    youtube.onServiceConnected(preferences)
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
    x.restore(preferences, homeFeedStatusStore, nowElapsedMs)
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
    x.start()
    instagramIntervention = InstagramIntervention(
      service = this,
      readStats = ::instagramBlockerStats,
      canEnforce = ::canRunEnforcementAction,
      recordStop = { dailyTally.recordStop(System.currentTimeMillis()) },
      recordAttachment = { reason ->
        reelsDeadline.cancel()
        stats.record(reason, EnforcementStatsOutcome.SUCCESS)
      },
      leave = { reason ->
        instagramStateMachine.leaveBlockedSurface()
        when (reason) {
          InstagramBlockReason.HOME_LIMIT -> performGlobalAction(GLOBAL_ACTION_HOME)
          InstagramBlockReason.EXPLORE,
          InstagramBlockReason.REELS_ENTRY,
          InstagramBlockReason.REELS_SWIPE,
          InstagramBlockReason.REELS_WINDOW_EXPIRED,
          -> openInstagramMessages()
        }
      },
      openMessages = {
        instagramStateMachine.leaveBlockedSurface()
        openInstagramMessages()
      },
      continueReels = {
        instagramStateMachine.continueReels(SystemClock.elapsedRealtime(), preferences.instagramSettings()).also { continued ->
          if (continued) {
            dailyTally.recordContinue(System.currentTimeMillis())
            scheduleInstagramReelsDeadline()
          }
        }
      },
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
          youtube.reset()
        }
        if (windows.none { it.root?.packageName?.toString() == X_PACKAGE }) {
          x.pause()
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
      windows.none { it.root?.packageName?.toString() == X_PACKAGE }) x.pause()

    when (eventPackage) {
      YOUTUBE_PACKAGE -> {
        instagramNavigationSuppressedUntilMs = 0L
        clearInstagramEnforcement(preserveHomeSession = true)
        youtube.observe(event)
      }
      INSTAGRAM_PACKAGE -> handleInstagramEvent(event)
      X_PACKAGE -> {
        clearInstagramEnforcement(preserveHomeSession = true)
        x.observe(event)
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
    clearInstagramEnforcement(preserveHomeSession = true)
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
      BrowserUrlDetector.supports(packageName) -> handleBrowserEvent(packageName, root)
      packageName == INSTAGRAM_PACKAGE -> handleInstagramEvent(null, root)
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
    val nodes = snapshotNodeSignals(root)
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
        instagramIntervention.end()
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
        instagramIntervention.observe(
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
        )
      }
      InstagramGuardAction.None -> instagramIntervention.end()
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
    x.pause()
    youtube.reset()
    stats.clear()
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
    visitDeadline.cancel()
    browserIntervention?.end()
    youtube.reset()
    x.destroy(hasActiveProtection())
    navigationHandler.removeCallbacksAndMessages(null)
    usageHandler.removeCallbacksAndMessages(null)
    closeAppRuleOverlay()
    clearInstagramEnforcement(preserveHomeSession = hasActiveProtection())
    stats.clear()
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
    val nowMs = SystemClock.elapsedRealtime()
    val remainingMs = instagramStateMachine.reelsWindowRemainingMs(nowMs)
    if (remainingMs == null) reelsDeadline.cancel() else reelsDeadline.scheduleAt(nowMs + remainingMs)
  }

  /**
   * Acts only while the policy still holds the ended window. Leaving Reels, the app, or the
   * screen clears that window, so a stale callback cannot block another surface. Retry transient
   * trees until a blocker attaches or the policy clears the window. The normal handler still
   * requires a live Instagram root and a visible Reels pager.
   */
  private fun checkInstagramReelsDeadline(): DeadlineDecision {
    if (instagramStateMachine.reelsWindowRemainingMs(SystemClock.elapsedRealtime()) == null) return DeadlineDecision.Cancel
    if (!canRunEnforcementAction() || preferences.instagramObservationMode || !isScreenInteractive) {
      return DeadlineDecision.Cancel
    }
    if (!instagramIntervention.isShowing) handleInstagramEvent(null)
    return when {
      instagramIntervention.isShowing -> DeadlineDecision.Complete
      instagramStateMachine.reelsWindowRemainingMs(SystemClock.elapsedRealtime()) == 0L ->
        DeadlineDecision.RetryAfter(INSTAGRAM_REELS_DEADLINE_RETRY_MS)
      else -> DeadlineDecision.Cancel
    }
  }

  private fun clearInstagramEnforcement(preserveHomeSession: Boolean = false) {
    reelsDeadline.cancel()
    if (preserveHomeSession) {
      instagramStateMachine.onAppBackground(SystemClock.elapsedRealtime(), preferences.instagramSettings())
    } else {
      instagramStateMachine.onSurfaceLost()
    }
    if (::instagramIntervention.isInitialized) instagramIntervention.end()
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
    youtube.reset()
    stats.clear()
    x.pause()
    usageTracker.reset()
    currentForegroundPackage = null
    closeAppRuleOverlay()
    navigationHandler.removeCallbacksAndMessages(null)
    instagramNavigationSuppressedUntilMs = 0L
    clearInstagramEnforcement(preserveHomeSession = true)
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
      intervention.end()
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
      val nodes = snapshotNodeSignals(root)
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

  companion object {
    private const val X_PACKAGE = "com.twitter.android"
    private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    private const val INSTAGRAM_PACKAGE = "com.instagram.android"
    private const val MAX_DEPTH = 36
    private const val MAX_PARENT_CHAIN = 8
    private const val NAVIGATION_RETRY_MS = 250L
    private const val NAVIGATION_SUPPRESSION_MS = 3_000L
    private const val MAIN_LAUNCH_ATTEMPT = 3
    private const val MAX_NAVIGATION_ATTEMPTS = 12
    private const val USAGE_TICK_MS = 15_000L
    private const val APP_RULE_REFRESH_MS = 1_000L
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
