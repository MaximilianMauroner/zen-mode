package com.maxmauroner.zenguard

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.ArrayDeque

class ZenGuardAccessibilityService : AccessibilityService() {
  private lateinit var preferences: ZenGuardPreferences
  private val stateMachine = EnforcementStateMachine()
  private val instagramStateMachine = InstagramGuardStateMachine()
  private lateinit var instagramOverlay: InstagramBlockerOverlay
  private var instagramBlockReason: InstagramBlockReason? = null

  override fun onServiceConnected() {
    super.onServiceConnected()
    preferences = ZenGuardPreferences(this)
    instagramOverlay = InstagramBlockerOverlay(
      service = this,
      onLeave = {
        val action = if (instagramBlockReason == InstagramBlockReason.HOME_LIMIT) {
          GLOBAL_ACTION_HOME
        } else {
          GLOBAL_ACTION_BACK
        }
        instagramStateMachine.leaveBlockedSurface()
        instagramBlockReason = null
        instagramOverlay.hide()
        performGlobalAction(action)
      },
      onContinue = {
        instagramStateMachine.continueReels(SystemClock.elapsedRealtime(), preferences.instagramSettings())
      },
    )
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (!::preferences.isInitialized || event == null) return
    if (!preferences.protectionEnabled) return

    when (event.packageName?.toString()) {
      YOUTUBE_PACKAGE -> handleYouTubeEvent()
      INSTAGRAM_PACKAGE -> handleInstagramEvent(event)
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

  private fun handleInstagramEvent(event: AccessibilityEvent) {
    val root = rootInActiveWindow ?: return
    val detection = InstagramDetector.detect(snapshot(root))
    if (detection.surface == InstagramSurface.UNKNOWN) return

    preferences.recordInstagramDetection(System.currentTimeMillis(), detection)
    if (preferences.instagramObservationMode) return

    when (
      val action = instagramStateMachine.next(
        surface = detection.surface,
        isScrollEvent = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED,
        nowMs = SystemClock.elapsedRealtime(),
        settings = preferences.instagramSettings(),
      )
    ) {
      is InstagramGuardAction.ShowBlocker -> {
        instagramBlockReason = action.reason
        instagramOverlay.show(action, preferences.instagramReelsMinutes)
      }
      InstagramGuardAction.None -> Unit
    }
  }

  override fun onInterrupt() {
    if (::instagramOverlay.isInitialized) instagramOverlay.hide()
  }

  override fun onDestroy() {
    if (::instagramOverlay.isInitialized) instagramOverlay.hide()
    super.onDestroy()
  }

  private fun snapshot(root: AccessibilityNodeInfo): List<NodeSignal> {
    data class PendingNode(val node: AccessibilityNodeInfo, val depth: Int)

    val result = ArrayList<NodeSignal>(128)
    val queue = ArrayDeque<PendingNode>()
    queue.add(PendingNode(root, 0))

    while (queue.isNotEmpty() && result.size < MAX_NODES) {
      val (node, depth) = queue.removeFirst()
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
  }
}
