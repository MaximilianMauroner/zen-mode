package com.maxmauroner.zenguard

import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
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
  private val instagramStateMachine = InstagramGuardStateMachine()
  private val instagramDebugTrace = InstagramDebugTrace()
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
  private var lastExternalPackage: String? = null
  private val usageTracker = AppUsageTracker()
  private val usageHandler = Handler(Looper.getMainLooper())
  private var lastLimitToastAtMs = 0L
  private var currentForegroundPackage: String? = null

  /**
   * Banks foreground time every [USAGE_TICK_MS] so a long uninterrupted session
   * still trips its budget. Window events alone are not enough: an app the user
   * simply sits in emits nothing.
   */
  private val usageTicker = object : Runnable {
    override fun run() {
      usageTracker.tick(SystemClock.elapsedRealtime())?.let(::bankUsage)
      enforceAppLimit(currentForegroundPackage)
      enforceRollingLimit(currentForegroundPackage)
      enforceIntentSession()
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
    usageHandler.postDelayed(usageTicker, USAGE_TICK_MS)
    instagramOverlay = InstagramBlockerOverlay(
      service = this,
      onLeave = {
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
      },
      onContinue = {
        val continued = instagramStateMachine.continueReels(SystemClock.elapsedRealtime(), preferences.instagramSettings())
        if (continued) dailyTally.recordContinue(System.currentTimeMillis())
        continued
      },
    )
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (!::preferences.isInitialized || event == null) return
    if (!preferences.protectionEnabled) {
      usageTracker.reset()
      currentForegroundPackage = null
      lastExternalPackage = null
      intentSessions.reset()
      if (::intentOverlay.isInitialized) intentOverlay.hide()
      clearInstagramEnforcement()
      return
    }

    trackForegroundApp(event)
    // The blocker is an accessibility overlay owned by this package. Ignore its own focus and
    // content events; treating them as an external app would immediately remove the blocker.
    if (event.packageName?.toString() == packageName) return

    when (event.packageName?.toString()) {
      YOUTUBE_PACKAGE -> {
        instagramNavigationSuppressedUntilMs = 0L
        clearInstagramEnforcement()
        handleYouTubeEvent()
      }
      INSTAGRAM_PACKAGE -> handleInstagramEvent(event)
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
    intentSessions.grant(packageName, minutes, SystemClock.elapsedRealtime())
    if (intentOverlay.shownPackage() == packageName) intentOverlay.hide()
  }

  /** Leaves without starting a session. */
  private fun leaveIntent() {
    intentOverlay.hide()
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

    val nowMs = System.currentTimeMillis()
    preferences.recordEvent(nowMs)

    val root = rootInActiveWindow ?: return
    val result = ShortsDetector.detect(snapshot(root))
    if (!result.isShortsViewer) {
      stateMachine.next(false, SystemClock.elapsedRealtime())
      return
    }

    preferences.recordDetection(nowMs, result.reason)
    if (preferences.observationMode) return

    when (stateMachine.next(true, SystemClock.elapsedRealtime())) {
      EnforcementAction.BACK -> {
        performGlobalAction(GLOBAL_ACTION_BACK)
        Toast.makeText(this, R.string.zen_guard_shorts_blocked, Toast.LENGTH_SHORT).show()
      }
      EnforcementAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
      EnforcementAction.NONE -> Unit
    }
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
      clearInstagramEnforcement()
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
      instagramDebugTrace.record(
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
      instagramDebugTrace.record(
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
      instagramDebugTrace.record(
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
    val debugState = instagramStateMachine.debugState(nowMs)
    instagramDebugTrace.record(
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
            homeUsedMinutes = (debugState.homeElapsedMs / 60_000L).toInt(),
            homeAllowanceMinutes = preferences.instagramHomeMinutes,
            stoppedToday = tally.stopped,
            continuedToday = tally.continued,
            resetsInMs = millisUntilLocalMidnight(wallNow),
          ),
          debugInfo = InstagramBlockerDebugInfo(
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
          ),
        )
      }
      InstagramGuardAction.None -> {
        if (instagramBlockReason != null) {
          instagramBlockReason = null
          instagramOverlay.hide()
        }
      }
    }
  }

  override fun onInterrupt() {
    clearInstagramEnforcement(preserveHomeSession = true)
  }

  private fun openInstagramMessages() {
    navigationHandler.removeCallbacksAndMessages(null)
    instagramNavigationSuppressedUntilMs =
      SystemClock.elapsedRealtime() + NAVIGATION_SUPPRESSION_MS
    val movedBack = performGlobalAction(GLOBAL_ACTION_BACK)
    if (!movedBack) InstagramIntentHelper.openMain(this)
    navigationHandler.postDelayed({ ensureMessagesVisible(0) }, NAVIGATION_RETRY_MS)
  }

  override fun onDestroy() {
    navigationHandler.removeCallbacksAndMessages(null)
    usageHandler.removeCallbacksAndMessages(null)
    if (::intentOverlay.isInitialized) intentOverlay.hide()
    clearInstagramEnforcement()
    super.onDestroy()
  }

  private val isScreenInteractive: Boolean
    get() = getSystemService(PowerManager::class.java)?.isInteractive == true

  private fun clearInstagramEnforcement(preserveHomeSession: Boolean = false) {
    if (preserveHomeSession) {
      instagramStateMachine.onAppBackground()
    } else {
      instagramStateMachine.onSurfaceLost()
    }
    instagramBlockReason = null
    if (::instagramOverlay.isInitialized) instagramOverlay.hide()
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
    private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    private const val INSTAGRAM_PACKAGE = "com.instagram.android"
    private const val MAX_NODES = 500
    private const val MAX_DEPTH = 18
    private const val MAX_PARENT_CHAIN = 8
    private const val NAVIGATION_RETRY_MS = 250L
    private const val NAVIGATION_SUPPRESSION_MS = 3_000L
    private const val MAIN_LAUNCH_ATTEMPT = 3
    private const val MAX_NAVIGATION_ATTEMPTS = 12
    private const val USAGE_TICK_MS = 15_000L
    private const val LIMIT_TOAST_INTERVAL_MS = 30_000L

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
