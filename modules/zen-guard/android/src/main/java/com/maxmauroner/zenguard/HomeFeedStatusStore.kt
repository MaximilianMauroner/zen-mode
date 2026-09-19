package com.maxmauroner.zenguard

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings

/** Narrow persistence seam for corruption and commit-failure tests. */
internal interface HomeFeedStatusPersistence {
  fun readAll(): Map<String, Any?>
  fun commit(values: Map<String, Any?>): Boolean
}

private class SharedPreferencesHomeFeedStatusPersistence(
  context: Context,
) : HomeFeedStatusPersistence {
  private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

  override fun readAll(): Map<String, Any?> = preferences.all.entries.associate { it.key to it.value }

  override fun commit(values: Map<String, Any?>): Boolean = try {
    val editor = preferences.edit()
    values.forEach { (key, value) ->
      when (value) {
        null -> editor.remove(key)
        is Boolean -> editor.putBoolean(key, value)
        is Float -> editor.putFloat(key, value)
        is Int -> editor.putInt(key, value)
        is Long -> editor.putLong(key, value)
        is String -> editor.putString(key, value)
        is Set<*> -> {
          @Suppress("UNCHECKED_CAST")
          editor.putStringSet(key, value as Set<String>)
        }
        else -> return false
      }
    }
    // X accounting must know whether the snapshot reached disk before it keeps enforcing.
    editor.commit()
  } catch (_: RuntimeException) {
    false
  }

  companion object {
    private const val FILE_NAME = "zen_guard_home_feed_status"
  }
}

internal data class HomeFeedStatus(
  val usedMs: Long,
  val blockedUntilElapsedMs: Long?,
  val usageState: HomeFeedUsageState,
  val storageState: HomeFeedStorageState = HomeFeedStorageState.AVAILABLE,
  val lockoutState: HomeFeedLockoutState =
    if (blockedUntilElapsedMs != null) HomeFeedLockoutState.ACTIVE else HomeFeedLockoutState.NONE,
)

internal object HomeFeedStatusKeys {
  const val FORMAT_VERSION = "format_version"
  const val BOOT_COUNT = "boot_count"
  const val USED_MS = "used_ms"
  const val USAGE_STATE = "usage_state"
  const val LOCKOUT_STATE = "lockout_state"
  const val RESUME_AT_ELAPSED = "resume_at_elapsed"
  const val BLOCKED_UNTIL_ELAPSED = "blocked_until_elapsed"
  const val LEGACY_BLOCKED_UNTIL_WALL = "blocked_until_wall"

  fun key(prefix: String, suffix: String): String = "${prefix}_$suffix"
}

internal data class HomeFeedStatusDecode(
  val status: HomeFeedStatus,
  val migration: Map<String, Any?>? = null,
)

/**
 * Versioned scalar codec. Wrong types, impossible ranges, and inconsistent state are rejected
 * into an explicit unavailable status; they are never silently converted into zero usage.
 */
internal object HomeFeedStatusCodec {
  const val CURRENT_FORMAT_VERSION = 2
  const val STATE_PAUSED = 0
  const val STATE_ACTIVE = 1
  const val STATE_UNKNOWN = 2
  const val LOCKOUT_NONE = 0
  const val LOCKOUT_ACTIVE = 1
  const val LOCKOUT_UNKNOWN = 2

