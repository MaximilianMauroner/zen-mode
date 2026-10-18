package com.maxmauroner.zenguard

import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

internal fun snapshotNodeSignals(root: AccessibilityNodeInfo): List<NodeSignal> {
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

private const val MAX_NODES = 500
private const val MAX_DEPTH = 36
