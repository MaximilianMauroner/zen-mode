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

class ZenGuardAccessibilityService : AccessibilityService() {
  private lateinit var preferences: ZenGuardPreferences
  private val stateMachine = EnforcementStateMachine()
  private val xStateMachine = XGuardStateMachine()
  private lateinit var xOverlay: XBreakOverlay
  private val xHandler = Handler(Looper.getMainLooper())
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
  private val navigationHandler = Handler(Looper.getMainLooper())
  private lateinit var instagramOverlay: InstagramBlockerOverlay
  private var instagramBlockReason: InstagramBlockReason? = null
  private var instagramNavigationSuppressedUntilMs = 0L
  private lateinit var appLimits: AppLimitStore
  private lateinit var dailyTally: DailyTally
  private lateinit var intentStore: IntentAppStore
  private lateinit var rollingLimits: RollingLimitStore
  private val intentSessions = IntentSessionTracker()
  private lateinit var intentOverlay: IntentOverlay
  private lateinit var adultSiteStore: AdultSiteRuleStore
  private lateinit var adultSiteOverlay: AdultSiteBlockerOverlay
  private lateinit var homeFeedStatusStore: HomeFeedStatusStore
  private val browserHandler = Handler(Looper.getMainLooper())
  private var lastExternalPackage: String? = null
  private val usageTracker = AppUsageTracker()
  private val usageHandler = Handler(Looper.getMainLooper())
  private var lastLimitToastAtMs = 0L
  private var currentForegroundPackage: String? = null
  private var screenReceiverRegistered = false
  private val screenStateReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action != Intent.ACTION_SCREEN_OFF || !hasActiveProtection()) return
      val nowMs = SystemClock.elapsedRealtime()
      xStateMachine.pause(nowMs, xSettings())
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
      if (hasActiveProtection()) {
        usageTracker.tick(SystemClock.elapsedRealtime())?.let(::bankUsage)
        enforceAppLimit(currentForegroundPackage)
        enforceRollingLimit(currentForegroundPackage)
        enforceIntentSession()
      } else {
        clearInactiveProtection()
      }
      usageHandler.postDelayed(this, USAGE_TICK_MS)
    }
  }

  override fun onServiceConnected() {
    super.onServiceConnected()
    preferences = ZenGuardPreferences(this)
    appLimits = AppLimitStore(this)
    dailyTally = DailyTally(this)
    intentStore = IntentAppStore(this)
    rollingLimits = RollingLimitStore(this)
    intentOverlay = IntentOverlay(this)
    adultSiteStore = AdultSiteRuleStore(this)
    adultSiteOverlay = AdultSiteBlockerOverlay(this)
    homeFeedStatusStore = HomeFeedStatusStore(this)
    publishXHomeStatus()
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
      if (hasActiveProtection()) {
        xStateMachine.leaveBlockedSurface()
        xOverlay.hide()
        performGlobalAction(GLOBAL_ACTION_HOME)
      } else {
        clearInactiveProtection()
      }
    }
    xHandler.postDelayed(xTicker, 1_000L)
    instagramOverlay = InstagramBlockerOverlay(
      service = this,
      onLeave = {
        if (hasActiveProtection()) {
          val reason = instagramBlockReason
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
        } else {
          clearInactiveProtection()
        }
      },
      onOpenMessages = {
        if (hasActiveProtection()) {
          instagramStateMachine.leaveBlockedSurface()
          instagramBlockReason = null
          instagramOverlay.hide()
          openInstagramMessages()
        } else {
          clearInactiveProtection()
        }
      },
      onContinue = {
        if (hasActiveProtection()) {
          val continued = instagramStateMachine.continueReels(SystemClock.elapsedRealtime(), preferences.instagramSettings())
          if (continued) dailyTally.recordContinue(System.currentTimeMillis())
          continued
        } else {
          clearInactiveProtection()
          false
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

    trackForegroundApp(event)
    // The blocker is an accessibility overlay owned by this package. Ignore its own focus and
    // content events; treating them as an external app would immediately remove the blocker.
    val eventPackage = event.packageName?.toString()
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
        handleYouTubeEvent()
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
    usageTracker.onForeground(packageName, SystemClock.elapsedRealtime())?.let(::bankUsage)
    if (packageName != YOUTUBE_PACKAGE && packageName != this.packageName) stateMachine.reset()
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
    if (packageName == null || packageName == this.packageName) return
    if (!appLimits.isOverBudget(packageName, System.currentTimeMillis())) return

    performGlobalAction(GLOBAL_ACTION_HOME)
    usageTracker.reset()
    currentForegroundPackage = null

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
    if (packageName == null || packageName == this.packageName) return
    if (!::rollingLimits.isInitialized) return
    if (!rollingLimits.isOver(packageName, System.currentTimeMillis())) return

    performGlobalAction(GLOBAL_ACTION_HOME)
    usageTracker.reset()
    currentForegroundPackage = null
    lastExternalPackage = null

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
    } else {
      if (showingForThis && !intentOverlay.isCooldown()) return
      intentOverlay.showAsk(
        packageName,
        labelFor(packageName),
        rule.sessionMinutes,
        onStart = { grantIntentSession(packageName, rule.sessionMinutes) },
        onLeave = { leaveIntent() },
      )
    }
  }

  /** Starts one timed visit and lets the app through. */
  private fun grantIntentSession(packageName: String, minutes: Int) {
    if (!hasActiveProtection()) {
      clearInactiveProtection()
      return
    }
    intentSessions.grant(packageName, minutes, SystemClock.elapsedRealtime())
    if (intentOverlay.shownPackage() == packageName) intentOverlay.hide()
  }

  /** Leaves without starting a session. */
  private fun leaveIntent() {
    intentOverlay.hide()
    if (!hasActiveProtection()) {
      clearInactiveProtection()
      return
    }
    performGlobalAction(GLOBAL_ACTION_HOME)
  }

  /**
   * Ends a visit whose minutes ran out while its app is still in front.
   * Runs on the usage tick, so an app the user simply sits in is still
   * caught. Reopening starts the downtime overlay, not a new question.
   */
  private fun enforceIntentSession() {
    if (!::intentStore.isInitialized || !::intentOverlay.isInitialized) return
    val packageName = lastExternalPackage ?: return
    if (intentSessions.isActive(packageName, SystemClock.elapsedRealtime())) return
    if (!intentSessions.end(packageName)) return

    intentStore.recordSessionEnd(packageName, System.currentTimeMillis())
    intentOverlay.hide()
    performGlobalAction(GLOBAL_ACTION_HOME)
    lastExternalPackage = null
    Toast.makeText(this, R.string.zen_guard_intent_time_up, Toast.LENGTH_SHORT).show()
  }

  /** Human-readable app name, falling back to the package when unknown. */
  private fun labelFor(packageName: String): String = try {
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
  } catch (_: PackageManager.NameNotFoundException) {
    packageName
  }

  private fun handleYouTubeEvent() {
    val root = rootInActiveWindow ?: return
    if (root.packageName?.toString() != YOUTUBE_PACKAGE) return
    val nowMs = System.currentTimeMillis()
    preferences.recordEvent(nowMs)
    val result = ShortsDetector.detect(snapshot(root))
    if (!result.isShortsViewer) {
      stateMachine.reset()
      return
    }
    preferences.recordDetection(nowMs, result.reason)
    if (preferences.observationMode || !preferences.shortsEnabled) {
      stateMachine.reset()
      return
    }
    // The pager's collection row stays constant during playback and changes with the video.
    // Titles, like counts, comments, and playback progress must never consume the allowance.
    val page = root.findAccessibilityNodeInfosByViewId("$YOUTUBE_PACKAGE:id/reel_player_page_container")
      .firstOrNull { it.isVisibleToUser }
    val pageIndex = page?.collectionItemInfo?.rowIndex
    if (stateMachine.next(true, pageIndex, SystemClock.elapsedRealtime()) == EnforcementAction.LEAVE_SHORTS) {
      // Back/Home can put Premium Shorts into PiP. Use YouTube's own Home tab instead.
      val tabs = root.findAccessibilityNodeInfosByViewId("$YOUTUBE_PACKAGE:id/pivot_bar").firstOrNull()
      val homeTab = tabs?.getChild(0)?.getChild(0)
      if (homeTab?.isClickable == true && homeTab.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
        Toast.makeText(this, R.string.zen_guard_shorts_blocked, Toast.LENGTH_SHORT).show()
      }
    }
  }

  private fun clearXEnforcement(preserveHomeLockout: Boolean = false) {
    if (preserveHomeLockout) xStateMachine.pause(SystemClock.elapsedRealtime(), xSettings()) else xStateMachine.reset()
    if (::xOverlay.isInitialized) xOverlay.hide()
    publishXHomeStatus()
  }

  /** One-second ticks count a still Home feed; events identify only the full-screen video pager. */
  private fun handleXEvent(event: AccessibilityEvent?) {
    if (!hasActiveProtection()) { clearInactiveProtection(); return }
    if (!isScreenInteractive) { xStateMachine.pause(); return }
    val root = rootInActiveWindow ?: run { xStateMachine.pause(); return }
    if (root.packageName?.toString() != X_PACKAGE) {
      if (xOverlay.isShowing && windows.any { it.root?.packageName?.toString() == X_PACKAGE }) return
      xStateMachine.pause()
      return
    }
    val surface = XDetector.detect(snapshot(root))
    val pager = if (surface == XSurface.VIDEO) findXNode(root) { isXVideoPager(it) } else null
    if (surface == XSurface.HOME) preferences.recordXSignal(1)
    if (pager != null) preferences.recordXSignal(2)
    if (preferences.xObservationMode) { clearXEnforcement(); return }
    val source = event?.source
    val advanced = pager != null && event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
      source != null && isXVideoPager(source) && event.scrollY > 0
    val action = xStateMachine.next(surface, SystemClock.elapsedRealtime(), xSettings(), advanced)
    when (action) {
      XAction.HOME_BREAK -> xOverlay.show(preferences.xHomeMinutes)
      XAction.LEAVE_VIDEO -> {
        xOverlay.hide()
        // X's own Back control closes its viewer without docking playback into PiP.
        val back = findXNode(root) { it.contentDescription?.toString() == "Back" }
        var target = back
        repeat(MAX_PARENT_CHAIN) {
          val candidate = target ?: return@repeat
          if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Toast.makeText(this, R.string.zen_guard_x_video_blocked, Toast.LENGTH_SHORT).show()
            return
          }
          target = candidate.parent
        }
      }
      XAction.NONE -> if (surface != XSurface.UNKNOWN) xOverlay.hide()
    }
    if (action == XAction.HOME_BREAK) publishXHomeStatus()
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
      }
      InstagramGuardAction.None -> {
        if (instagramBlockReason != null) {
          instagramBlockReason = null
          instagramOverlay.hide()
        }
      }
    }
    if (action is InstagramGuardAction.ShowBlocker) publishInstagramHomeStatus()
  }

  override fun onInterrupt() {
    clearAdultSiteEnforcement()
    clearXEnforcement(preserveHomeLockout = true)
    stateMachine.reset()
    clearInstagramEnforcement(preserveHomeSession = true)
  }

  private fun openInstagramMessages() {
    if (!hasActiveProtection()) {
      clearInactiveProtection()
      return
    }
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
    clearXEnforcement()
    navigationHandler.removeCallbacksAndMessages(null)
    usageHandler.removeCallbacksAndMessages(null)
    if (::intentOverlay.isInitialized) intentOverlay.hide()
    clearInstagramEnforcement()
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
    publishInstagramHomeStatus()
  }

  private fun publishInstagramHomeStatus() {
    if (!::homeFeedStatusStore.isInitialized) return
    val nowElapsedMs = SystemClock.elapsedRealtime()
    val runtime = instagramStateMachine.homeRuntimeState(nowElapsedMs)
    homeFeedStatusStore.recordInstagram(
      usedMs = runtime.usedMs,
      blockedUntilElapsedMs = runtime.blockedUntilElapsedMs,
    )
  }

  private fun publishXHomeStatus() {
    if (!::homeFeedStatusStore.isInitialized) return
    val nowElapsedMs = SystemClock.elapsedRealtime()
    val runtime = xStateMachine.homeRuntimeState(nowElapsedMs)
    homeFeedStatusStore.recordX(
      usedMs = runtime.usedMs,
      blockedUntilElapsedMs = runtime.blockedUntilElapsedMs,
    )
  }

  private fun xSettings() = XSettings(
    homeEnabled = preferences.xHomeEnabled,
    videosEnabled = preferences.xVideosEnabled,
    homeAllowanceMs = preferences.xHomeMinutes * 60_000L,
  )

  private fun hasActiveProtection(): Boolean =
    ::preferences.isInitialized && preferences.hasCurrentConsent && preferences.protectionEnabled

  /** Clear every in-memory action path without inspecting another app's screen. */
  private fun clearInactiveProtection() {
    clearAdultSiteEnforcement()
    stateMachine.reset()
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

  /** Reads text only from exact address-bar nodes; arbitrary browser page text is never copied. */
  private fun handleBrowserEvent(browserPackage: String) {
    if (!::adultSiteStore.isInitialized || !::adultSiteOverlay.isInitialized) return
    if (!adultSiteStore.enabled) {
      clearAdultSiteEnforcement()
      return
    }
    val root = browserRoot(browserPackage) ?: return
    val detection = BrowserUrlDetector.detect(browserPackage, browserAddressBars(root, browserPackage)) ?: return
    adultSiteStore.recordBrowserSignal(detection.browserMask)
    if (AdultSitePolicy.isBlocked(detection.host, adultSiteStore.customHosts())) {
      adultSiteOverlay.show(
        browserPackage = browserPackage,
        onBack = { leaveBlockedSiteBack() },
        onHome = { leaveBlockedSiteHome() },
      )
    } else if (adultSiteOverlay.shownPackage() == browserPackage) {
      adultSiteOverlay.hide()
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
    if (!hasActiveProtection() || !adultSiteStore.enabled) {
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
    if (hasActiveProtection()) performGlobalAction(GLOBAL_ACTION_HOME) else clearInactiveProtection()
  }

  private fun clearAdultSiteEnforcement() {
    browserHandler.removeCallbacksAndMessages(null)
    if (::adultSiteOverlay.isInitialized) adultSiteOverlay.hide()
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
    if (!hasActiveProtection()) {
      clearInactiveProtection()
      return
    }
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
