package com.maxmauroner.zenguard

import android.content.Context

internal class ZenGuardPreferences(context: Context) {
  private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

  var protectionEnabled: Boolean
    get() = preferences.getBoolean(KEY_PROTECTION_ENABLED, false)
    set(value) = preferences.edit().putBoolean(KEY_PROTECTION_ENABLED, value).apply()

  var observationMode: Boolean
    get() = preferences.getBoolean(KEY_OBSERVATION_MODE, true)
    set(value) = preferences.edit().putBoolean(KEY_OBSERVATION_MODE, value).apply()

  val lastEventAt: Long get() = preferences.getLong(KEY_LAST_EVENT_AT, 0)
  val lastDetectionAt: Long get() = preferences.getLong(KEY_LAST_DETECTION_AT, 0)
  val detectionCount: Int get() = preferences.getInt(KEY_DETECTION_COUNT, 0)
  val lastDetectionReason: String get() = preferences.getString(KEY_LAST_DETECTION_REASON, "") ?: ""

  var instagramObservationMode: Boolean
    get() = preferences.getBoolean(KEY_INSTAGRAM_OBSERVATION_MODE, true)
    set(value) = preferences.edit().putBoolean(KEY_INSTAGRAM_OBSERVATION_MODE, value).apply()

  var instagramWaitSeconds: Int
    get() = preferences.getInt(KEY_INSTAGRAM_WAIT_SECONDS, 30)
    set(value) = preferences.edit().putInt(KEY_INSTAGRAM_WAIT_SECONDS, value).apply()

  var instagramReelsMinutes: Int
    get() = preferences.getInt(KEY_INSTAGRAM_REELS_MINUTES, 5)
    set(value) = preferences.edit().putInt(KEY_INSTAGRAM_REELS_MINUTES, value).apply()

  var instagramHomeMinutes: Int
    get() = preferences.getInt(KEY_INSTAGRAM_HOME_MINUTES, 5)
    set(value) = preferences.edit().putInt(KEY_INSTAGRAM_HOME_MINUTES, value).apply()

  var instagramExploreBlocked: Boolean
    get() = preferences.getBoolean(KEY_INSTAGRAM_EXPLORE_BLOCKED, true)
    set(value) = preferences.edit().putBoolean(KEY_INSTAGRAM_EXPLORE_BLOCKED, value).apply()

  val instagramLastDetectionAt: Long get() = preferences.getLong(KEY_INSTAGRAM_LAST_DETECTION_AT, 0)
  val instagramDetectionCount: Int get() = preferences.getInt(KEY_INSTAGRAM_DETECTION_COUNT, 0)
  val instagramSignalMask: Int get() = preferences.getInt(KEY_INSTAGRAM_SIGNAL_MASK, 0)
  val instagramLastDetectionReason: String get() = preferences.getString(KEY_INSTAGRAM_LAST_DETECTION_REASON, "") ?: ""

  fun recordEvent(nowMs: Long) {
    preferences.edit().putLong(KEY_LAST_EVENT_AT, nowMs).apply()
  }

  fun recordDetection(nowMs: Long, reason: String) {
    preferences.edit()
      .putLong(KEY_LAST_DETECTION_AT, nowMs)
      .putInt(KEY_DETECTION_COUNT, detectionCount + 1)
      .putString(KEY_LAST_DETECTION_REASON, reason)
      .apply()
  }

  fun recordInstagramDetection(nowMs: Long, detection: InstagramDetection) {
    preferences.edit()
      .putLong(KEY_INSTAGRAM_LAST_DETECTION_AT, nowMs)
      .putInt(KEY_INSTAGRAM_DETECTION_COUNT, instagramDetectionCount + 1)
      .putInt(KEY_INSTAGRAM_SIGNAL_MASK, instagramSignalMask or detection.surface.mask)
      .putString(KEY_INSTAGRAM_LAST_DETECTION_REASON, detection.reason)
      .apply()
  }

  fun instagramSettings() = InstagramGuardSettings(
    waitMs = instagramWaitSeconds * 1_000L,
    reelsWindowMs = instagramReelsMinutes * 60_000L,
    homeAllowanceMs = instagramHomeMinutes * 60_000L,
    exploreBlocked = instagramExploreBlocked,
  )

  companion object {
    private const val FILE_NAME = "zen_guard_preferences"
    private const val KEY_PROTECTION_ENABLED = "protection_enabled"
    private const val KEY_OBSERVATION_MODE = "observation_mode"
    private const val KEY_LAST_EVENT_AT = "last_event_at"
    private const val KEY_LAST_DETECTION_AT = "last_detection_at"
    private const val KEY_DETECTION_COUNT = "detection_count"
    private const val KEY_LAST_DETECTION_REASON = "last_detection_reason"
    private const val KEY_INSTAGRAM_OBSERVATION_MODE = "instagram_observation_mode"
    private const val KEY_INSTAGRAM_WAIT_SECONDS = "instagram_wait_seconds"
    private const val KEY_INSTAGRAM_REELS_MINUTES = "instagram_reels_minutes"
    private const val KEY_INSTAGRAM_HOME_MINUTES = "instagram_home_minutes"
    private const val KEY_INSTAGRAM_EXPLORE_BLOCKED = "instagram_explore_blocked"
    private const val KEY_INSTAGRAM_LAST_DETECTION_AT = "instagram_last_detection_at"
    private const val KEY_INSTAGRAM_DETECTION_COUNT = "instagram_detection_count"
    private const val KEY_INSTAGRAM_SIGNAL_MASK = "instagram_signal_mask"
    private const val KEY_INSTAGRAM_LAST_DETECTION_REASON = "instagram_last_detection_reason"
  }
}
