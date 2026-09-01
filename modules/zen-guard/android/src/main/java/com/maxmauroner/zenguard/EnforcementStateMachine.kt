package com.maxmauroner.zenguard

internal enum class EnforcementAction {
  NONE,
  BACK,
  HOME,
}

/** Bounds enforcement to at most one action per cooldown and one HOME fallback per Shorts visit. */
internal class EnforcementStateMachine(
  private val cooldownMs: Long = 900,
  private val fallbackAfterMs: Long = 1_800,
) {
  private var firstDetectionAt: Long? = null
  private var lastActionAt = Long.MIN_VALUE
  private var usedHomeFallback = false

  fun next(isShorts: Boolean, nowMs: Long): EnforcementAction {
    if (!isShorts) {
      reset()
      return EnforcementAction.NONE
    }

    val firstAt = firstDetectionAt ?: nowMs.also { firstDetectionAt = it }
    if (lastActionAt != Long.MIN_VALUE && nowMs - lastActionAt < cooldownMs) {
      return EnforcementAction.NONE
    }

    if (nowMs - firstAt >= fallbackAfterMs && !usedHomeFallback) {
      usedHomeFallback = true
      lastActionAt = nowMs
      return EnforcementAction.HOME
    }

    if (lastActionAt == Long.MIN_VALUE) {
      lastActionAt = nowMs
      return EnforcementAction.BACK
    }

    return EnforcementAction.NONE
  }

  private fun reset() {
    firstDetectionAt = null
    lastActionAt = Long.MIN_VALUE
    usedHomeFallback = false
  }
}
