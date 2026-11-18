package com.maxmauroner.zenguard

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * Daily minute budgets for individual apps, plus how much of each budget today
 * has used up.
 *
 * Budgets and usage live in one preferences file as two JSON objects keyed by
 * package name. Usage carries the day it belongs to, so the first read after
 * midnight reports zero without needing a scheduled job.
 */
internal class AppLimitStore(context: Context) {
  private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

  /** Package name to daily budget in minutes. */
  fun limits(): Map<String, Int> {
    val raw = JSONObject(preferences.getString(KEY_LIMITS, "{}") ?: "{}")
    return raw.keys().asSequence().associateWith { raw.optInt(it, 0) }.filterValues { it > 0 }
  }

  fun setLimit(packageName: String, minutes: Int, guidedVisitPending: Boolean = false) {
    val update = editLimits(limits() + (packageName to minutes))
    if (guidedVisitPending) {
      check(update.putBoolean(guidedVisitKey(packageName), true).commit()) { "Could not save the daily cap" }
    } else {
      update.remove(guidedVisitKey(packageName)).apply()
    }
  }

  fun removeLimit(packageName: String) {
    editLimits(limits() - packageName).remove(guidedVisitKey(packageName)).apply()
  }

  fun removePendingGuidedCaps() {
    val current = limits()
    val pending = current.keys.filter(::hasPendingGuidedVisit)
    if (pending.isEmpty()) return
    val update = editLimits(current - pending)
    pending.forEach { update.remove(guidedVisitKey(it)) }
    check(update.commit()) { "Could not remove unfinished setup caps" }
  }

  fun hasPendingGuidedVisit(packageName: String): Boolean = preferences.getBoolean(guidedVisitKey(packageName), false)

  fun clearPendingGuidedVisit(packageName: String) {
    if (hasPendingGuidedVisit(packageName)) preferences.edit().remove(guidedVisitKey(packageName)).apply()
  }

  /** Milliseconds each limited app has spent in the foreground today. */
  fun usageToday(nowMs: Long): Map<String, Long> {
    if (preferences.getInt(KEY_USAGE_DAY, -1) != dayOf(nowMs)) return emptyMap()
    val raw = JSONObject(preferences.getString(KEY_USAGE, "{}") ?: "{}")
    return raw.keys().asSequence().associateWith { raw.optLong(it, 0L) }
  }

  /** Adds foreground time to one app and returns its new total for today. */
  fun addUsage(packageName: String, durationMs: Long, nowMs: Long): Long {
    val current = usageToday(nowMs)
    val total = (current[packageName] ?: 0L) + durationMs

    val next = JSONObject()
    (current + (packageName to total)).forEach { (key, value) -> next.put(key, value) }
    preferences.edit()
      .putString(KEY_USAGE, next.toString())
      .putInt(KEY_USAGE_DAY, dayOf(nowMs))
      .apply()
    return total
  }

  /** True once [packageName] has used up its budget for today. */
  fun isOverBudget(packageName: String, nowMs: Long): Boolean {
    val budgetMinutes = limits()[packageName] ?: return false
    val used = usageToday(nowMs)[packageName] ?: 0L
    return used >= budgetMinutes * 60_000L
  }

  /** Time left in today's budget, or null when [packageName] has no budget. */
  fun leftTodayMs(packageName: String, nowMs: Long): Long? {
    val budgetMinutes = limits()[packageName] ?: return null
    return (budgetMinutes * 60_000L - (usageToday(nowMs)[packageName] ?: 0L)).coerceAtLeast(0L)
  }

  /** Whole minutes left in today's budget, rounded up so a part-minute still offers a visit. */
  fun minutesLeftToday(packageName: String, nowMs: Long): Int? =
    leftTodayMs(packageName, nowMs)?.let { ((it + 59_999L) / 60_000L).toInt() }

  fun availableAgainAt(packageName: String, nowWallMs: Long): Long? =
    if (isOverBudget(packageName, nowWallMs)) nowWallMs + millisUntilLocalMidnight(nowWallMs) else null

  private fun editLimits(values: Map<String, Int>): SharedPreferences.Editor {
    val next = JSONObject()
    values.forEach { (key, value) -> next.put(key, value) }
    return preferences.edit().putString(KEY_LIMITS, next.toString())
  }

  private fun guidedVisitKey(packageName: String) = "guided_visit_pending_$packageName"

  /** Local calendar day, so the reset lands at the user's midnight. */
  private fun dayOf(nowMs: Long): Int {
    val calendar = Calendar.getInstance(TimeZone.getDefault())
    calendar.timeInMillis = nowMs
    return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
  }

  companion object {
    private const val FILE_NAME = "zen_guard_app_limits"
    private const val KEY_LIMITS = "limits"
    private const val KEY_USAGE = "usage"
    private const val KEY_USAGE_DAY = "usage_day"
  }
}
