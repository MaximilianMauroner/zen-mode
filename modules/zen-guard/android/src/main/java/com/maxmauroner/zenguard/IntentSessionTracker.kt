package com.maxmauroner.zenguard

/**
 * Holds active intent sessions: which app was granted how much time.
 *
 * Times are monotonic elapsed-realtime milliseconds, so a wall-clock change
 * cannot stretch or shrink a session. The tracker keeps no clock of its own
 * and can be unit tested; every entry point takes the current time explicitly.
 */
internal class IntentSessionTracker {
  private sealed interface Visit {
    data object Timed : Visit
    data object AwaitingVideo : Visit
    data class Watching(val title: String) : Visit
  }

  private data class Session(val expiresAt: Long, val visit: Visit)

  private val sessions = mutableMapOf<String, Session>()

  /** Grants [packageName] a visit of [durationMinutes] from [nowElapsedMs]. */
  fun grant(packageName: String, durationMinutes: Int, nowElapsedMs: Long, watchOneVideo: Boolean = false) {
    require(durationMinutes in 1..IntentAppStore.MAX_SESSION_MINUTES) {
      "An intent visit must be between 1 and ${IntentAppStore.MAX_SESSION_MINUTES} minutes"
    }
    val visit = if (watchOneVideo) Visit.AwaitingVideo else Visit.Timed
    sessions[packageName] = Session(nowElapsedMs + durationMinutes * 60_000L, visit)
  }

  /** True while [packageName] still has time left on its granted session. */
  fun isActive(packageName: String, nowElapsedMs: Long): Boolean {
    return (sessions[packageName]?.expiresAt ?: return false) > nowElapsedMs
  }

  /** Milliseconds left on [packageName]'s session, or zero. */
  fun remainingMs(packageName: String, nowElapsedMs: Long): Long =
    ((sessions[packageName]?.expiresAt ?: 0L) - nowElapsedMs).coerceAtLeast(0L)

  fun isWatchingOneVideo(packageName: String, nowElapsedMs: Long): Boolean =
    isActive(packageName, nowElapsedMs) && sessions[packageName]?.visit != Visit.Timed

  fun startsSecondVideo(packageName: String, title: String): Boolean {
    val session = sessions[packageName] ?: return false
    return when (val visit = session.visit) {
      Visit.Timed -> false
      Visit.AwaitingVideo -> {
        sessions[packageName] = session.copy(visit = Visit.Watching(title))
        false
      }
      is Visit.Watching -> visit.title != title
    }
  }

  /** Ends [packageName]'s session without recording downtime. Returns true when one was active. */
  fun end(packageName: String): Boolean = sessions.remove(packageName) != null

  /** Drops all sessions, for when the guard is switched off. */
  fun reset() {
    sessions.clear()
  }
}
