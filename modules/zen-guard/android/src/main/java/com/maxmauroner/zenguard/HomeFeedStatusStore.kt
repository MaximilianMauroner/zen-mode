package com.maxmauroner.zenguard

import android.content.Context

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

  fun instagram(): HomeFeedStatus = read(INSTAGRAM_PREFIX)
  fun x(): HomeFeedStatus = read(X_PREFIX)

  fun recordInstagram(usedMs: Long, blockedUntilElapsedMs: Long?) = write(INSTAGRAM_PREFIX, usedMs, blockedUntilElapsedMs)
  fun recordX(usedMs: Long, blockedUntilElapsedMs: Long?) = write(X_PREFIX, usedMs, blockedUntilElapsedMs)

  private fun read(prefix: String): HomeFeedStatus {
    val blockedUntil = preferences.getLong("${prefix}_blocked_until_elapsed", 0L).takeIf { it > 0L }
    return HomeFeedStatus(
      usedMs = preferences.getLong("${prefix}_used_ms", 0L).coerceAtLeast(0L),
      blockedUntilElapsedMs = blockedUntil,
    )
  }

  private fun write(prefix: String, usedMs: Long, blockedUntilElapsedMs: Long?) {
    val next = HomeFeedStatus(usedMs.coerceAtLeast(0L), blockedUntilElapsedMs)
    if (read(prefix) == next) return
    preferences.edit()
      .putLong("${prefix}_used_ms", next.usedMs)
      .putLong("${prefix}_blocked_until_elapsed", blockedUntilElapsedMs ?: 0L)
      .apply()
  }

  companion object {
    private const val FILE_NAME = "zen_guard_home_feed_status"
    private const val INSTAGRAM_PREFIX = "instagram"
    private const val X_PREFIX = "x"
  }
}
