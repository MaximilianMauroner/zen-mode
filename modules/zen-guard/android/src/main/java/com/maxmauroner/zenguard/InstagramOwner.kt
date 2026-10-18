package com.maxmauroner.zenguard

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

internal enum class InstagramPause { COVERED, DEPARTED }

internal class InstagramOwner(
  private val service: AccessibilityService,
  private val stats: InterventionStats,
  private val canEnforce: () -> Boolean,
) {
  private lateinit var preferences: ZenGuardPreferences
  private lateinit var homeFeedStatusStore: HomeFeedStatusStore
  private var storageAvailable = true
  private val policy = InstagramGuardStateMachine()
  private val debugTrace = if (BuildConfig.DEBUG) InstagramDebugTrace() else null
  private val navigationHandler by lazy { Handler(Looper.getMainLooper()) }
  private val reelsDeadline by lazy { DeadlineTask(Handler(Looper.getMainLooper()), decide = ::checkReelsDeadline) }
  private lateinit var intervention: InstagramIntervention
  private var navigationSuppressedUntilMs = 0L
  private lateinit var dailyTally: DailyTally

  fun createTally() {
    dailyTally = DailyTally(service)
  }

  fun restore(preferences: ZenGuardPreferences, homeFeedStatusStore: HomeFeedStatusStore, nowElapsedMs: Long) {
    this.preferences = preferences
    this.homeFeedStatusStore = homeFeedStatusStore
    val storedInstagram = homeFeedStatusStore.instagram(nowElapsedMs)
    storageAvailable = storedInstagram.storageState == HomeFeedStorageState.AVAILABLE
    policy.restore(
      HomeFeedRuntimeState(
        usedMs = storedInstagram.usedMs,
        blockedUntilElapsedMs = storedInstagram.blockedUntilElapsedMs,
        usageState = storedInstagram.usageState,
        lockoutState = storedInstagram.lockoutState,
        storageState = storedInstagram.storageState,
        capturedAtElapsedMs = nowElapsedMs,
      ),
    )
  }

  fun start() {
    intervention = InstagramIntervention(
      service = service,
      readStats = ::blockerStats,
      canEnforce = canEnforce,
      recordStop = { dailyTally.recordStop(System.currentTimeMillis()) },
      recordAttachment = { reason ->
        reelsDeadline.cancel()
        stats.record(reason, EnforcementStatsOutcome.SUCCESS)
      },
      leave = { reason ->
        policy.leaveBlockedSurface()
        when (reason) {
          InstagramBlockReason.HOME_LIMIT -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
          InstagramBlockReason.EXPLORE,
          InstagramBlockReason.REELS_ENTRY,
          InstagramBlockReason.REELS_SWIPE,
          InstagramBlockReason.REELS_WINDOW_EXPIRED,
          -> openMessages()
        }
      },
      openMessages = {
        policy.leaveBlockedSurface()
        openMessages()
      },
      continueReels = {
        policy.continueReels(SystemClock.elapsedRealtime(), preferences.instagramSettings()).also { continued ->
          if (continued) {
            dailyTally.recordContinue(System.currentTimeMillis())
            scheduleReelsDeadline()
          }
        }
      },
    )
  }

  fun pause(cause: InstagramPause = InstagramPause.COVERED) {
    if (cause == InstagramPause.DEPARTED) navigationSuppressedUntilMs = 0L
    clearEnforcement(preserveHomeSession = true)
  }

  fun disable() {
    cancelNavigation()
    navigationSuppressedUntilMs = 0L
    clearEnforcement()
  }

  fun enterSettings() {
    cancelNavigation()
    navigationSuppressedUntilMs = 0L
    clearEnforcement(preserveHomeSession = true)
  }

  fun screenOff(nowElapsedMs: Long) {
    reelsDeadline.cancel()
    policy.onAppBackground(nowElapsedMs, preferences.instagramSettings())
  }

  fun cancelNavigation() {
    navigationHandler.removeCallbacksAndMessages(null)
  }

  fun destroy(protectionActive: Boolean) {
    clearEnforcement(preserveHomeSession = protectionActive)
  }

  /**
   * Keyboard, system-bar, and other transient windows also emit accessibility events while
   * Instagram stays in the foreground. Do not treat those events as an app switch. Clear the
   * guard only after a window event confirms that no Instagram window remains visible.
   */
  fun observeOtherApp(event: AccessibilityEvent) {
    if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
      event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
    ) {
      return
    }

    val instagramWindowVisible = service.windows.any { window ->
      window.root?.packageName?.toString() == INSTAGRAM_PACKAGE
    }
    if (!instagramWindowVisible) {
      pause(InstagramPause.DEPARTED)
    }
  }

  fun observe(event: AccessibilityEvent?, observedRoot: AccessibilityNodeInfo? = service.rootInActiveWindow) {
    val root = observedRoot ?: return
    // While our accessibility overlay is attached, Android can still deliver queued Instagram
    // events even though service.rootInActiveWindow points at the overlay (or another transient window).
    // Never classify that tree as Instagram UNKNOWN and erase the DM provenance underneath it.
    if (root.packageName?.toString() != INSTAGRAM_PACKAGE) return
    val nodes = snapshotNodeSignals(root)
    val detection = InstagramDetector.detect(nodes)
    if (detection.surface == InstagramSurface.UNKNOWN) {
      debugTrace?.record(
        event = event,
        nodes = nodes,
        detection = detection,
        observationMode = preferences.instagramObservationMode,
        exploreBlocked = preferences.instagramExploreBlocked,
        action = null,
      )
      policy.onTransientSurfaceLost(SystemClock.elapsedRealtime())
      return
    }

    preferences.recordInstagramDetection(System.currentTimeMillis(), detection)

    val nowMs = SystemClock.elapsedRealtime()
    if (nowMs < navigationSuppressedUntilMs) {
      if (detection.surface == InstagramSurface.DIRECT_MESSAGES && isMessagesInbox(nodes)) {
        navigationSuppressedUntilMs = 0L
        intervention.end()
      }
      debugTrace?.record(
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
      debugTrace?.record(
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
        storageAvailable = storageAvailable,
        surface = detection.surface,
        isForeground = isScreenInteractive,
      )
    ) {
      if (homeFeedStatusStore.recoverInstagram(nowMs)) {
        storageAvailable = true
        policy.recoverStorage(nowMs)
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
    val action = policy.next(guardInput, preferences.instagramSettings())
    debugTrace?.record(
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
        intervention.observe(
          action = action,
          reelsMinutes = preferences.instagramReelsMinutes,
          debugInfo = if (BuildConfig.DEBUG) {
            val debugState = policy.debugState(nowMs)
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
      InstagramGuardAction.None -> intervention.end()
    }
    publishHomeStatus()
  }

  private fun blockerStats(): InstagramBlockerStats {
    val wallNow = System.currentTimeMillis()
    val tally = dailyTally.counts(wallNow)
    return InstagramBlockerStats(
      home = policy.homeRuntimeState(SystemClock.elapsedRealtime()),
      homeAllowanceMinutes = preferences.instagramHomeMinutes,
      stoppedToday = tally.stopped,
      continuedToday = tally.continued,
      dailyCountsResetInMs = millisUntilLocalMidnight(wallNow),
    )
  }

  private fun openMessages() {
    if (!canEnforce()) return
    navigationHandler.removeCallbacksAndMessages(null)
    navigationSuppressedUntilMs =
      SystemClock.elapsedRealtime() + NAVIGATION_SUPPRESSION_MS
    val movedBack = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    if (!movedBack) InstagramIntentHelper.openMain(service)
    navigationHandler.postDelayed({ ensureMessagesVisible(0) }, NAVIGATION_RETRY_MS)
  }

  private val isScreenInteractive: Boolean
    get() = service.getSystemService(PowerManager::class.java)?.isInteractive == true

  /**
   * An untouched Reel emits no accessibility events, so the window granted by Continue would
   * otherwise end only at the next swipe. Recheck the live tree when the window ends.
   */
  private fun scheduleReelsDeadline() {
    val nowMs = SystemClock.elapsedRealtime()
    val remainingMs = policy.reelsWindowRemainingMs(nowMs)
    if (remainingMs == null) reelsDeadline.cancel() else reelsDeadline.scheduleAt(nowMs + remainingMs)
  }

  private fun checkReelsDeadline(): DeadlineDecision {
    if (policy.reelsWindowRemainingMs(SystemClock.elapsedRealtime()) == null) return DeadlineDecision.Cancel
    if (!canEnforce() || preferences.instagramObservationMode || !isScreenInteractive) {
      return DeadlineDecision.Cancel
    }
    if (!intervention.isShowing) observe(null)
    return when {
      intervention.isShowing -> DeadlineDecision.Complete
      policy.reelsWindowRemainingMs(SystemClock.elapsedRealtime()) == 0L ->
        DeadlineDecision.RetryAfter(INSTAGRAM_REELS_DEADLINE_RETRY_MS)
      else -> DeadlineDecision.Cancel
    }
  }

  private fun clearEnforcement(preserveHomeSession: Boolean = false) {
    reelsDeadline.cancel()
    if (preserveHomeSession) {
      policy.onAppBackground(SystemClock.elapsedRealtime(), preferences.instagramSettings())
    } else {
      policy.onSurfaceLost()
    }
    if (::intervention.isInitialized) intervention.end()
    publishHomeStatus()
  }

  fun publishHomeStatus() {
    if (!::homeFeedStatusStore.isInitialized) return
    val nowElapsedMs = SystemClock.elapsedRealtime()
    val runtime = policy.homeRuntimeState(nowElapsedMs)
    if (homeFeedStatusStore.recordInstagram(runtime)) return

    storageAvailable = false
    policy.markStorageUnavailable()
  }

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

  private fun isDmThreadVisible(nodes: List<NodeSignal>): Boolean {
    val ids = nodes.map { it.viewId.lowercase() }
    val hasThreadContent = ids.any { it.containsAny(DM_THREAD_CONTENT_IDS) }
    val hasThreadChrome = ids.any { it.containsAny(DM_THREAD_CHROME_IDS) }
    return hasThreadContent && hasThreadChrome
  }

  private fun ensureMessagesVisible(attempt: Int) {
    if (!canEnforce()) return
    if (!isScreenInteractive || attempt > MAX_NAVIGATION_ATTEMPTS) return

    val root = service.rootInActiveWindow
    val rootIsInstagram = root?.packageName?.toString() == INSTAGRAM_PACKAGE
    if (rootIsInstagram) {
      val nodes = snapshotNodeSignals(root)
      val detection = InstagramDetector.detect(nodes)
      if (detection.surface == InstagramSurface.DIRECT_MESSAGES && isMessagesInbox(nodes)) {
        navigationSuppressedUntilMs = 0L
        return
      }
      if (clickDirectTab(root)) {
        navigationHandler.postDelayed({ ensureMessagesVisible(attempt + 1) }, NAVIGATION_RETRY_MS)
        return
      }

      if (detection.surface == InstagramSurface.DIRECT_MESSAGES && isDmThreadVisible(nodes) ||
        detection.surface == InstagramSurface.REELS_VIEWER
      ) {
        if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
          navigationHandler.postDelayed({ ensureMessagesVisible(attempt + 1) }, NAVIGATION_RETRY_MS)
          return
        }
      }
    }

    if (attempt == MAIN_LAUNCH_ATTEMPT) {
      InstagramIntentHelper.openMain(service)
    }
    navigationHandler.postDelayed({ ensureMessagesVisible(attempt + 1) }, NAVIGATION_RETRY_MS)
  }

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

  private companion object {
    private const val INSTAGRAM_PACKAGE = "com.instagram.android"
    private const val MAX_DEPTH = 36
    private const val MAX_PARENT_CHAIN = 8
    private const val NAVIGATION_RETRY_MS = 250L
    private const val NAVIGATION_SUPPRESSION_MS = 3_000L
    private const val MAIN_LAUNCH_ATTEMPT = 3
    private const val MAX_NAVIGATION_ATTEMPTS = 12
    private const val INSTAGRAM_REELS_DEADLINE_RETRY_MS = 1_000L

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
