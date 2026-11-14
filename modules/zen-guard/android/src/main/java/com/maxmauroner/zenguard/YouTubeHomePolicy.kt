package com.maxmauroner.zenguard

internal enum class YouTubeSurface { HOME, SHORTS, OTHER, UNKNOWN }

internal data class YouTubeHomeVisit(val startedElapsedMs: Long) {
  val deadlineElapsedMs: Long = startedElapsedMs + 10_000L
}

internal class YouTubeHomePolicy {
  private var visit: YouTubeHomeVisit? = null

  fun observe(surface: YouTubeSurface, enabled: Boolean, observed: Boolean, nowElapsedMs: Long): YouTubeHomeVisit? {
    if (surface != YouTubeSurface.HOME || !enabled || !observed) {
      reset()
      return null
    }
    return visit ?: YouTubeHomeVisit(nowElapsedMs).also { visit = it }
  }

  fun reset() {
    visit = null
  }
}
