package com.maxmauroner.zenguard

/**
 * Holds active intent sessions: which app was granted how much time.
 *
 * Times are monotonic elapsed-realtime milliseconds, so a wall-clock change
 * cannot stretch or shrink a session. The tracker keeps no clock of its own
 * and can be unit tested; every entry point takes the current time explicitly.
 */
internal class IntentSessionTracker {
  private val expiries = mutableMapOf<String, Long>()

  /** Grants [packageName] a visit of [durationMinutes] from [nowElapsedMs]. */
  fun grant(packageName: String, durationMinutes: Int, nowElapsedMs: Long) {
    require(durationMinutes in 1..IntentAppStore.MAX_SESSION_MINUTES) {
      "An intent visit must be between 1 and ${IntentAppStore.MAX_SESSION_MINUTES} minutes"
    }
    expiries[packageName] = nowElapsedMs + durationMinutes * 60_000L
  }

  /** True while [packageName] still has time left on its granted session. */
  fun isActive(packageName: String, nowElapsedMs: Long): Boolean {
    return (expiries[packageName] ?: return false) > nowElapsedMs
  }

  /** Milliseconds left on [packageName]'s session, or zero. */
  fun remainingMs(packageName: String, nowElapsedMs: Long): Long =
    ((expiries[packageName] ?: 0L) - nowElapsedMs).coerceAtLeast(0L)

  /** Ends [packageName]'s session without recording downtime. Returns true when one was active. */
  fun end(packageName: String): Boolean = expiries.remove(packageName) != null

  /** Drops all sessions, for when the guard is switched off. */
  fun reset() {
    expiries.clear()
  }
}
