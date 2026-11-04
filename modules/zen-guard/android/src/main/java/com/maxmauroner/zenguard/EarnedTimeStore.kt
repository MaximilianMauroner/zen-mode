package com.maxmauroner.zenguard

import android.content.Context
import org.json.JSONObject

/** Every [habitMinutes] in [habitPackage] earns [rewardMinutes] in the guarded app. */
internal data class EarnRule(val habitPackage: String, val habitMinutes: Int, val rewardMinutes: Int)

/**
 * Apps that stay closed until the user earns time in a habit app, for example
 * 30 minutes in a reading app for 5 minutes of a social app.
 *
 * Habit time earns credit in proportion, so a short session still counts.
 * Using the guarded app spends the credit, and the app closes again at zero.
 * Rules and balances live in one preferences file as JSON objects keyed by the
 * guarded package name.
 */
internal class EarnedTimeStore(context: Context) {
  private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

  /** Guarded package name to its rule. Malformed entries are skipped. */
  fun rules(): Map<String, EarnRule> {
    val raw = read(KEY_RULES)
    return raw.keys().asSequence().mapNotNull { packageName ->
      val entry = raw.optJSONObject(packageName) ?: return@mapNotNull null
      val rule = EarnRule(entry.optString(KEY_HABIT), entry.optInt(KEY_HABIT_MINUTES), entry.optInt(KEY_REWARD_MINUTES))
      if (!isValidRule(packageName, rule)) return@mapNotNull null
      packageName to rule
    }.toMap()
  }

  /** Saving over an existing rule keeps the time already earned. */
  fun setRule(packageName: String, rule: EarnRule) {
    require(isValidRule(packageName, rule)) {
      "An earned-time rule needs a different habit app and 1 to $MAX_MINUTES minutes for each side"
    }
    val raw = read(KEY_RULES)
    raw.put(
      packageName,
      JSONObject().put(KEY_HABIT, rule.habitPackage).put(KEY_HABIT_MINUTES, rule.habitMinutes).put(KEY_REWARD_MINUTES, rule.rewardMinutes),
    )
    write(KEY_RULES, raw)
  }

  fun removeRule(packageName: String) {
    val rules = read(KEY_RULES)
    val balances = read(KEY_BALANCES)
    val hadRule = rules.remove(packageName) != null
    val hadBalance = balances.remove(packageName) != null
    if (!hadRule && !hadBalance) return
    write(KEY_RULES, rules)
    write(KEY_BALANCES, balances)
  }

  /** Credits every guarded app whose habit is [packageName], and spends from [packageName] when it is guarded. */
  fun addUsage(packageName: String, durationMs: Long) {
    if (durationMs <= 0L) return
    val balances = read(KEY_BALANCES)
    var changed = false
    rules().forEach { (guarded, rule) ->
      val delta = when (packageName) {
        rule.habitPackage -> durationMs * rule.rewardMinutes / rule.habitMinutes
        guarded -> -durationMs
        else -> return@forEach
      }
      balances.put(guarded, (balances.optLong(guarded, 0L) + delta).coerceAtLeast(0L))
      changed = true
    }
    if (changed) write(KEY_BALANCES, balances)
  }

  /** Earned milliseconds [packageName] can still spend. */
  fun balanceMs(packageName: String): Long = read(KEY_BALANCES).optLong(packageName, 0L).coerceAtLeast(0L)

  private fun isValidRule(packageName: String, rule: EarnRule): Boolean =
    rule.habitPackage.isNotBlank() &&
      rule.habitPackage != packageName &&
      rule.habitMinutes in 1..MAX_MINUTES &&
      rule.rewardMinutes in 1..MAX_MINUTES

  private fun read(key: String) = JSONObject(preferences.getString(key, "{}") ?: "{}")

  private fun write(key: String, value: JSONObject) {
    preferences.edit().putString(key, value.toString()).apply()
  }

  companion object {
    const val MAX_MINUTES = 480
    private const val FILE_NAME = "zen_guard_earned_time"
    private const val KEY_RULES = "rules"
    private const val KEY_BALANCES = "balances"
    private const val KEY_HABIT = "habit"
    private const val KEY_HABIT_MINUTES = "habitMinutes"
    private const val KEY_REWARD_MINUTES = "rewardMinutes"
  }
}
