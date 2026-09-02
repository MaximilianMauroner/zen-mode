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
 * Conservative Instagram classifier.
 *
 * Instagram renames view IDs freely between releases, so matching exact IDs is brittle. This
 * classifier instead anchors on Instagram's product vocabulary ("clips", "direct", "inbox",
 * "thread", "feed", "explore"). Those tokens are stable because they name the product itself, while
 * the surrounding IDs churn. Each surface needs a *distinctive content* token: the bottom tab bar
 * (`feed_tab`, `clips_tab`, `direct_tab`, ...) is present on every main surface and never
 * classifies one on its own.
 *
 * Text labels are deliberately ignored. They vary by locale and also appear inside posts and
 * messages. When two surfaces match at once the tree is ambiguous and classification fails open to
 * UNKNOWN, which the policy engine treats as "do nothing".
 */
internal object InstagramDetector {
  fun detect(nodes: List<NodeSignal>): InstagramDetection {
    val ids = nodes.map { it.viewId.lowercase() }

    val matches = buildList {
      if (ids.any(::isDirectMessages)) add(InstagramSurface.DIRECT_MESSAGES)
      if (ids.any(::isReelsViewer)) add(InstagramSurface.REELS_VIEWER)
      if (ids.any(::isHomeFeed)) add(InstagramSurface.HOME_FEED)
      if (ids.any(::isExplore)) add(InstagramSurface.EXPLORE)
    }

    if (matches.size != 1) return InstagramDetection(InstagramSurface.UNKNOWN)
    val surface = matches.single()
    return InstagramDetection(surface, "instagram-${surface.name.lowercase().replace('_', '-')}")
  }

  /**
   * The full-screen Reels player. A Reel shared inside a DM renders with `clips_media_component`
   * and the story `reel_viewer_*` avatars, but never with the `clips_viewer` namespace or a clips
   * pager, so those embedded trees stay classified as their host surface.
   */
  fun isReelsViewer(id: String): Boolean = id.contains("clips_viewer") || isReelsPager(id)

  /** The swipeable container that emits the vertical Reel scroll events. */
  fun isReelsPager(id: String): Boolean =
    id.contains("pager") && (id.contains("clips") || id.contains("reels"))

  private fun isDirectMessages(id: String): Boolean =
    id.contains("direct_inbox") ||
      id.contains("direct_thread") ||
      id.contains("thread_fragment") ||
      (id.contains("inbox") && id.contains("thread")) ||
      (id.contains("thread") && id.contains("composer")) ||
      (id.contains("message") && (id.contains("composer") || id.contains("list")))

  private fun isHomeFeed(id: String): Boolean =
    id.contains("row_feed_") ||
      id.contains("feed_recycler") ||
      id.contains("main_feed") ||
      id.contains("newsfeed") ||
      id.contains("feed_view_pager")

  private fun isExplore(id: String): Boolean =
    id.contains("explore_grid") ||
      id.contains("explore_recycler") ||
      id.contains("explore_action_bar") ||
      (id.contains("explore") && id.contains("search"))
}
