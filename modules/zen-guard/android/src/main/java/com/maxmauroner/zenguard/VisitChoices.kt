package com.maxmauroner.zenguard

private val SHORTER_VISITS = listOf(1, 2, 5, 10, 15, 20, 30, 45)

/**
 * Visit lengths the ask card offers, shortest first: the longest visit the rule allows and up to
 * two shorter steps. A daily cap lowers the longest visit to the minutes left today, so no choice
 * can end after the cap. An empty list means no visit fits.
 */
internal fun visitChoices(maxMinutes: Int, minutesLeftToday: Int?): List<Int> {
  val longest = minOf(maxMinutes, minutesLeftToday ?: maxMinutes)
  if (longest < 1) return emptyList()
  return SHORTER_VISITS.filter { it < longest }.takeLast(2) + longest
}
