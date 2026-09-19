package com.maxmauroner.zenguard

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/** Last authoritative Home-feed policy snapshot published by the accessibility service. */
internal data class HomeFeedStatus(
  val usedMs: Long,
  val blockedUntilElapsedMs: Long?,
  val usageState: HomeFeedUsageState,
  /** The durable snapshot boundary to resume from after a same-boot service restart. */
  val resumeAtElapsedMs: Long?,
)

/**
 * Service-to-module bridge for Home-feed timers.
 *
 * Policy remains in the state machines. This store keeps the latest snapshot
 * and the active-session boundary so a service restart cannot replace consumed
 * X time with a fresh in-memory zero.
 */
internal class HomeFeedStatusStore(context: Context) {
  private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
  private val bootCount = try {
    Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, UNKNOWN_BOOT)
  } catch (_: SecurityException) {
    UNKNOWN_BOOT
  }

  fun instagram(
    nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    nowWallMs: Long = System.currentTimeMillis(),
  ): HomeFeedStatus = read(INSTAGRAM_PREFIX, nowElapsedMs, nowWallMs)

  fun x(
    nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    nowWallMs: Long = System.currentTimeMillis(),
  ): HomeFeedStatus = read(X_PREFIX, nowElapsedMs, nowWallMs)

  fun recordInstagram(runtime: HomeFeedRuntimeState) =
    write(INSTAGRAM_PREFIX, runtime.copy(usageState = HomeFeedUsageState.PAUSED))

  fun recordX(runtime: HomeFeedRuntimeState) = write(X_PREFIX, runtime)

  private fun read(prefix: String, nowElapsedMs: Long, nowWallMs: Long): HomeFeedStatus {
    val storedBootCount = preferences.getInt("${prefix}_boot_count", UNKNOWN_BOOT)
    val sameBoot = bootCount != UNKNOWN_BOOT && storedBootCount == bootCount
    if (!sameBoot && prefix == INSTAGRAM_PREFIX) return EMPTY_STATUS
    val storedBlockedUntilElapsedMs = preferences.getLong("${prefix}_blocked_until_elapsed", 0L)
    val storedBlockedUntilWallMs = preferences.getLong("${prefix}_blocked_until_wall", 0L)
    val hasKnownBlockDeadline = (sameBoot && storedBlockedUntilElapsedMs > 0L) ||
      (!sameBoot && storedBlockedUntilWallMs > 0L)
    val remainingBreakMs = when {
      sameBoot && storedBlockedUntilElapsedMs > 0L ->
        (storedBlockedUntilElapsedMs - nowElapsedMs).coerceAtLeast(0L)
      !sameBoot && storedBlockedUntilWallMs > 0L ->
        (storedBlockedUntilWallMs - nowWallMs).coerceAtLeast(0L)
      else -> 0L
    }
    val breakActive = remainingBreakMs > 0L
    val storedState = decodeUsageState(preferences.getInt("${prefix}_usage_state", STATE_PAUSED))
    val usageState = when {
      breakActive -> HomeFeedUsageState.PAUSED
      sameBoot -> storedState
      storedState == HomeFeedUsageState.UNKNOWN -> HomeFeedUsageState.UNKNOWN
      else -> HomeFeedUsageState.PAUSED
    }
    val usedMs = if (hasKnownBlockDeadline && !breakActive) {
      0L
    } else {
      preferences.getLong("${prefix}_used_ms", 0L).coerceAtLeast(0L)
    }
    val resumeAtElapsedMs = if (sameBoot && !breakActive && usageState == HomeFeedUsageState.ACTIVE) {
      preferences.getLong("${prefix}_resume_at_elapsed", 0L).takeIf { it > 0L }
    } else {
      null
    }

    return HomeFeedStatus(
      usedMs = usedMs,
      blockedUntilElapsedMs = if (breakActive) nowElapsedMs + remainingBreakMs else null,
      usageState = usageState,
      resumeAtElapsedMs = resumeAtElapsedMs,
    )
  }

  private fun write(prefix: String, runtime: HomeFeedRuntimeState) {
    val nowElapsedMs = runtime.capturedAtElapsedMs ?: SystemClock.elapsedRealtime()
    val blockedUntilElapsedMs = runtime.blockedUntilElapsedMs
    val breakRemainingMs = blockedUntilElapsedMs?.let { (it - nowElapsedMs).coerceAtLeast(0L) } ?: 0L
    val active = runtime.usageState == HomeFeedUsageState.ACTIVE && breakRemainingMs == 0L
    val nowWallMs = System.currentTimeMillis()
    val editor = preferences.edit()
      .putInt("${prefix}_boot_count", bootCount)
      .putLong("${prefix}_used_ms", runtime.usedMs.coerceAtLeast(0L))
      .putInt("${prefix}_usage_state", encodeUsageState(runtime.usageState))
      .putLong("${prefix}_resume_at_elapsed", if (active) nowElapsedMs else 0L)
      .putLong("${prefix}_blocked_until_elapsed", if (breakRemainingMs > 0L) blockedUntilElapsedMs!! else 0L)
      .putLong("${prefix}_blocked_until_wall", if (breakRemainingMs > 0L) nowWallMs + breakRemainingMs else 0L)
    if (prefix == X_PREFIX) {
      // X accounting is a safety boundary: make the snapshot durable before returning.
      editor.commit()
    } else {
      editor.apply()
    }
  }

  private fun encodeUsageState(state: HomeFeedUsageState): Int = when (state) {
    HomeFeedUsageState.ACTIVE -> STATE_ACTIVE
    HomeFeedUsageState.PAUSED -> STATE_PAUSED
    HomeFeedUsageState.UNKNOWN -> STATE_UNKNOWN
  }

  private fun decodeUsageState(value: Int): HomeFeedUsageState = when (value) {
    STATE_ACTIVE -> HomeFeedUsageState.ACTIVE
    STATE_UNKNOWN -> HomeFeedUsageState.UNKNOWN
    else -> HomeFeedUsageState.PAUSED
  }

  companion object {
    private const val FILE_NAME = "zen_guard_home_feed_status"
    private const val INSTAGRAM_PREFIX = "instagram"
    private const val X_PREFIX = "x"
    private const val UNKNOWN_BOOT = -1
    private const val STATE_PAUSED = 0
    private const val STATE_ACTIVE = 1
    private const val STATE_UNKNOWN = 2
    private val EMPTY_STATUS = HomeFeedStatus(0L, null, HomeFeedUsageState.PAUSED, null)
  }
}
