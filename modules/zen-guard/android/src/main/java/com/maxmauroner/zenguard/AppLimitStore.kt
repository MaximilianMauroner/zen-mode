package com.maxmauroner.zenguard

import android.content.Context
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

  fun setLimit(packageName: String, minutes: Int) {
    writeLimits(limits() + (packageName to minutes))
  }

  fun removeLimit(packageName: String) {
    writeLimits(limits() - packageName)
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

  private fun writeLimits(values: Map<String, Int>) {
    val next = JSONObject()
    values.forEach { (key, value) -> next.put(key, value) }
    preferences.edit().putString(KEY_LIMITS, next.toString()).apply()
  }

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
