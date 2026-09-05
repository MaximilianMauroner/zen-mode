package com.maxmauroner.zenguard

internal enum class XSurface { HOME, VIDEO, OTHER, UNKNOWN }
internal enum class XAction { NONE, HOME_BREAK, LEAVE_VIDEO }
internal data class XSettings(val homeEnabled: Boolean = true, val videosEnabled: Boolean = true, val homeAllowanceMs: Long = 300_000L)

/** Home sessions match Instagram; only a verified video-pager scroll consumes the video visit. */
internal class XGuardStateMachine {
  private var homeElapsedMs = 0L
  private var lastHomeAt: Long? = null
  private var lastVideoExitAt: Long? = null

  fun next(surface: XSurface, nowMs: Long, settings: XSettings, videoPagerAdvanced: Boolean = false): XAction {
    if (surface == XSurface.UNKNOWN) {
      pause()
      return XAction.NONE
    }
    if (surface != XSurface.HOME || !settings.homeEnabled) {
      homeElapsedMs = 0L
      lastHomeAt = null
    }
    if (surface != XSurface.VIDEO) lastVideoExitAt = null
    if (surface == XSurface.HOME && settings.homeEnabled) {
      lastHomeAt?.let { homeElapsedMs += (nowMs - it).coerceAtLeast(0L) }
      lastHomeAt = nowMs
      if (homeElapsedMs >= settings.homeAllowanceMs) return XAction.HOME_BREAK
    }
    if (surface == XSurface.VIDEO && settings.videosEnabled && videoPagerAdvanced) {
      if (lastVideoExitAt?.let { nowMs - it < 900L } == true) return XAction.NONE
      lastVideoExitAt = nowMs
      return XAction.LEAVE_VIDEO
    }
    return XAction.NONE
  }

  fun pause() { lastHomeAt = null }
  fun reset() { homeElapsedMs = 0L; lastHomeAt = null; lastVideoExitAt = null }
}

/** IDs observed in X's Compose accessibility tree. Post text is never a surface signal. */
internal object XDetector {
  fun detect(nodes: List<NodeSignal>): XSurface = when {
    nodes.any { it.viewId == "VideoTab" } -> XSurface.VIDEO
    nodes.any { it.viewId == "scaffold_home_tabbed" } -> XSurface.HOME
    nodes.any { it.viewId in setOf("Search", "PostDetail", "MainLanding") } -> XSurface.OTHER
    else -> XSurface.UNKNOWN
  }
}
