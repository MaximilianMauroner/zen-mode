package com.maxmauroner.zenguard

internal enum class EnforcementAction { NONE, LEAVE_SHORTS }

/** Allow the entry video for each Shorts visit; a different verified page ends that visit. */
internal class EnforcementStateMachine(private val cooldownMs: Long = 900) {
  private var firstPage: Int? = null
  private var lastActionAt: Long? = null

  fun next(isShorts: Boolean, pageIndex: Int?, nowMs: Long): EnforcementAction {
    if (!isShorts) {
      reset()
      return EnforcementAction.NONE
    }
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
    lastActionAt = null
  }
}
