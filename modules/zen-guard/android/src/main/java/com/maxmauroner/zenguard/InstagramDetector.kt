package com.maxmauroner.zenguard

internal enum class InstagramSurface(val mask: Int) {
  UNKNOWN(0),
  DIRECT_MESSAGES(1),
  REELS_VIEWER(2),
  HOME_FEED(4),
  EXPLORE(8),
}

internal data class InstagramDetection(
  val surface: InstagramSurface,
  val reason: String = "unknown",
)

/**
 * Conservative Instagram classifier. Text labels are deliberately not enough to classify a
 * surface because they vary by locale and can also appear inside posts and messages.
 */
internal object InstagramDetector {
  private val dmIds = listOf("direct_inbox", "direct_thread", "message_list", "row_inbox")
  private val reelsIds = listOf("clips_viewer", "reels_viewer", "reel_viewer", "clips_viewer_view_pager")
  private val homeIds = listOf("feed_view_pager", "main_feed", "newsfeed", "feed_recycler_view")
  private val exploreIds = listOf("explore_grid", "explore_recycler", "explore_search")

  fun detect(nodes: List<NodeSignal>): InstagramDetection {
    val ids = nodes.map { it.viewId.lowercase() }
    val matches = buildList {
      if (ids.any { id -> dmIds.any(id::contains) }) add(InstagramSurface.DIRECT_MESSAGES)
      if (ids.any { id -> reelsIds.any(id::contains) }) add(InstagramSurface.REELS_VIEWER)
      if (ids.any { id -> homeIds.any(id::contains) }) add(InstagramSurface.HOME_FEED)
      if (ids.any { id -> exploreIds.any(id::contains) }) add(InstagramSurface.EXPLORE)
    }

    if (matches.size != 1) return InstagramDetection(InstagramSurface.UNKNOWN)
    val surface = matches.single()
    return InstagramDetection(surface, "instagram-${surface.name.lowercase().replace('_', '-')}")
  }
}
