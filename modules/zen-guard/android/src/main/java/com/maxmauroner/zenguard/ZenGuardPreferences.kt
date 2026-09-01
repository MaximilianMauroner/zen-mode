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

  companion object {
    private const val FILE_NAME = "zen_guard_preferences"
    private const val KEY_PROTECTION_ENABLED = "protection_enabled"
    private const val KEY_OBSERVATION_MODE = "observation_mode"
    private const val KEY_LAST_EVENT_AT = "last_event_at"
    private const val KEY_LAST_DETECTION_AT = "last_detection_at"
    private const val KEY_DETECTION_COUNT = "detection_count"
    private const val KEY_LAST_DETECTION_REASON = "last_detection_reason"
  }
}
