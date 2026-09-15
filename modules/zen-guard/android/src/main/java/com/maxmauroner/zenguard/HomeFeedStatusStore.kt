package com.maxmauroner.zenguard

import android.content.Context
import android.provider.Settings

/** Last authoritative Home-feed policy snapshot published by the accessibility service. */
internal data class HomeFeedStatus(val usedMs: Long, val blockedUntilElapsedMs: Long?)

/**
 * Small service-to-module bridge for Home-feed timers.
 *
 * Policy remains in the state machines. This store only lets the Expo module
 * read their latest snapshot after the user leaves X or Instagram to open Zen Mode.
 */
internal class HomeFeedStatusStore(context: Context) {
  private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
  private val bootCount = try {
    Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, UNKNOWN_BOOT)
  } catch (_: SecurityException) {
    UNKNOWN_BOOT
  }

  fun instagram(): HomeFeedStatus = read(INSTAGRAM_PREFIX)
  fun x(): HomeFeedStatus = read(X_PREFIX)

  fun recordInstagram(usedMs: Long, blockedUntilElapsedMs: Long?) = write(INSTAGRAM_PREFIX, usedMs, blockedUntilElapsedMs)
  fun recordX(usedMs: Long, blockedUntilElapsedMs: Long?) = write(X_PREFIX, usedMs, blockedUntilElapsedMs)

  private fun read(prefix: String): HomeFeedStatus {
    if (bootCount == UNKNOWN_BOOT || preferences.getInt("${prefix}_boot_count", UNKNOWN_BOOT) != bootCount) {
      return EMPTY_STATUS
    }
    val blockedUntil = preferences.getLong("${prefix}_blocked_until_elapsed", 0L).takeIf { it > 0L }
    return HomeFeedStatus(
      usedMs = preferences.getLong("${prefix}_used_ms", 0L).coerceAtLeast(0L),
      blockedUntilElapsedMs = blockedUntil,
    )
  }

  private fun write(prefix: String, usedMs: Long, blockedUntilElapsedMs: Long?) {
    val next = HomeFeedStatus(usedMs.coerceAtLeast(0L), blockedUntilElapsedMs)
    val isCurrentBoot = bootCount != UNKNOWN_BOOT && preferences.getInt("${prefix}_boot_count", UNKNOWN_BOOT) == bootCount
    if (isCurrentBoot && read(prefix) == next) return
    preferences.edit()
      .putInt("${prefix}_boot_count", bootCount)
      .putLong("${prefix}_used_ms", next.usedMs)
      .putLong("${prefix}_blocked_until_elapsed", blockedUntilElapsedMs ?: 0L)
      .apply()
  }

  companion object {
    private const val FILE_NAME = "zen_guard_home_feed_status"
    private const val INSTAGRAM_PREFIX = "instagram"
    private const val X_PREFIX = "x"
    private const val UNKNOWN_BOOT = -1
    private val EMPTY_STATUS = HomeFeedStatus(0L, null)
  }
}
