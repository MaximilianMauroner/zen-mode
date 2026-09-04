package com.maxmauroner.zenguard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One rolling rule: at most [allowanceMinutes] of use in any [windowMinutes]. */
internal data class RollingRule(val allowanceMinutes: Int, val windowMinutes: Int)

/**
 * Rolling allowances for individual apps, e.g. 5 minutes in any hour.
 *
 * Unlike daily budgets there is no reset moment: every banked slice of
 * foreground time is stamped, and slices older than the window simply stop
 * counting, which refills the allowance over time. Rules and slices live in
 * one preferences file as JSON objects keyed by package name.
 */
internal class RollingLimitStore(context: Context) {
  private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

  /** Package name to its rolling rule. Malformed entries are skipped. */
  fun rules(): Map<String, RollingRule> {
    val raw = JSONObject(preferences.getString(KEY_RULES, "{}") ?: "{}")
    return raw.keys().asSequence().mapNotNull { packageName ->
      val entry = raw.optJSONObject(packageName) ?: return@mapNotNull null
      val allowance = entry.optInt(KEY_ALLOWANCE, 0)
      val window = entry.optInt(KEY_WINDOW, 0)
      if (!isValidRule(allowance, window)) return@mapNotNull null
      packageName to RollingRule(allowance, window)
    }.toMap()
  }

  fun setRule(packageName: String, allowanceMinutes: Int, windowMinutes: Int) {
    require(isValidRule(allowanceMinutes, windowMinutes)) {
      "A rolling rule needs 1 to $MAX_ALLOWANCE_MINUTES minutes inside 15 to $MAX_WINDOW_MINUTES minutes, allowance within window"
    }
    val raw = JSONObject(preferences.getString(KEY_RULES, "{}") ?: "{}")
    raw.put(packageName, JSONObject().put(KEY_ALLOWANCE, allowanceMinutes).put(KEY_WINDOW, windowMinutes))
    preferences.edit().putString(KEY_RULES, raw.toString()).apply()
  }

  fun removeRule(packageName: String) {
    val raw = JSONObject(preferences.getString(KEY_RULES, "{}") ?: "{}")
    if (raw.remove(packageName) == null && !usageRaw().has(packageName)) return
    preferences.edit().putString(KEY_RULES, raw.toString()).apply()
    val usage = usageRaw()
    if (usage.remove(packageName) != null) {
      preferences.edit().putString(KEY_USAGE, usage.toString()).apply()
    }
  }

  /** Banks foreground time for one app and prunes slices it can never need. */
  fun addUsage(packageName: String, durationMs: Long, nowWallMs: Long) {
    if (durationMs <= 0L) return
    val events = readEvents(packageName) + RollingEvent(nowWallMs, durationMs)
    writeEvents(packageName, RollingWindow.pruned(events, MAX_WINDOW_MINUTES * 60_000L, nowWallMs, MAX_SLICES))
  }

  /** Milliseconds [packageName] has used inside its own window. */
  fun usedMs(packageName: String, nowWallMs: Long): Long {
    val rule = rules()[packageName] ?: return 0L
    return RollingWindow.usedMs(readEvents(packageName), rule.windowMinutes * 60_000L, nowWallMs)
  }

  /** True once [packageName] has spent its allowance inside its window. */
  fun isOver(packageName: String, nowWallMs: Long): Boolean {
    val rule = rules()[packageName] ?: return false
    return usedMs(packageName, nowWallMs) >= rule.allowanceMinutes * 60_000L
  }

  private fun isValidRule(allowanceMinutes: Int, windowMinutes: Int): Boolean =
    allowanceMinutes in 1..MAX_ALLOWANCE_MINUTES &&
      windowMinutes in MIN_WINDOW_MINUTES..MAX_WINDOW_MINUTES &&
      allowanceMinutes <= windowMinutes

  private fun usageRaw(): JSONObject = JSONObject(preferences.getString(KEY_USAGE, "{}") ?: "{}")

  private fun readEvents(packageName: String): List<RollingEvent> {
    val entries = usageRaw().optJSONArray(packageName) ?: return emptyList()
    return (0 until entries.length()).mapNotNull { index ->
      val slice = entries.optJSONArray(index) ?: return@mapNotNull null
      val at = slice.optLong(0, 0L)
      val duration = slice.optLong(1, 0L)
      if (at <= 0L || duration <= 0L) return@mapNotNull null
      RollingEvent(at, duration)
    }
  }

  private fun writeEvents(packageName: String, events: List<RollingEvent>) {
    val usage = usageRaw()
    val entries = JSONArray()
    events.forEach { entries.put(JSONArray().put(it.atWallMs).put(it.durationMs)) }
    usage.put(packageName, entries)
    preferences.edit().putString(KEY_USAGE, usage.toString()).apply()
  }

  companion object {
    const val MAX_ALLOWANCE_MINUTES = 480
    const val MIN_WINDOW_MINUTES = 15
    const val MAX_WINDOW_MINUTES = 1440
    private const val MAX_SLICES = 1000
    private const val FILE_NAME = "zen_guard_rolling_limits"
    private const val KEY_RULES = "rules"
    private const val KEY_ALLOWANCE = "allowance"
    private const val KEY_WINDOW = "window"
    private const val KEY_USAGE = "usage"
  }
}
