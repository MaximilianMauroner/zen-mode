package com.maxmauroner.zenguard

import android.content.Context

/**
 * How today's blocks ended, counted per local day.
 *
 * The blocked screen shows these back in its second phase, so they have to
 * survive a service restart. They are deliberately not history: the stored day
 * stamp drops yesterday's numbers on the first read after midnight.
 */
internal class DailyTally(context: Context) {
  private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

  data class Counts(val stopped: Int, val continued: Int)

  fun counts(nowMs: Long): Counts {
    if (preferences.getInt(KEY_DAY, NO_DAY) != localDay(nowMs)) return Counts(0, 0)
    return Counts(
      stopped = preferences.getInt(KEY_STOPPED, 0),
      continued = preferences.getInt(KEY_CONTINUED, 0),
    )
  }

  /** A blocker was put on screen. Counted when it appears, not when it is dismissed. */
  fun recordStop(nowMs: Long) {
    val current = counts(nowMs)
    write(current.copy(stopped = current.stopped + 1), nowMs)
  }

  /** The user waited out the pause and took the continue action. */
  fun recordContinue(nowMs: Long) {
    val current = counts(nowMs)
    write(current.copy(continued = current.continued + 1), nowMs)
  }

  private fun write(counts: Counts, nowMs: Long) {
    preferences.edit()
      .putInt(KEY_STOPPED, counts.stopped)
      .putInt(KEY_CONTINUED, counts.continued)
      .putInt(KEY_DAY, localDay(nowMs))
      .apply()
  }

  companion object {
    private const val FILE_NAME = "zen_guard_daily_tally"
    private const val KEY_DAY = "day"
    private const val KEY_STOPPED = "stopped"
    private const val KEY_CONTINUED = "continued"
    private const val NO_DAY = -1
  }
}
