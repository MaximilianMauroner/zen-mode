package com.maxmauroner.zenguard

import android.accessibilityservice.AccessibilityService
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

  override fun onServiceConnected() {
    super.onServiceConnected()
    preferences = ZenGuardPreferences(this)
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
        instagramStateMachine.continueReels(SystemClock.elapsedRealtime(), preferences.instagramSettings())
      },
    )
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (!::preferences.isInitialized || event == null) return
    if (!preferences.protectionEnabled) {
      clearInstagramEnforcement()
      return
    }
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
        instagramBlockReason = action.reason
        instagramOverlay.show(
          action = action,
          homeMinutes = preferences.instagramHomeMinutes,
          reelsMinutes = preferences.instagramReelsMinutes,
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