  fun decode(
    prefix: String,
    values: Map<String, Any?>,
    currentBootCount: Long?,
    nowElapsedMs: Long,
  ): HomeFeedStatusDecode {
    val now = nowElapsedMs.takeIf { it in 0L..HOME_FEED_MAX_SAFE_TIMESTAMP_MS }
      ?: return HomeFeedStatusDecode(unavailableStatus())
    val currentBoot = currentBootCount?.takeIf { it in 0L..Int.MAX_VALUE.toLong() }
      ?: return HomeFeedStatusDecode(unavailableStatus())
    val marker = "${prefix}_"
    if (values.keys.none { it.startsWith(marker) }) {
      return HomeFeedStatusDecode(
        HomeFeedStatus(
          usedMs = 0L,
          blockedUntilElapsedMs = null,
          usageState = HomeFeedUsageState.PAUSED,
        ),
      )
    }

    val format = if (!values.containsKey(HomeFeedStatusKeys.key(prefix, HomeFeedStatusKeys.FORMAT_VERSION))) {
      0
    } else {
      readInt(values[HomeFeedStatusKeys.key(prefix, HomeFeedStatusKeys.FORMAT_VERSION)], 0, CURRENT_FORMAT_VERSION)
        ?: return HomeFeedStatusDecode(unavailableStatus())
    }
    if (format != 0 && format != CURRENT_FORMAT_VERSION) return HomeFeedStatusDecode(unavailableStatus())
    val legacy = format == 0
    val boot = readRequiredLong(values, prefix, HomeFeedStatusKeys.BOOT_COUNT, 0L, Int.MAX_VALUE.toLong())
      ?: return HomeFeedStatusDecode(unavailableStatus())
    val used = readValueLong(
      values,
      prefix,
      HomeFeedStatusKeys.USED_MS,
      0L,
      HOME_FEED_MAX_SAFE_USAGE_MS,
      allowMissing = legacy,
    ) ?: return HomeFeedStatusDecode(unavailableStatus())
    val blockedUntil = readValueLong(
      values,
      prefix,
      HomeFeedStatusKeys.BLOCKED_UNTIL_ELAPSED,
      0L,
      HOME_FEED_MAX_SAFE_TIMESTAMP_MS,
      allowMissing = legacy,
    ) ?: return HomeFeedStatusDecode(unavailableStatus())
    val state = readValueInt(
      values,
      prefix,
      HomeFeedStatusKeys.USAGE_STATE,
      STATE_PAUSED,
      STATE_UNKNOWN,
      allowMissing = legacy,
    ) ?: return HomeFeedStatusDecode(unavailableStatus())
    val storedLockout = readValueInt(
      values,
      prefix,
      HomeFeedStatusKeys.LOCKOUT_STATE,
      LOCKOUT_NONE,
      LOCKOUT_UNKNOWN,
      allowMissing = legacy,
    ) ?: return HomeFeedStatusDecode(unavailableStatus())
    readValueLong(
      values,
      prefix,
      HomeFeedStatusKeys.RESUME_AT_ELAPSED,
      0L,
      HOME_FEED_MAX_SAFE_TIMESTAMP_MS,
      allowMissing = legacy,
    ) ?: return HomeFeedStatusDecode(unavailableStatus())
    val legacyWall = readValueLong(
      values,
      prefix,
      HomeFeedStatusKeys.LEGACY_BLOCKED_UNTIL_WALL,
      0L,
      HOME_FEED_MAX_SAFE_TIMESTAMP_MS,
      allowMissing = true,
    ) ?: return HomeFeedStatusDecode(unavailableStatus())

    // Legacy records did not have an explicit lockout enum; the elapsed deadline is authoritative
    // while it belongs to this boot. The old wall deadline is deliberately never restored.
    val lockout = if (legacy && blockedUntil > 0L) LOCKOUT_ACTIVE else storedLockout
    if (lockout == LOCKOUT_ACTIVE && blockedUntil == 0L) return HomeFeedStatusDecode(unavailableStatus())
    if (lockout == LOCKOUT_NONE && blockedUntil > 0L) return HomeFeedStatusDecode(unavailableStatus())
    if (lockout == LOCKOUT_UNKNOWN && blockedUntil > 0L) return HomeFeedStatusDecode(unavailableStatus())
    if (!legacy && legacyWall > 0L) return HomeFeedStatusDecode(unavailableStatus())

    val sameBoot = boot == currentBoot
    if (!sameBoot) {
      val unresolvedLockout = blockedUntil > 0L || lockout == LOCKOUT_ACTIVE || legacyWall > 0L
      val status = HomeFeedStatus(
        usedMs = used,
        blockedUntilElapsedMs = null,
        usageState = HomeFeedUsageState.UNKNOWN,
        lockoutState = if (unresolvedLockout) HomeFeedLockoutState.UNKNOWN else HomeFeedLockoutState.NONE,
      )
      return HomeFeedStatusDecode(
        status,
        migration = if (legacy) encode(prefix, status.toRuntimeState(now), boot, now) else null,
      )
    }

    // A legacy wall deadline with no same-boot monotonic deadline is unverifiable. Keep the
    // boundary fail-closed and migrate the explicit unknown state; never trust wall time here.
    if (legacy && legacyWall > 0L && blockedUntil == 0L) {
      val status = HomeFeedStatus(
        usedMs = used,
        blockedUntilElapsedMs = null,
        usageState = HomeFeedUsageState.UNKNOWN,
        lockoutState = HomeFeedLockoutState.UNKNOWN,
      )
      return HomeFeedStatusDecode(status, encode(prefix, status.toRuntimeState(now), boot, now))
    }

    if (lockout == LOCKOUT_ACTIVE && blockedUntil <= now) {
      val normalized = HomeFeedRuntimeState(
        usedMs = used,
        blockedUntilElapsedMs = blockedUntil,
        usageState = HomeFeedUsageState.PAUSED,
        lockoutState = HomeFeedLockoutState.ACTIVE,
        capturedAtElapsedMs = now,
      ).normalizedAt(now)
      val status = normalized.toStatus()
      return HomeFeedStatusDecode(status, encode(prefix, normalized, boot, now))
    }

    val decodedLockout = when {
      blockedUntil > 0L -> HomeFeedLockoutState.ACTIVE
      lockout == LOCKOUT_UNKNOWN -> HomeFeedLockoutState.UNKNOWN
      else -> HomeFeedLockoutState.NONE
    }
    val decodedUsage = when (state) {
      STATE_ACTIVE,
      STATE_UNKNOWN,
      -> HomeFeedUsageState.UNKNOWN
      else -> HomeFeedUsageState.PAUSED
    }
    val status = HomeFeedStatus(
      usedMs = used,
      blockedUntilElapsedMs = blockedUntil.takeIf { it > 0L },
      usageState = if (decodedLockout == HomeFeedLockoutState.NONE) decodedUsage else if (decodedLockout == HomeFeedLockoutState.ACTIVE) HomeFeedUsageState.PAUSED else HomeFeedUsageState.UNKNOWN,
      lockoutState = decodedLockout,
    )
    return HomeFeedStatusDecode(
      status,
      migration = if (legacy) encode(prefix, status.toRuntimeState(now), boot, now) else null,
    )
  }

