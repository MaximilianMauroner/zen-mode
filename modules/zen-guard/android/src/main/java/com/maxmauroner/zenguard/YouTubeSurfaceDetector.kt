package com.maxmauroner.zenguard

import android.view.accessibility.AccessibilityNodeInfo

internal data class YouTubeScreen(
  val surface: YouTubeSurface,
  val subscriptions: AccessibilityNodeInfo? = null,
)

internal object YouTubeSurfaceDetector {
  private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
  private val preservedIds = listOf(
    "watch_player", "player_control", "search_edit", "search_query", "search_results",
    "search_suggestions", "channel_header", "channel_page", "channel_profile",
    "notification_panel", "notification_list", "history_list", "playlist_panel", "comment_thread",
  )
  private val navigationLabels = setOf("home", "shorts", "subscriptions", "you", "library")
  private val preservedSelectedLabels = setOf(
    "subscriptions", "you", "library", "history", "notifications", "search", "channel",
  )

  fun detect(nodes: List<NodeSignal>): YouTubeSurface {
    if (ShortsDetector.detect(nodes).isShortsViewer) return YouTubeSurface.SHORTS
    if (nodes.any { node -> preservedIds.any(node.viewId.lowercase()::contains) }) {
      return YouTubeSurface.OTHER
    }
    if (nodes.any { it.selected && labels(it).any(preservedSelectedLabels::contains) }) {
      return YouTubeSurface.OTHER
    }
    return YouTubeSurface.UNKNOWN
  }

  fun inspect(root: AccessibilityNodeInfo): YouTubeScreen {
    if (root.packageName?.toString() != YOUTUBE_PACKAGE || !root.isVisibleToUser) {
      return YouTubeScreen(YouTubeSurface.UNKNOWN)
    }
    val nodes = visibleNodes(root) ?: return YouTubeScreen(YouTubeSurface.UNKNOWN)
    val surface = detect(nodes.map(::signal))
    if (surface != YouTubeSurface.UNKNOWN) return YouTubeScreen(surface)
    if (nodes.any { it.className?.toString() == "android.widget.EditText" }) {
      return YouTubeScreen(YouTubeSurface.OTHER)
    }
    val bar = nodes.singleOrNull { it.viewIdResourceName == "$YOUTUBE_PACKAGE:id/pivot_bar" }
      ?: return YouTubeScreen(YouTubeSurface.UNKNOWN)
    val navigation = visibleNodes(bar) ?: return YouTubeScreen(YouTubeSurface.UNKNOWN)
    val controls = navigation.filter { it != bar && it.isClickable && it.isEnabled }
    val selected = controls.filter { control ->
      visibleNodes(control)?.any { it.isSelected } == true
    }
    val home = selected.singleOrNull()
      ?: return YouTubeScreen(YouTubeSurface.UNKNOWN)
    if (controls.singleOrNull { controlLabels(it) == setOf("home") } != home) {
      return YouTubeScreen(YouTubeSurface.UNKNOWN)
    }
    val subscriptions = controls.singleOrNull { controlLabels(it) == setOf("subscriptions") }
      ?: return YouTubeScreen(YouTubeSurface.UNKNOWN)
    if (subscriptions == home) return YouTubeScreen(YouTubeSurface.UNKNOWN)
    // A retained Home-tab selection alone cannot distinguish Search or a channel from the feed.
    val homeContent = nodes.any {
      it !in navigation && it.isScrollable && it.contentDescription?.toString()?.trim()?.lowercase() == "home"
    }
    if (!homeContent) return YouTubeScreen(YouTubeSurface.UNKNOWN)
    return YouTubeScreen(YouTubeSurface.HOME, subscriptions)
  }

  private fun controlLabels(control: AccessibilityNodeInfo): Set<String> =
    visibleNodes(control)?.flatMap { labels(signal(it)) }?.filter(navigationLabels::contains)?.toSet().orEmpty()

  private fun labels(node: NodeSignal): List<String> =
    listOf(node.text, node.description).map { it.trim().lowercase() }.filter { it.isNotEmpty() }

  private fun signal(node: AccessibilityNodeInfo) = NodeSignal(
    viewId = node.viewIdResourceName.orEmpty(),
    text = node.text?.toString().orEmpty(),
    description = node.contentDescription?.toString().orEmpty(),
    selected = node.isSelected,
  )

  private fun visibleNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo>? {
    val nodes = mutableListOf<AccessibilityNodeInfo>()
    val pending = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
    pending.add(root to 0)
    while (pending.isNotEmpty()) {
      val (node, depth) = pending.removeFirst()
      if (!node.isVisibleToUser) continue
      if (nodes.size >= 500 || depth > 36 || node.packageName?.toString() != YOUTUBE_PACKAGE) return null
      nodes.add(node)
      for (index in 0 until node.childCount) {
        val child = node.getChild(index) ?: return null
        pending.add(child to depth + 1)
      }
    }
    return nodes
  }
}
