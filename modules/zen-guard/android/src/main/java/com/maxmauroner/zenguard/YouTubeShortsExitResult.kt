package com.maxmauroner.zenguard

/** A navigation request is not a result until YouTube reports its Home tab selected. */
internal class YouTubeShortsExitResult(private val confirmationWindowMs: Long = 3_000L) {
  data class ConfirmedExit(val pageIndex: Int?)
  private var pendingPageIndex: Int? = null
  private var requestedAtMs: Long? = null

  fun requested(pageIndex: Int?, nowMs: Long) {
    pendingPageIndex = pageIndex
    requestedAtMs = nowMs
  }

  fun confirmed(homeSelected: Boolean, nowMs: Long): ConfirmedExit? {
    val requestedAt = requestedAtMs ?: return null
    if (nowMs < requestedAt || nowMs - requestedAt > confirmationWindowMs) {
      clear()
      return null
    }
    if (!homeSelected) return null
    val pageIndex = pendingPageIndex
    clear()
    return ConfirmedExit(pageIndex)
  }

  fun clear() {
    pendingPageIndex = null
    requestedAtMs = null
  }
}