  fun encode(
    prefix: String,
    runtime: HomeFeedRuntimeState,
    bootCount: Long?,
    nowElapsedMs: Long,
  ): Map<String, Any?>? {
    if (runtime.storageState != HomeFeedStorageState.AVAILABLE) return null
    val boot = bootCount ?: return null
    if (boot !in 0L..Int.MAX_VALUE.toLong()) return null
    if (nowElapsedMs !in 0L..HOME_FEED_MAX_SAFE_TIMESTAMP_MS) return null
    if (runtime.usedMs !in 0L..HOME_FEED_MAX_SAFE_USAGE_MS) return null
    val capturedAt = runtime.capturedAtElapsedMs
    if (capturedAt != null && capturedAt !in 0L..HOME_FEED_MAX_SAFE_TIMESTAMP_MS) return null
    val blockedUntil = runtime.blockedUntilElapsedMs
    if (blockedUntil != null && blockedUntil !in 0L..HOME_FEED_MAX_SAFE_TIMESTAMP_MS) return null

    val normalized = runtime.normalizedAt(nowElapsedMs)
    if (normalized.storageState != HomeFeedStorageState.AVAILABLE) return null
    val normalizedCapturedAt = normalized.capturedAtElapsedMs ?: nowElapsedMs
    val block = when (normalized.lockoutState) {
      HomeFeedLockoutState.ACTIVE -> normalized.blockedUntilElapsedMs?.takeIf { it > nowElapsedMs }
      HomeFeedLockoutState.NONE,
      HomeFeedLockoutState.UNKNOWN,
      -> null
    }
    val state = when (normalized.usageState) {
      HomeFeedUsageState.PAUSED -> STATE_PAUSED
      HomeFeedUsageState.ACTIVE -> STATE_ACTIVE
      HomeFeedUsageState.UNKNOWN -> STATE_UNKNOWN
    }
    val lockout = when (normalized.lockoutState) {
      HomeFeedLockoutState.NONE -> LOCKOUT_NONE
      HomeFeedLockoutState.ACTIVE -> if (block != null) LOCKOUT_ACTIVE else LOCKOUT_NONE
      HomeFeedLockoutState.UNKNOWN -> LOCKOUT_UNKNOWN
    }
    return mapOf(
      HomeFeedStatusKeys.key(prefix, HomeFeedStatusKeys.FORMAT_VERSION) to CURRENT_FORMAT_VERSION,
      HomeFeedStatusKeys.key(prefix, HomeFeedStatusKeys.BOOT_COUNT) to boot,
      HomeFeedStatusKeys.key(prefix, HomeFeedStatusKeys.USED_MS) to normalized.usedMs,
      HomeFeedStatusKeys.key(prefix, HomeFeedStatusKeys.USAGE_STATE) to state,
      HomeFeedStatusKeys.key(prefix, HomeFeedStatusKeys.LOCKOUT_STATE) to lockout,
      HomeFeedStatusKeys.key(prefix, HomeFeedStatusKeys.RESUME_AT_ELAPSED) to if (state == STATE_ACTIVE) normalizedCapturedAt else 0L,
      HomeFeedStatusKeys.key(prefix, HomeFeedStatusKeys.BLOCKED_UNTIL_ELAPSED) to (block ?: 0L),
      // Remove the old wall-clock fallback during migration.
      HomeFeedStatusKeys.key(prefix, HomeFeedStatusKeys.LEGACY_BLOCKED_UNTIL_WALL) to null,
    )
  }

