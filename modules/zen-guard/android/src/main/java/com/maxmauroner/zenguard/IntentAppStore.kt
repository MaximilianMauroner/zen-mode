package com.maxmauroner.zenguard

import android.content.Context
import org.json.JSONObject

/** One intent-gated app: a fixed visit length plus downtime between visits. */
internal data class IntentRule(val sessionMinutes: Int, val cooldownMinutes: Int)

/**
 * Apps that always open with an intent question: confirming starts one timed
 * visit of the app's fixed length. Returning inside the visit resumes it
 * without asking again.
 *
 * Each app also carries a downtime in minutes: the quiet gap after a visit
 * ends before the next one can be asked for. While the downtime runs, opening
 * the app only shows when it opens again.
 *
 * Rules and visit ends live in one preferences file as JSON objects keyed by
 * package name. Active visits stay in memory in [IntentSessionTracker], so a
 * service restart only ever re-asks, never grants extra time.
 */
internal class IntentAppStore(context: Context) {
  private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

  /** Package name to its intent rule. Entries in the old shape are skipped. */
  fun intents(): Map<String, IntentRule> {
    val raw = JSONObject(preferences.getString(KEY_INTENTS, "{}") ?: "{}")
    return raw.keys().asSequence().mapNotNull { packageName ->
      val entry = raw.optJSONObject(packageName) ?: return@mapNotNull null
      val session = entry.optInt(KEY_SESSION, 0)
      val cooldown = entry.optInt(KEY_COOLDOWN, -1)
      if (session !in 1..MAX_SESSION_MINUTES || cooldown !in 0..MAX_COOLDOWN_MINUTES) return@mapNotNull null
      packageName to IntentRule(session, cooldown)
    }.toMap()
  }

  fun setIntent(packageName: String, sessionMinutes: Int, cooldownMinutes: Int) {
    require(sessionMinutes in 1..MAX_SESSION_MINUTES) { "A visit must be between 1 and $MAX_SESSION_MINUTES minutes" }
    require(cooldownMinutes in 0..MAX_COOLDOWN_MINUTES) { "Downtime must be between 0 and $MAX_COOLDOWN_MINUTES minutes" }
    val raw = JSONObject(preferences.getString(KEY_INTENTS, "{}") ?: "{}")
    raw.put(packageName, JSONObject().put(KEY_SESSION, sessionMinutes).put(KEY_COOLDOWN, cooldownMinutes))
    preferences.edit().putString(KEY_INTENTS, raw.toString()).apply()
  }

  fun removeIntent(packageName: String) {
    val raw = JSONObject(preferences.getString(KEY_INTENTS, "{}") ?: "{}")
    if (raw.remove(packageName) == null) return
    preferences.edit().putString(KEY_INTENTS, raw.toString()).apply()
    val ends = lastEnds().toMutableMap()
    if (ends.remove(packageName) != null) writeLastEnds(ends)
  }

  /** Remembers when a visit for [packageName] ended, starting its downtime. */
  fun recordSessionEnd(packageName: String, nowWallMs: Long) {
    writeLastEnds(lastEnds() + (packageName to nowWallMs))
  }

  /** Milliseconds until [packageName] can be asked for again, or zero. */
  fun cooldownRemainingMs(packageName: String, nowWallMs: Long): Long {
    val cooldownMs = (intents()[packageName]?.cooldownMinutes ?: return 0) * 60_000L
    if (cooldownMs <= 0L) return 0L
    val lastEnd = lastEnds()[packageName] ?: return 0L
    return (lastEnd + cooldownMs - nowWallMs).coerceAtLeast(0L)
  }

  private fun lastEnds(): Map<String, Long> {
    val raw = JSONObject(preferences.getString(KEY_LAST_ENDS, "{}") ?: "{}")
    return raw.keys().asSequence().associateWith { raw.optLong(it, 0L) }.filterValues { it > 0L }
  }

  private fun writeLastEnds(values: Map<String, Long>) {
    val next = JSONObject()
    values.forEach { (key, value) -> next.put(key, value) }
    preferences.edit().putString(KEY_LAST_ENDS, next.toString()).apply()
  }

  companion object {
    const val MAX_SESSION_MINUTES = 60
    const val MAX_COOLDOWN_MINUTES = 180
    private const val FILE_NAME = "zen_guard_intent_apps"
    private const val KEY_INTENTS = "intents"
    private const val KEY_SESSION = "session"
    private const val KEY_COOLDOWN = "cooldown"
    private const val KEY_LAST_ENDS = "last_ends"
  }
}
