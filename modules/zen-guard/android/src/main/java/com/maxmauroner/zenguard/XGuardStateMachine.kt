package com.maxmauroner.zenguard

internal enum class XSurface { HOME, VIDEO, OTHER, UNKNOWN }
internal enum class XAction { NONE, HOME_BREAK, LEAVE_VIDEO }
internal data class XSettings(
  val homeEnabled: Boolean = true,
  val videosEnabled: Boolean = true,
  val homeAllowanceMs: Long = 300_000L,
  val homeLockoutMs: Long = 3_600_000L,
)

/** Home sessions match Instagram; only a verified video-pager scroll consumes the video visit. */
internal class XGuardStateMachine {
  private var homeElapsedMs = 0L
  private var lastHomeAt: Long? = null
  private var homeBlockedUntil: Long? = null
  private var lastVideoExitAt: Long? = null

  fun next(surface: XSurface, nowMs: Long, settings: XSettings, videoPagerAdvanced: Boolean = false): XAction {
    if (surface == XSurface.UNKNOWN) {
      pause()
      return XAction.NONE
    }
    if (!settings.homeEnabled) {
      resetHomeSession()
      homeBlockedUntil = null
    } else if (surface != XSurface.HOME) {
      resetHomeSession()
    }
    if (surface != XSurface.VIDEO) lastVideoExitAt = null
    if (surface == XSurface.HOME && settings.homeEnabled) {
      val blockedUntil = homeBlockedUntil
      if (blockedUntil != null) {
        if (nowMs < blockedUntil) return XAction.HOME_BREAK
        homeBlockedUntil = null
        resetHomeSession()
      }
      lastHomeAt?.let { homeElapsedMs += (nowMs - it).coerceAtLeast(0L) }
      lastHomeAt = nowMs
      if (homeElapsedMs >= settings.homeAllowanceMs) {
        homeBlockedUntil = nowMs + settings.homeLockoutMs
        return XAction.HOME_BREAK
      }
    }
    if (surface == XSurface.VIDEO && settings.videosEnabled && videoPagerAdvanced) {
      if (lastVideoExitAt?.let { nowMs - it < 900L } == true) return XAction.NONE
      lastVideoExitAt = nowMs
      return XAction.LEAVE_VIDEO
    }
    return XAction.NONE
  }

  fun pause(nowMs: Long? = null, settings: XSettings? = null) {
    if (homeBlockedUntil == null && nowMs != null) {
      lastHomeAt?.let { homeElapsedMs += (nowMs - it).coerceAtLeast(0L) }
      if (settings != null && homeElapsedMs >= settings.homeAllowanceMs) {
        homeBlockedUntil = nowMs + settings.homeLockoutMs
      }
    }
    lastHomeAt = null
  }
  fun leaveBlockedSurface() { lastHomeAt = null }
  fun reset() { resetHomeSession(); homeBlockedUntil = null; lastVideoExitAt = null }

  /** Snapshot for presentation only; enforcement continues to use the private state above. */
  fun homeRuntimeState(nowMs: Long): HomeFeedRuntimeState {
    val pendingMs = if (homeBlockedUntil == null) {
      lastHomeAt?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
    } else {
      0L
    }
    return HomeFeedRuntimeState(homeElapsedMs + pendingMs, homeBlockedUntil)
  }

  private fun resetHomeSession() {
    homeElapsedMs = 0L
    lastHomeAt = null
  }
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