  private fun readRequiredLong(
    values: Map<String, Any?>,
    prefix: String,
    suffix: String,
    min: Long,
    max: Long,
  ): Long? = readValueLong(values, prefix, suffix, min, max, allowMissing = false)

  private fun readValueLong(
    values: Map<String, Any?>,
    prefix: String,
    suffix: String,
    min: Long,
    max: Long,
    allowMissing: Boolean,
  ): Long? {
    val key = HomeFeedStatusKeys.key(prefix, suffix)
    if (!values.containsKey(key)) return if (allowMissing) 0L else null
    val raw = values[key] ?: return null
    val value = when (raw) {
      is Byte -> raw.toLong()
      is Short -> raw.toLong()
      is Int -> raw.toLong()
      is Long -> raw
      else -> return null
    }
    return value.takeIf { it in min..max }
  }

  private fun readValueInt(
    values: Map<String, Any?>,
    prefix: String,
    suffix: String,
    min: Int,
    max: Int,
    allowMissing: Boolean,
  ): Int? = readValueLong(values, prefix, suffix, min.toLong(), max.toLong(), allowMissing)?.toInt()

  private fun readInt(raw: Any?, min: Int, max: Int): Int? {
    val value = when (raw) {
      is Byte -> raw.toLong()
      is Short -> raw.toLong()
      is Int -> raw.toLong()
      is Long -> raw
      else -> return null
    }
    return value.takeIf { it in min.toLong()..max.toLong() }?.toInt()
  }

  private fun unavailableStatus() = HomeFeedStatus(
    usedMs = HOME_FEED_SAFE_FAIL_CLOSED_USAGE_MS,
    blockedUntilElapsedMs = null,
    usageState = HomeFeedUsageState.UNKNOWN,
    storageState = HomeFeedStorageState.UNAVAILABLE,
    lockoutState = HomeFeedLockoutState.UNKNOWN,
  )

  private fun HomeFeedStatus.toRuntimeState(nowElapsedMs: Long) = HomeFeedRuntimeState(
    usedMs = usedMs,
    blockedUntilElapsedMs = blockedUntilElapsedMs,
    usageState = usageState,
    lockoutState = lockoutState,
    storageState = storageState,
    capturedAtElapsedMs = nowElapsedMs,
  )

