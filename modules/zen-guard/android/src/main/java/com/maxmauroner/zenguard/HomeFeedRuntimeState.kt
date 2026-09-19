package com.maxmauroner.zenguard

internal enum class HomeFeedUsageState { ACTIVE, PAUSED, UNKNOWN }

/** Read-only policy snapshot shared by the Instagram and X Home-feed guards. */
internal data class HomeFeedRuntimeState(
  val usedMs: Long,
  val blockedUntilElapsedMs: Long?,
  val usageState: HomeFeedUsageState = HomeFeedUsageState.PAUSED,
  /** Monotonic time through which [usedMs] includes active usage. */
  val capturedAtElapsedMs: Long? = null,
)
