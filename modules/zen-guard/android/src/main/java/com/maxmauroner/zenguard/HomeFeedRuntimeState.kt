package com.maxmauroner.zenguard

internal enum class HomeFeedUsageState { ACTIVE, PAUSED, UNKNOWN }
internal enum class HomeFeedStorageState { AVAILABLE, UNAVAILABLE }
internal enum class HomeFeedLockoutState { NONE, ACTIVE, UNKNOWN }

/** Bounds used at the persistence boundary and by all elapsed-time arithmetic. */
internal const val HOME_FEED_MAX_SAFE_USAGE_MS = 365L * 24L * 60L * 60L * 1_000L
internal const val HOME_FEED_MAX_SAFE_TIMESTAMP_MS = Long.MAX_VALUE / 2L
internal const val HOME_FEED_SAFE_FAIL_CLOSED_USAGE_MS = Long.MAX_VALUE / 4L

internal fun elapsedMsSince(nowMs: Long, startMs: Long): Long =
  if (nowMs < 0L || startMs < 0L || nowMs <= startMs) 0L else nowMs - startMs

internal fun saturatingAddMs(valueMs: Long, durationMs: Long): Long {
  if (valueMs <= 0L) return durationMs.coerceAtLeast(0L)
  if (durationMs <= 0L) return valueMs
  return if (Long.MAX_VALUE - valueMs < durationMs) Long.MAX_VALUE else valueMs + durationMs
}

internal fun saturatingUsageAdd(valueMs: Long, durationMs: Long): Long =
  saturatingAddMs(valueMs.coerceAtLeast(0L), durationMs.coerceAtLeast(0L))
    .coerceAtMost(HOME_FEED_MAX_SAFE_USAGE_MS)

internal fun saturatingTimestampAdd(nowMs: Long, durationMs: Long): Long =
  saturatingAddMs(nowMs.coerceIn(0L, HOME_FEED_MAX_SAFE_TIMESTAMP_MS), durationMs.coerceAtLeast(0L))
    .coerceAtMost(HOME_FEED_MAX_SAFE_TIMESTAMP_MS)

/** Read-only policy snapshot shared by the Instagram and X Home-feed guards. */
internal data class HomeFeedRuntimeState(
  val usedMs: Long,
  val blockedUntilElapsedMs: Long?,
  val usageState: HomeFeedUsageState = HomeFeedUsageState.PAUSED,
  val lockoutState: HomeFeedLockoutState =
    if (blockedUntilElapsedMs != null) HomeFeedLockoutState.ACTIVE else HomeFeedLockoutState.NONE,
  val storageState: HomeFeedStorageState = HomeFeedStorageState.AVAILABLE,
  /** Monotonic time through which [usedMs] includes active usage. */
  val capturedAtElapsedMs: Long? = null,
)

/**
 * Makes the state safe to serialize or restore. In particular, an expired lockout is a
 * completed policy phase, not a snapshot with an expired deadline and exhausted usage.
 */
internal fun HomeFeedRuntimeState.normalizedAt(nowElapsedMs: Long): HomeFeedRuntimeState {
  val now = nowElapsedMs.coerceIn(0L, HOME_FEED_MAX_SAFE_TIMESTAMP_MS)
  if (storageState == HomeFeedStorageState.UNAVAILABLE) {
    return copy(
      usedMs = HOME_FEED_SAFE_FAIL_CLOSED_USAGE_MS,
      blockedUntilElapsedMs = null,
      usageState = HomeFeedUsageState.UNKNOWN,
      lockoutState = HomeFeedLockoutState.UNKNOWN,
      capturedAtElapsedMs = now,
    )
  }

  val safeUsed = usedMs.coerceIn(0L, HOME_FEED_MAX_SAFE_USAGE_MS)
  val safeCapturedAt = capturedAtElapsedMs?.takeIf { it in 0L..HOME_FEED_MAX_SAFE_TIMESTAMP_MS }
  val safeBlockedUntil = blockedUntilElapsedMs?.takeIf { it in 0L..HOME_FEED_MAX_SAFE_TIMESTAMP_MS }
  val effectiveLockout = when {
    lockoutState == HomeFeedLockoutState.UNKNOWN -> HomeFeedLockoutState.UNKNOWN
    safeBlockedUntil != null -> HomeFeedLockoutState.ACTIVE
    lockoutState == HomeFeedLockoutState.ACTIVE -> HomeFeedLockoutState.UNKNOWN
    else -> HomeFeedLockoutState.NONE
  }

  if (effectiveLockout == HomeFeedLockoutState.ACTIVE && safeBlockedUntil!! <= now) {
    return copy(
      usedMs = 0L,
      blockedUntilElapsedMs = null,
      usageState = HomeFeedUsageState.PAUSED,
      lockoutState = HomeFeedLockoutState.NONE,
      capturedAtElapsedMs = now,
    )
  }

  return copy(
    usedMs = safeUsed,
    blockedUntilElapsedMs = if (effectiveLockout == HomeFeedLockoutState.ACTIVE) safeBlockedUntil else null,
    usageState = if (effectiveLockout == HomeFeedLockoutState.ACTIVE) HomeFeedUsageState.PAUSED else usageState,
    lockoutState = effectiveLockout,
    capturedAtElapsedMs = safeCapturedAt,
  )
}
