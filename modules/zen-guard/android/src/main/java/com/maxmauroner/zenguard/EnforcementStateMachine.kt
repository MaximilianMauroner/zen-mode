package com.maxmauroner.zenguard

internal enum class EnforcementAction { NONE, LEAVE_SHORTS }

/** Allow the entry video for each Shorts visit; verified pager movement ends that visit. */
internal class EnforcementStateMachine(private val cooldownMs: Long = 900) {
  private var firstPage: Int? = null
  private var viewerObserved = false
  private var lastActionAt: Long? = null

  fun next(
    isShorts: Boolean,
    pageIndex: Int?,
    nowMs: Long,
    pagerAdvanced: Boolean = false,
  ): EnforcementAction {
    if (!isShorts) {
      reset()
      return EnforcementAction.NONE
    }
    val wasObserved = viewerObserved
    viewerObserved = true
    if (pagerAdvanced && wasObserved) return leaveShorts(nowMs)
    if (pageIndex == null || pageIndex < 0) return EnforcementAction.NONE
    val first = firstPage
    if (first == null) {
      firstPage = pageIndex
      return EnforcementAction.NONE
    }
    if (pageIndex == first) return EnforcementAction.NONE
    return leaveShorts(nowMs)
  }

  private fun leaveShorts(nowMs: Long): EnforcementAction {
    if (lastActionAt?.let { nowMs - it < cooldownMs } == true) return EnforcementAction.NONE
    lastActionAt = nowMs
    return EnforcementAction.LEAVE_SHORTS
  }

  fun reset() {
    firstPage = null
    viewerObserved = false
    lastActionAt = null
  }
}
