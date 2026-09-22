package com.maxmauroner.zenguard

internal enum class YouTubeSurface { HOME, SHORTS, OTHER, UNKNOWN }

internal enum class YouTubeHomeAction { NONE, LEAVE_HOME }

/** Policy only. Android navigation stays in the service after a destination is proven on-device. */
internal object YouTubeHomePolicy {
  fun next(
    surface: YouTubeSurface,
    enabled: Boolean,
    observed: Boolean,
  ): YouTubeHomeAction = if (surface == YouTubeSurface.HOME && enabled && observed) {
    YouTubeHomeAction.LEAVE_HOME
  } else {
    YouTubeHomeAction.NONE
  }
}
