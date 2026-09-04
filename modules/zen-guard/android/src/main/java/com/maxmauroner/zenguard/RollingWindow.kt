package com.maxmauroner.zenguard

/** One banked slice of foreground time, stamped with wall-clock time. */
internal data class RollingEvent(val atWallMs: Long, val durationMs: Long)

/**
 * Rolling-window sums over banked usage slices.
 *
 * Pure wall-clock math with no clock of its own, so it can be unit tested:
 * every entry point takes the current time explicitly. Old slices age out on
 * their own, which is what refills an allowance over time.
 */
internal object RollingWindow {
  /** Milliseconds banked inside the last [windowMs], ending at [nowWallMs]. */
  fun usedMs(events: List<RollingEvent>, windowMs: Long, nowWallMs: Long): Long {
    if (windowMs <= 0L) return 0L
    val cutoff = nowWallMs - windowMs
    return events.filter { it.atWallMs > cutoff }.sumOf { it.durationMs }
  }

  /** Drops slices older than [maxWindowMs] and keeps at most [cap] entries. */
  fun pruned(events: List<RollingEvent>, maxWindowMs: Long, nowWallMs: Long, cap: Int): List<RollingEvent> {
    val cutoff = nowWallMs - maxWindowMs
    return events.filter { it.atWallMs > cutoff }.takeLast(cap)
  }
}
