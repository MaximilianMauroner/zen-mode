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

  override fun onServiceConnected() {
    super.onServiceConnected()
    preferences = ZenGuardPreferences(this)
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (!::preferences.isInitialized || event?.packageName?.toString() != YOUTUBE_PACKAGE) return
    if (!preferences.protectionEnabled) return

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

  override fun onInterrupt() = Unit

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
    private const val MAX_NODES = 500
    private const val MAX_DEPTH = 18
  }
}
