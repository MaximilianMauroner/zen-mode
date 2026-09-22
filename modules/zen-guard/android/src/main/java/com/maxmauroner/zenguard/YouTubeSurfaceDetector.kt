package com.maxmauroner.zenguard

/**
 * Conservative YouTube surface classifier.
 *
 * HOME intentionally has no positive signature yet. It must be populated only from sanitized
 * accessibility fixtures captured on the physical-device matrix in the validation document.
 * Until then, every possible Home layout is UNKNOWN and therefore fails open.
 */
internal object YouTubeSurfaceDetector {
  private val ordinaryWatchIds = listOf("watch_player", "player_control")
  private val preservedSelectedLabels = setOf(
    "subscriptions",
    "you",
    "library",
    "history",
    "notifications",
    "search",
  )

  fun detect(nodes: List<NodeSignal>): YouTubeSurface {
    if (ShortsDetector.detect(nodes).isShortsViewer) return YouTubeSurface.SHORTS

    if (nodes.any { node -> ordinaryWatchIds.any(node.viewId.lowercase()::contains) }) {
      return YouTubeSurface.OTHER
    }
    if (nodes.any { node ->
        node.selected && sequenceOf(node.text, node.description)
          .map(String::trim)
          .map(String::lowercase)
          .any(preservedSelectedLabels::contains)
      }) {
      return YouTubeSurface.OTHER
    }

    return YouTubeSurface.UNKNOWN
  }
}
