package com.maxmauroner.zenguard

/** Read-only policy snapshot shared by the Instagram and X Home-feed guards. */
internal data class HomeFeedRuntimeState(
  val usedMs: Long,
  val blockedUntilElapsedMs: Long?,
)
