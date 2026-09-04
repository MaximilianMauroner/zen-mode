package com.maxmauroner.zenguard

import java.util.Calendar
import java.util.TimeZone

private const val MS_PER_DAY = 24 * 60 * 60 * 1000L

/**
 * The local calendar day [nowMs] falls in, as a sortable integer.
 *
 * Stores stamp their data with this so the first read after midnight reports a
 * fresh day without needing a scheduled reset. It follows the device time zone,
 * so the reset lands at the user's midnight rather than UTC.
 */
internal fun localDay(nowMs: Long): Int {
  val calendar = Calendar.getInstance(TimeZone.getDefault())
  calendar.timeInMillis = nowMs
  return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
}

/** How long until the local day rolls over and daily counts go back to zero. */
internal fun millisUntilLocalMidnight(nowMs: Long): Long {
  val calendar = Calendar.getInstance(TimeZone.getDefault())
  calendar.timeInMillis = nowMs
  calendar.set(Calendar.HOUR_OF_DAY, 0)
  calendar.set(Calendar.MINUTE, 0)
  calendar.set(Calendar.SECOND, 0)
  calendar.set(Calendar.MILLISECOND, 0)
  calendar.add(Calendar.DAY_OF_YEAR, 1)
  return (calendar.timeInMillis - nowMs).coerceIn(0L, MS_PER_DAY)
}

/** How much of the local day has already passed, as a 0..1 ratio. */
internal fun localDayElapsedRatio(nowMs: Long): Float =
  1f - millisUntilLocalMidnight(nowMs).toFloat() / MS_PER_DAY
