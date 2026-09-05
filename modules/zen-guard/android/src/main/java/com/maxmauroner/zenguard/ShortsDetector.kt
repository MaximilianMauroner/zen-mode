package com.maxmauroner.zenguard

internal data class NodeSignal(
  val viewId: String = "",
  val text: String = "",
  val description: String = "",
  val selected: Boolean = false,
)

internal data class DetectionResult(
  val isShortsViewer: Boolean,
  val reason: String = "unknown",
)

/**
 * Conservative classifier for the full-screen YouTube Shorts viewer.
 *
 * A Shorts shelf or navigation label must never be enough to trigger enforcement. We act only on
 * viewer-specific resource identifiers, or on a selected Shorts tab corroborated by player actions.
 * Unknown YouTube versions therefore fail open.
 */
internal object ShortsDetector {
  private val viewerIdFragments = listOf(
    "reel_recycler",
    "reel_player_page_container",
    "reel_watch_fragment",
    "shorts_video_container",
    "reel_progress_bar",
  )

  private val ordinaryWatchIdFragments = listOf(
    "watch_player",
    "player_control",
  )

  private val playerActionDescriptions = listOf(
    "comments",
    "share",
    "like this video",
    "dislike this video",
  )

  fun detect(nodes: List<NodeSignal>): DetectionResult {
    val ids = nodes.map { it.viewId.lowercase() }
    val hasViewerId = ids.any { id -> viewerIdFragments.any(id::contains) }
    val hasOrdinaryWatchId = ids.any { id -> !id.substringAfterLast('/').startsWith("reel_") && ordinaryWatchIdFragments.any(id::contains) }

    if (hasViewerId && !hasOrdinaryWatchId) {
      return DetectionResult(true, "viewer-resource-id")
    }

    val selectedShortsTab = nodes.any { node ->
      node.selected && sequenceOf(node.text, node.description).any { it.trim().equals("shorts", ignoreCase = true) }
    }
    val actionCount = nodes.count { node ->
      val description = node.description.lowercase()
      playerActionDescriptions.any(description::contains)
    }

    if (selectedShortsTab && actionCount >= 2 && !hasOrdinaryWatchId) {
      return DetectionResult(true, "selected-tab-with-player-actions")
    }

    return DetectionResult(false)
  }
}