  private fun HomeFeedRuntimeState.toStatus() = HomeFeedStatus(
    usedMs = usedMs,
    blockedUntilElapsedMs = blockedUntilElapsedMs,
    usageState = usageState,
    storageState = storageState,
    lockoutState = lockoutState,
  )
}

/** Shared in-process signal so a failed service write cannot leave the UI showing an old timer. */
internal object HomeFeedStatusFailureRegistry {
  private val failedPrefixes = mutableSetOf<String>()

  @Synchronized fun markFailed(prefix: String) {
    failedPrefixes += prefix
  }

  @Synchronized fun isFailed(prefix: String): Boolean = prefix in failedPrefixes

  @Synchronized fun clearForTests() {
    failedPrefixes.clear()
  }
}

/**
 * Durable Home-feed snapshot shared by the service and Expo module.
 *
 * A persisted ACTIVE state is deliberately read as UNKNOWN: a new service instance cannot prove
 * that X stayed in the foreground across the gap, so it must wait for a fresh Home observation.
 */
internal class HomeFeedStatusStore(
  private val persistence: HomeFeedStatusPersistence,
  private val currentBootCount: () -> Long?,
  private val elapsedNowMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
  internal constructor(context: Context) : this(
    persistence = SharedPreferencesHomeFeedStatusPersistence(context),
    currentBootCount = {
      try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toLong()
          .takeIf { it >= 0L }
      } catch (_: RuntimeException) {
        null
      }
    },
  )

  fun instagram(nowElapsedMs: Long = elapsedNowMs()): HomeFeedStatus = read(INSTAGRAM_PREFIX, nowElapsedMs)
  fun x(nowElapsedMs: Long = elapsedNowMs()): HomeFeedStatus = read(X_PREFIX, nowElapsedMs)

  fun recordInstagram(runtime: HomeFeedRuntimeState): Boolean =
    write(INSTAGRAM_PREFIX, runtime.copy(usageState = HomeFeedUsageState.PAUSED))

  fun recordX(runtime: HomeFeedRuntimeState): Boolean = write(X_PREFIX, runtime)

  private fun read(prefix: String, nowElapsedMs: Long): HomeFeedStatus {
    if (HomeFeedStatusFailureRegistry.isFailed(prefix)) return failureStatus()
    val values = try {
      persistence.readAll()
    } catch (_: RuntimeException) {
      HomeFeedStatusFailureRegistry.markFailed(prefix)
      return failureStatus()
    }
    val decoded = HomeFeedStatusCodec.decode(prefix, values, safeBootCount(), nowElapsedMs)
    if (decoded.migration != null && !commit(prefix, decoded.migration)) return failureStatus()
    return decoded.status
  }

  private fun write(prefix: String, runtime: HomeFeedRuntimeState): Boolean {
    if (HomeFeedStatusFailureRegistry.isFailed(prefix)) return false
    val values = HomeFeedStatusCodec.encode(prefix, runtime, safeBootCount(), elapsedNowMs()) ?: run {
      HomeFeedStatusFailureRegistry.markFailed(prefix)
      return false
    }
    return commit(prefix, values)
  }

  private fun safeBootCount(): Long? = try {
    currentBootCount()?.takeIf { it in 0L..Int.MAX_VALUE.toLong() }
  } catch (_: RuntimeException) {
    null
  }

  private fun commit(prefix: String, values: Map<String, Any?>): Boolean = try {
    if (!persistence.commit(values)) {
      HomeFeedStatusFailureRegistry.markFailed(prefix)
      false
    } else {
      true
    }
  } catch (_: RuntimeException) {
    HomeFeedStatusFailureRegistry.markFailed(prefix)
    false
  }

  private fun failureStatus() = HomeFeedStatus(
    usedMs = HOME_FEED_SAFE_FAIL_CLOSED_USAGE_MS,
    blockedUntilElapsedMs = null,
    usageState = HomeFeedUsageState.UNKNOWN,
    storageState = HomeFeedStorageState.UNAVAILABLE,
    lockoutState = HomeFeedLockoutState.UNKNOWN,
  )

  companion object {
    private const val INSTAGRAM_PREFIX = "instagram"
    private const val X_PREFIX = "x"
  }
}
