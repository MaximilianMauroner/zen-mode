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
  private lateinit var instagramOverlay: InstagramBlockerOverlay
  private var instagramBlockReason: InstagramBlockReason? = null
  private var instagramNavigationSuppressedUntilMs = 0L
  private lateinit var appLimits: AppLimitStore
  private lateinit var dailyTally: DailyTally
  private lateinit var intentStore: IntentAppStore
  private lateinit var rollingLimits: RollingLimitStore
  private lateinit var appRuleSafety: AppRuleSafety
  private val intentSessions = IntentSessionTracker()
  private lateinit var intentOverlay: IntentOverlay
  private lateinit var adultSiteStore: AdultSiteRuleStore
  private lateinit var adultSiteOverlay: AdultSiteBlockerOverlay
  private lateinit var homeFeedStatusStore: HomeFeedStatusStore
  private val browserHandler by lazy { Handler(Looper.getMainLooper()) }
  private var lastExternalPackage: String? = null
  private val usageTracker = AppUsageTracker()
  private val usageHandler by lazy { Handler(Looper.getMainLooper()) }
  private var lastLimitToastAtMs = 0L
  private var currentForegroundPackage: String? = null
  private var screenReceiverRegistered = false
  private val screenStateReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action != Intent.ACTION_SCREEN_OFF || !hasActiveProtection()) return
      val nowMs = SystemClock.elapsedRealtime()
      xStateMachine.pause(nowMs, xSettings())
      if (xHomeUsageState != HomeFeedUsageState.UNKNOWN) xHomeUsageState = HomeFeedUsageState.PAUSED
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
        enforceAppLimit(currentForegroundPackage)
        enforceRollingLimit(currentForegroundPackage)
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
    intentOverlay = IntentOverlay(this)
    adultSiteStore = AdultSiteRuleStore(this)
    adultSiteOverlay = AdultSiteBlockerOverlay(this)
    homeFeedStatusStore = HomeFeedStatusStore(this)
    val nowElapsedMs = SystemClock.elapsedRealtime()
    val storedInstagram = homeFeedStatusStore.instagram(nowElapsedMs)
    instagramStorageAvailable = storedInstagram.storageState == HomeFeedStorageState.AVAILABLE
    if (!instagramStorageAvailable) instagramStateMachine.markStorageUnavailable()
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
            dailyTally.recordContinue(System.currentTimeMillis())
          }
          continued
        } else false
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
    // The blocker is an accessibility overlay owned by this package. Ignore its own focus and
    // content events; treating them as an external app would immediately remove the blocker.
    if (eventPackage == packageName) {
      if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
        if (windows.none { it.root?.packageName?.toString() == X_PACKAGE }) {
          clearXEnforcement(preserveHomeLockout = true)
        }
        if (windows.none { it.root?.packageName?.toString() == INSTAGRAM_PACKAGE }) {
          clearInstagramEnforcement(preserveHomeSession = true)
        }
      }
      if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
        windows.none { BrowserUrlDetector.supports(it.root?.packageName?.toString()) }
      ) clearAdultSiteEnforcement()
      return
    }
    if (BrowserUrlDetector.supports(eventPackage)) {
      handleBrowserEvent(eventPackage!!)
    } else if ((event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
        event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) &&
      windows.none { BrowserUrlDetector.supports(it.root?.packageName?.toString()) }
    ) {
      clearAdultSiteEnforcement()
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

    val packageName = event.packageName?.toString() ?: return
    if (statsDedupeKeys[EnforcementReason.APP_LIMIT] != packageName) resetStatsDedupe(EnforcementReason.APP_LIMIT)
    if (statsDedupeKeys[EnforcementReason.ROLLING_LIMIT] != packageName) resetStatsDedupe(EnforcementReason.ROLLING_LIMIT)
    if (statsDedupeKeys[EnforcementReason.TIMED_VISIT] != packageName) resetStatsDedupe(EnforcementReason.TIMED_VISIT)
    usageTracker.onForeground(packageName, SystemClock.elapsedRealtime())?.let(::bankUsage)
    if (packageName != YOUTUBE_PACKAGE && packageName != this.packageName) resetYouTubeEnforcement()
    currentForegroundPackage = packageName
    enforceAppLimit(packageName)
    enforceRollingLimit(packageName)
    if (packageName != this.packageName) {
      lastExternalPackage = packageName
      handleIntentForeground(packageName)
    }
  }

  /** Writes banked foreground time against today's total for that app. */
  private fun bankUsage(attribution: AppUsageTracker.Attribution) {
    if (!::appLimits.isInitialized || !::rollingLimits.isInitialized) return
    if (::appRuleSafety.isInitialized && appRuleSafety.isExempt(attribution.packageName)) return
    val nowWallMs = System.currentTimeMillis()
    if (appLimits.limits().containsKey(attribution.packageName)) {
      appLimits.addUsage(attribution.packageName, attribution.durationMs, nowWallMs)
    }
    if (rollingLimits.rules().containsKey(attribution.packageName)) {
      rollingLimits.addUsage(attribution.packageName, attribution.durationMs, nowWallMs)
    }
  }

  /**
   * Sends the user home once an app has spent its daily budget.
   *
   * Unlike the Shorts and Instagram guards this does not wait for observation
   * mode to end. There is nothing to learn about an app first: the user named
   * the app and the number, so the budget applies from that moment.
   */
  private fun enforceAppLimit(packageName: String?) {
    if (!canRunEnforcementAction()) return
    if (packageName == null || !::appRuleSafety.isInitialized || !appRuleSafety.allowsDailyEnforcement(packageName)) return
    if (!appLimits.isOverBudget(packageName, System.currentTimeMillis())) {
      resetStatsDedupe(EnforcementReason.APP_LIMIT)
      return
    }

    val actionSucceeded = performGlobalAction(GLOBAL_ACTION_HOME)
    usageTracker.reset()
    currentForegroundPackage = null
    recordAppLimitStats(
      packageName = packageName,
      outcome = if (actionSucceeded) EnforcementStatsOutcome.SUCCESS else EnforcementStatsOutcome.FAILED,
    )

    val nowMs = SystemClock.elapsedRealtime()
    if (nowMs - lastLimitToastAtMs < LIMIT_TOAST_INTERVAL_MS) return
    lastLimitToastAtMs = nowMs
    Toast.makeText(this, R.string.zen_guard_app_limit_reached, Toast.LENGTH_SHORT).show()
  }

  /**
   * Sends the user home once an app has spent its rolling allowance.
   * Old slices age out on their own, so the allowance refills over time
   * with nothing to reset.
   */
  private fun enforceRollingLimit(packageName: String?) {
    if (!canRunEnforcementAction()) return
    if (packageName == null || !::appRuleSafety.isInitialized || !appRuleSafety.allowsRollingEnforcement(packageName)) return
    if (!::rollingLimits.isInitialized) return
    if (!rollingLimits.isOver(packageName, System.currentTimeMillis())) {
      resetStatsDedupe(EnforcementReason.ROLLING_LIMIT)
      return
    }

    val actionSucceeded = performGlobalAction(GLOBAL_ACTION_HOME)
    usageTracker.reset()
    currentForegroundPackage = null
    lastExternalPackage = null
    recordRollingLimitStats(
      packageName = packageName,
      outcome = if (actionSucceeded) EnforcementStatsOutcome.SUCCESS else EnforcementStatsOutcome.FAILED,
    )

    val nowMs = SystemClock.elapsedRealtime()
    if (nowMs - lastLimitToastAtMs < LIMIT_TOAST_INTERVAL_MS) return
    lastLimitToastAtMs = nowMs
    Toast.makeText(this, R.string.zen_guard_rolling_limit_reached, Toast.LENGTH_SHORT).show()
  }

  /**
   * Asks for a timed visit when an intent-gated app opens. An active session
   * resumes quietly; an expired one starts the app's downtime before the next
   * ask; anything else goes home without starting a session.
   */
  private fun handleIntentForeground(packageName: String) {
    if (!::intentStore.isInitialized || !::intentOverlay.isInitialized) return
    if (!canRunEnforcementAction()) return
    if (!::appRuleSafety.isInitialized || !appRuleSafety.allowsTimedVisitEnforcement(packageName)) {
      if (intentOverlay.shownPackage() != null) intentOverlay.hide()
      return
    }
    val rule = intentStore.intents()[packageName]
    if (rule == null) {
      if (intentOverlay.shownPackage() != null) intentOverlay.hide()
      return
    }

    val nowElapsedMs = SystemClock.elapsedRealtime()
    if (intentSessions.isActive(packageName, nowElapsedMs)) {
      if (intentOverlay.shownPackage() == packageName) intentOverlay.hide()
      return
    }

    // The grant ran out, in front or away. Close it so the downtime starts.
    val nowWallMs = System.currentTimeMillis()
    if (intentSessions.end(packageName)) {
      intentStore.recordSessionEnd(packageName, nowWallMs)
    }

    val showingForThis = intentOverlay.shownPackage() == packageName
    val cooldownMs = intentStore.cooldownRemainingMs(packageName, nowWallMs)
    if (cooldownMs > 0L) {
      if (showingForThis && intentOverlay.isCooldown()) return
      intentOverlay.showCooldown(packageName, labelFor(packageName), cooldownMs) { leaveIntent() }
      if (intentOverlay.shownPackage() == packageName) {
        recordTimedVisitStats(packageName, EnforcementStatsOutcome.SUCCESS)
      }
    } else {
      if (showingForThis && !intentOverlay.isCooldown()) return
      intentOverlay.showAsk(
        packageName,
        labelFor(packageName),
        rule.sessionMinutes,
        onStart = { grantIntentSession(packageName, rule.sessionMinutes) },
        onLeave = { leaveIntent() },
      )
      if (intentOverlay.shownPackage() == packageName) {
        recordTimedVisitStats(packageName, EnforcementStatsOutcome.SUCCESS)
      }
    }
  }

  /** Starts one timed visit and lets the app through. */
  private fun grantIntentSession(packageName: String, minutes: Int) {
    if (!canRunEnforcementAction()) return
    intentSessions.grant(packageName, minutes, SystemClock.elapsedRealtime())
    resetStatsDedupe(EnforcementReason.TIMED_VISIT)
    if (intentOverlay.shownPackage() == packageName) intentOverlay.hide()
  }

  /** Leaves without starting a session. */
  private fun leaveIntent() {
    intentOverlay.hide()
    if (!canRunEnforcementAction()) return
    performGlobalAction(GLOBAL_ACTION_HOME)
  }

  /**
   * Ends a visit whose minutes ran out while its app is still in front.
   * Runs on the usage tick, so an app the user simply sits in is still
   * caught. Reopening starts the downtime overlay, not a new question.
   */
  private fun enforceIntentSession() {
    if (!::intentStore.isInitialized || !::intentOverlay.isInitialized) return
    if (!canRunEnforcementAction()) return
    val packageName = lastExternalPackage ?: return
    if (!::appRuleSafety.isInitialized || !appRuleSafety.allowsTimedVisitEnforcement(packageName)) {
      intentOverlay.hide()
      lastExternalPackage = null
      return
    }
    if (intentSessions.isActive(packageName, SystemClock.elapsedRealtime())) return
    if (!intentSessions.end(packageName)) return

    intentStore.recordSessionEnd(packageName, System.currentTimeMillis())
    intentOverlay.hide()
    val actionSucceeded = performGlobalAction(GLOBAL_ACTION_HOME)
    lastExternalPackage = null
    recordTimedVisitStats(
      packageName = packageName,
      outcome = if (actionSucceeded) EnforcementStatsOutcome.SUCCESS else EnforcementStatsOutcome.FAILED,
    )
    Toast.makeText(this, R.string.zen_guard_intent_time_up, Toast.LENGTH_SHORT).show()
  }

  /** Human-readable app name, falling back to the package when unknown. */
  private fun labelFor(packageName: String): String = try {
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
  } catch (_: PackageManager.NameNotFoundException) {
    packageName
  }

  private fun handleYouTubeEvent(event: AccessibilityEvent) {
    val root = rootInActiveWindow ?: return
    if (root.packageName?.toString() != YOUTUBE_PACKAGE) return
    val nowMs = System.currentTimeMillis()
    preferences.recordEvent(nowMs)
    val result = ShortsDetector.detect(snapshot(root))
    if (!result.isShortsViewer) {
      resetYouTubeEnforcement()
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
      isViewScrolled = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED,
      sourceViewId = event.source?.viewIdResourceName,
      fromIndex = event.fromIndex,
      toIndex = event.toIndex,
      scrollY = event.scrollY,
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
        recordYouTubeShortsStats(stateMachine.pendingExitPageIndex(), EnforcementStatsOutcome.SUCCESS)
        Toast.makeText(this, R.string.zen_guard_shorts_blocked, Toast.LENGTH_SHORT).show()
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
      source != null && isXVideoPager(source) && event.scrollY > 0
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

  private fun handleInstagramEvent(event: AccessibilityEvent) {
    val root = rootInActiveWindow ?: return
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
    val dmThreadClicked = isDmThreadClick(event, nodes)
    val guardInput = InstagramGuardInput(
      surface = detection.surface,
      nowMs = nowMs,
      isForeground = isScreenInteractive,
      dmThreadClicked = dmThreadClicked,
      dmThreadVisible = isDmThreadVisible(nodes),
      reelPagerVisible = reelPagerVisible,
      reelPagerScrolled = isReelPagerScroll(event, nodes, reelPagerVisible),
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
        val wasShowing = instagramOverlay.isShowing
        instagramBlockReason = action.reason
        val wallNow = System.currentTimeMillis()
        if (isNewBlock) dailyTally.recordStop(wallNow)
        val tally = dailyTally.counts(wallNow)
        instagramOverlay.show(
          action = action,
          homeMinutes = preferences.instagramHomeMinutes,
          reelsMinutes = preferences.instagramReelsMinutes,
          stats = InstagramBlockerStats(
            homeUsedMinutes = (instagramStateMachine.homeElapsedMs() / 60_000L).toInt(),
            homeAllowanceMinutes = preferences.instagramHomeMinutes,
            stoppedToday = tally.stopped,
            continuedToday = tally.continued,
            resetsInMs = millisUntilLocalMidnight(wallNow),
          ),
          debugInfo = if (BuildConfig.DEBUG) {
            val debugState = instagramStateMachine.debugState(nowMs)
            InstagramBlockerDebugInfo(
              surface = detection.surface,
              reason = action.reason,
              event = AccessibilityEvent.eventTypeToString(event.eventType),
              eventPackage = event.packageName?.toString().orEmpty().ifEmpty { "none" },
              rootPackage = root.packageName?.toString().orEmpty().ifEmpty { "none" },
              source = event.source?.viewIdResourceName?.substringAfterLast('/').orEmpty().ifEmpty { "none" },
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
        recordInstagramBlockStats(action.reason, wasShowing, instagramOverlay.isShowing, EnforcementStatsOutcome.SUCCESS)
      }
      InstagramGuardAction.None -> {
        if (instagramBlockReason != null) {
          instagramBlockReason = null
          instagramOverlay.hide()
        }
        resetInstagramStatsDedupe()
      }
    }
    if (action is InstagramGuardAction.ShowBlocker) publishInstagramHomeStatus()
  }

  override fun onInterrupt() {
    clearAdultSiteEnforcement()
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
    browserHandler.removeCallbacksAndMessages(null)
    clearAdultSiteEnforcement()
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
    if (::intentOverlay.isInitialized) intentOverlay.hide()
    clearInstagramEnforcement()
    clearStatsDedupeState()
    if (screenReceiverRegistered) {
      unregisterReceiver(screenStateReceiver)
      screenReceiverRegistered = false
    }
    super.onDestroy()
  }

  private val isScreenInteractive: Boolean
    get() = getSystemService(PowerManager::class.java)?.isInteractive == true

  private fun clearInstagramEnforcement(preserveHomeSession: Boolean = false) {
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

  internal fun recordInstagramBlockStats(
    reason: InstagramBlockReason,
    wasShowing: Boolean,
    overlayAttached: Boolean,
    outcome: EnforcementStatsOutcome,
  ): Boolean {
    if (wasShowing || !overlayAttached) return false
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

  internal fun recordBlockedSiteStats(
    browserPackage: String,
    previousPackage: String?,
    overlayAttached: Boolean,
    outcome: EnforcementStatsOutcome,
  ): Boolean {
    if (previousPackage == browserPackage || !overlayAttached) return false
    return recordStatsAction(EnforcementReason.BLOCKED_SITE, outcome, browserPackage)
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
    clearAdultSiteEnforcement()
    resetYouTubeEnforcement()
    clearStatsDedupeState()
    clearXEnforcement()
    usageTracker.reset()
    currentForegroundPackage = null
    lastExternalPackage = null
    intentSessions.reset()
    if (::intentOverlay.isInitialized) intentOverlay.hide()
    navigationHandler.removeCallbacksAndMessages(null)
    instagramNavigationSuppressedUntilMs = 0L
    clearInstagramEnforcement()
  }

  /**
   * Android Settings is the user's unconditional escape path from this service. Clear overlays,
   * delayed navigation, and tracked app-rule state without changing the in-app settings lock.
   */
  private fun enterSystemSettings() {
    browserHandler.removeCallbacksAndMessages(null)
    clearAdultSiteEnforcement()
    resetYouTubeEnforcement()
    clearStatsDedupeState()
    clearXEnforcement(preserveHomeLockout = true)
    usageTracker.reset()
    currentForegroundPackage = null
    lastExternalPackage = null
    if (::intentOverlay.isInitialized) intentOverlay.hide()
    navigationHandler.removeCallbacksAndMessages(null)
    instagramNavigationSuppressedUntilMs = 0L
    clearInstagramEnforcement(preserveHomeSession = true)
  }

  private fun resetYouTubeEnforcement() {
    stateMachine.reset()
    youtubeShortsPager.reset()
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

  /** Reads text only from exact address-bar nodes; arbitrary browser page text is never copied. */
  private fun handleBrowserEvent(browserPackage: String) {
    if (!::adultSiteStore.isInitialized || !::adultSiteOverlay.isInitialized) return
    if (!canRunEnforcementAction()) return
    if (!adultSiteStore.enabled) {
      clearAdultSiteEnforcement()
      return
    }
    val root = browserRoot(browserPackage) ?: return
    val detection = BrowserUrlDetector.detect(browserPackage, browserAddressBars(root, browserPackage)) ?: return
    adultSiteStore.recordBrowserSignal(detection.browserMask)
    if (AdultSitePolicy.isBlocked(detection.host, adultSiteStore.customHosts())) {
      val previousPackage = adultSiteOverlay.shownPackage()
      adultSiteOverlay.show(
        browserPackage = browserPackage,
        onBack = { leaveBlockedSiteBack() },
        onHome = { leaveBlockedSiteHome() },
      )
      recordBlockedSiteStats(
        browserPackage = browserPackage,
        previousPackage = previousPackage,
        overlayAttached = adultSiteOverlay.shownPackage() == browserPackage,
        outcome = EnforcementStatsOutcome.SUCCESS,
      )
    } else if (adultSiteOverlay.shownPackage() == browserPackage) {
      adultSiteOverlay.hide()
      resetStatsDedupe(EnforcementReason.BLOCKED_SITE)
    }
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
        result.add(BrowserNodeSignal(viewId, node.text?.toString().orEmpty(), true))
      }
      if (depth >= MAX_BROWSER_DEPTH) continue
      for (index in 0 until node.childCount) node.getChild(index)?.let { pending.add(Pending(it, depth + 1)) }
    }
    return result
  }

  /** Keep the overlay in place until Back produces a verified non-blocked URL. */
  private fun leaveBlockedSiteBack() {
    val browserPackage = adultSiteOverlay.shownPackage() ?: return
    if (!canRunEnforcementAction() || !adultSiteStore.enabled) {
      clearAdultSiteEnforcement()
      return
    }
    performGlobalAction(GLOBAL_ACTION_BACK)
    browserHandler.removeCallbacksAndMessages(null)
    browserHandler.postDelayed({
      if (hasActiveProtection() && adultSiteOverlay.shownPackage() == browserPackage) {
        handleBrowserEvent(browserPackage)
      }
    }, BROWSER_RECHECK_MS)
  }

  private fun leaveBlockedSiteHome() {
    adultSiteOverlay.hide()
    if (canRunEnforcementAction()) performGlobalAction(GLOBAL_ACTION_HOME)
  }

  private fun clearAdultSiteEnforcement() {
    browserHandler.removeCallbacksAndMessages(null)
    if (::adultSiteOverlay.isInitialized) adultSiteOverlay.hide()
    resetStatsDedupe(EnforcementReason.BLOCKED_SITE)
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
    private const val LIMIT_TOAST_INTERVAL_MS = 30_000L
    private const val BROWSER_RECHECK_MS = 350L
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
