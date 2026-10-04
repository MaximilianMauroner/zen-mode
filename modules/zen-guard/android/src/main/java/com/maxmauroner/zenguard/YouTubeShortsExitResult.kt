package com.maxmauroner.zenguard

/** A result requires a positive Home surface and its selected tab, not merely absent Shorts IDs. */
internal class YouTubeShortsExitResult(private val confirmationWindowMs: Long = 3_000L) {
  data class ConfirmedExit(val pageIndex: Int?)
  private var pendingPageIndex: Int? = null
  private var requestedAtMs: Long? = null

  fun requested(pageIndex: Int?, nowMs: Long) {
    pendingPageIndex = pageIndex
    requestedAtMs = nowMs
  }

  fun confirmed(surface: YouTubeSurface, homeSelected: Boolean, nowMs: Long): ConfirmedExit? {
    val requestedAt = requestedAtMs ?: return null
    if (nowMs < requestedAt || nowMs - requestedAt > confirmationWindowMs) {
      clear()
      return null
    }
    if (surface != YouTubeSurface.HOME || !homeSelected) return null
    val pageIndex = pendingPageIndex
    clear()
    return ConfirmedExit(pageIndex)
  }

  fun clear() {
    pendingPageIndex = null
    requestedAtMs = null
  }
}
