package com.maxmauroner.zenguard

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings

/** Narrow persistence seam for corruption and commit-failure tests. */
internal interface HomeFeedStatusPersistence {
  fun readAll(): Map<String, Any?>
  fun commit(values: Map<String, Any?>): Boolean

  /** Integrity metadata is deliberately independent from the status snapshot. */
  fun readIntegrity(): Map<String, Any?>
  fun commitIntegrity(values: Map<String, Any?>): Boolean
}

private class SharedPreferencesHomeFeedStatusPersistence(
  context: Context,
) : HomeFeedStatusPersistence {
  private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
  private val integrityPreferences = context.getSharedPreferences(INTEGRITY_FILE_NAME, Context.MODE_PRIVATE)

  override fun readAll(): Map<String, Any?> = preferences.all.entries.associate { it.key to it.value }

  override fun commit(values: Map<String, Any?>): Boolean = commitTo(preferences, values)

  override fun readIntegrity(): Map<String, Any?> =
    integrityPreferences.all.entries.associate { it.key to it.value }

  override fun commitIntegrity(values: Map<String, Any?>): Boolean = commitTo(integrityPreferences, values)

  private fun commitTo(preferences: SharedPreferences, values: Map<String, Any?>): Boolean = try {
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
          if (value.any { it !is String }) return false
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
    private const val INTEGRITY_FILE_NAME = "zen_guard_home_feed_integrity"
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
  const val GENERATION = "generation"
  const val CHECKSUM = "checksum"
  const val BOOT_COUNT = "boot_count"
  const val USED_MS = "used_ms"
  const val USAGE_STATE = "usage_state"
  const val LOCKOUT_STATE = "lockout_state"
  const val RESUME_AT_ELAPSED = "resume_at_elapsed"
  const val BLOCKED_UNTIL_ELAPSED = "blocked_until_elapsed"
  const val LEGACY_BLOCKED_UNTIL_WALL = "blocked_until_wall"

  fun key(prefix: String, suffix: String): String = "${prefix}_$suffix"
}

internal object HomeFeedStatusIntegrityKeys {
  const val FORMAT_VERSION = "integrity_format_version"
  const val INITIALIZED = "integrity_initialized"
  const val PHASE = "integrity_phase"
  const val GENERATION = "integrity_generation"
  const val PENDING_GENERATION = "integrity_pending_generation"

  fun key(prefix: String, suffix: String): String = "${prefix}_$suffix"
}

internal data class HomeFeedStatusDecode(
  val status: HomeFeedStatus,
  val formatVersion: Int = -1,
  val generation: Long? = null,
  val needsMigration: Boolean = false,
)

internal data class HomeFeedIntegrityState(
  val phase: Int,
  val generation: Long,
  val pendingGeneration: Long,
)

/** Transaction phases for the independent integrity journal. */
internal object HomeFeedStatusIntegrityCodec {
  const val CURRENT_FORMAT_VERSION = 1
  const val PHASE_STABLE = 0
  const val PHASE_PENDING = 1
  const val PHASE_FAILED = 2
  const val PHASE_UNAVAILABLE = 3

  fun decode(prefix: String, values: Map<String, Any?>): HomeFeedIntegrityState? {
    val format = readScalarLong(values[HomeFeedStatusIntegrityKeys.key(prefix, HomeFeedStatusIntegrityKeys.FORMAT_VERSION)])
      ?.takeIf { it in 1L..CURRENT_FORMAT_VERSION.toLong() }
      ?: return null
    if (format != CURRENT_FORMAT_VERSION.toLong()) return null
    if (values[HomeFeedStatusIntegrityKeys.key(prefix, HomeFeedStatusIntegrityKeys.INITIALIZED)] != true) return null
    val phase = readScalarLong(values[HomeFeedStatusIntegrityKeys.key(prefix, HomeFeedStatusIntegrityKeys.PHASE)])
      ?.takeIf { it in PHASE_STABLE.toLong()..PHASE_UNAVAILABLE.toLong() }
      ?.toInt()
      ?: return null
    val generation = readScalarLong(values[HomeFeedStatusIntegrityKeys.key(prefix, HomeFeedStatusIntegrityKeys.GENERATION)])
      ?.takeIf { it in 0L..HOME_FEED_MAX_GENERATION }
      ?: return null
    val pendingGeneration = readScalarLong(values[HomeFeedStatusIntegrityKeys.key(prefix, HomeFeedStatusIntegrityKeys.PENDING_GENERATION)])
      ?.takeIf { it in 0L..HOME_FEED_MAX_GENERATION }
      ?: return null

    return when (phase) {
      PHASE_STABLE -> if (pendingGeneration == 0L) {
        HomeFeedIntegrityState(phase, generation, pendingGeneration)
      } else {
        null
      }
      PHASE_PENDING,
      PHASE_FAILED,
      -> if (generation < HOME_FEED_MAX_GENERATION && pendingGeneration == generation + 1L) {
        HomeFeedIntegrityState(phase, generation, pendingGeneration)
      } else {
        null
      }
      PHASE_UNAVAILABLE -> if (pendingGeneration == 0L) {
        HomeFeedIntegrityState(phase, generation, pendingGeneration)
      } else {
        null
      }
      else -> null
    }
  }

  fun values(prefix: String, phase: Int, generation: Long, pendingGeneration: Long): Map<String, Any?> = mapOf(
    HomeFeedStatusIntegrityKeys.key(prefix, HomeFeedStatusIntegrityKeys.FORMAT_VERSION) to CURRENT_FORMAT_VERSION,
    HomeFeedStatusIntegrityKeys.key(prefix, HomeFeedStatusIntegrityKeys.INITIALIZED) to true,
    HomeFeedStatusIntegrityKeys.key(prefix, HomeFeedStatusIntegrityKeys.PHASE) to phase,
    HomeFeedStatusIntegrityKeys.key(prefix, HomeFeedStatusIntegrityKeys.GENERATION) to generation,
    HomeFeedStatusIntegrityKeys.key(prefix, HomeFeedStatusIntegrityKeys.PENDING_GENERATION) to pendingGeneration,
  )
}

/**
 * Versioned scalar codec. Wrong types, impossible ranges, inconsistent state, and checksum
 * mismatches are rejected into an explicit unavailable status; they are never silently converted
 * into zero usage.
 */
internal object HomeFeedStatusCodec {
  const val CURRENT_FORMAT_VERSION = 3
  const val INVALID_FORMAT_VERSION = -1
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
        formatVersion = 0,
      )
    }

    val format = if (!values.containsKey(HomeFeedStatusKeys.key(prefix, HomeFeedStatusKeys.FORMAT_VERSION))) {
      0
    } else {
      readScalarLong(values[HomeFeedStatusKeys.key(prefix, HomeFeedStatusKeys.FORMAT_VERSION)])
        ?.takeIf { it in 0L..CURRENT_FORMAT_VERSION.toLong() }
        ?.toInt()
        ?: return HomeFeedStatusDecode(unavailableStatus())
    }
    if (format != 0 && format != CURRENT_FORMAT_VERSION) {
      return HomeFeedStatusDecode(unavailableStatus())
    }
    val legacy = format == 0
    val current = format == CURRENT_FORMAT_VERSION
    val generation = if (current) {
      readValueLong(
        values,
        prefix,
        HomeFeedStatusKeys.GENERATION,
        1L,
        HOME_FEED_MAX_GENERATION,
        allowMissing = false,
      ) ?: return HomeFeedStatusDecode(unavailableStatus())
    } else {
      null
    }
    val storedChecksum = if (current) {
      readValueLong(
        values,
        prefix,
        HomeFeedStatusKeys.CHECKSUM,
        Long.MIN_VALUE,
        Long.MAX_VALUE,
        allowMissing = false,
      ) ?: return HomeFeedStatusDecode(unavailableStatus())
    } else {
      null
    }
    val boot = readRequiredLong(values, prefix, HomeFeedStatusKeys.BOOT_COUNT, 0L, Int.MAX_VALUE.toLong())
      ?: return HomeFeedStatusDecode(unavailableStatus())
    val used = readValueLong(
      values,
      prefix,
      HomeFeedStatusKeys.USED_MS,
      0L,
      HOME_FEED_MAX_SAFE_USAGE_MS,
      allowMissing = false,
    ) ?: return HomeFeedStatusDecode(unavailableStatus())
    val blockedUntil = readValueLong(
      values,
      prefix,
      HomeFeedStatusKeys.BLOCKED_UNTIL_ELAPSED,
      0L,
      HOME_FEED_MAX_SAFE_TIMESTAMP_MS,
      allowMissing = false,
    ) ?: return HomeFeedStatusDecode(unavailableStatus())
    val unsupportedLegacyKeys = listOf(
      HomeFeedStatusKeys.USAGE_STATE,
      HomeFeedStatusKeys.LOCKOUT_STATE,
      HomeFeedStatusKeys.RESUME_AT_ELAPSED,
      HomeFeedStatusKeys.LEGACY_BLOCKED_UNTIL_WALL,
      HomeFeedStatusKeys.GENERATION,
      HomeFeedStatusKeys.CHECKSUM,
    )
    if (legacy && unsupportedLegacyKeys.any { values.containsKey(HomeFeedStatusKeys.key(prefix, it)) }) {
      return HomeFeedStatusDecode(unavailableStatus())
    }
    val state = if (legacy) STATE_PAUSED else readValueInt(
      values, prefix, HomeFeedStatusKeys.USAGE_STATE, STATE_PAUSED, STATE_UNKNOWN, allowMissing = false,
    ) ?: return HomeFeedStatusDecode(unavailableStatus())
    val storedLockout = if (legacy) {
      if (blockedUntil > 0L) LOCKOUT_ACTIVE else LOCKOUT_NONE
    } else readValueInt(
      values, prefix, HomeFeedStatusKeys.LOCKOUT_STATE, LOCKOUT_NONE, LOCKOUT_UNKNOWN, allowMissing = false,
    ) ?: return HomeFeedStatusDecode(unavailableStatus())
    val resumeAt = if (legacy) 0L else readValueLong(
      values, prefix, HomeFeedStatusKeys.RESUME_AT_ELAPSED, 0L, HOME_FEED_MAX_SAFE_TIMESTAMP_MS, allowMissing = false,
    ) ?: return HomeFeedStatusDecode(unavailableStatus())

    if ((state == STATE_PAUSED || state == STATE_UNKNOWN) && resumeAt != 0L) {
      return HomeFeedStatusDecode(unavailableStatus())
    }
    if (current && state == STATE_ACTIVE && resumeAt == 0L) {
      return HomeFeedStatusDecode(unavailableStatus())
    }

    // Legacy records did not have an explicit lockout enum; the elapsed deadline is authoritative
    // while it belongs to this boot. The old wall deadline is deliberately never restored.
    val lockout = storedLockout
    if (lockout == LOCKOUT_ACTIVE && blockedUntil == 0L) return HomeFeedStatusDecode(unavailableStatus())
    if (lockout == LOCKOUT_NONE && blockedUntil > 0L) return HomeFeedStatusDecode(unavailableStatus())
    if (lockout == LOCKOUT_UNKNOWN && blockedUntil > 0L) return HomeFeedStatusDecode(unavailableStatus())
    if (!legacy && values.containsKey(HomeFeedStatusKeys.key(prefix, HomeFeedStatusKeys.LEGACY_BLOCKED_UNTIL_WALL))) {
      return HomeFeedStatusDecode(unavailableStatus())
    }
    if (current && storedChecksum != checksum(prefix, generation!!, boot, used, state, lockout, resumeAt, blockedUntil)) {
      return HomeFeedStatusDecode(unavailableStatus())
    }

    val sameBoot = boot == currentBoot
    if (!sameBoot) {
      val unresolvedLockout = blockedUntil > 0L || lockout == LOCKOUT_ACTIVE
      val status = HomeFeedStatus(
        usedMs = used,
        blockedUntilElapsedMs = null,
        usageState = HomeFeedUsageState.UNKNOWN,
        lockoutState = if (unresolvedLockout) HomeFeedLockoutState.UNKNOWN else HomeFeedLockoutState.NONE,
      )
      return HomeFeedStatusDecode(
        status,
        formatVersion = format,
        generation = generation,
        needsMigration = !current,
      )
    }

    if (lockout == LOCKOUT_ACTIVE && blockedUntil <= now) {
      val normalized = HomeFeedRuntimeState(
        usedMs = used,
        blockedUntilElapsedMs = blockedUntil,
        usageState = HomeFeedUsageState.PAUSED,
        lockoutState = HomeFeedLockoutState.ACTIVE,
        capturedAtElapsedMs = now,
      ).normalizedAt(now)
      return HomeFeedStatusDecode(
        normalized.toStatus(),
        formatVersion = format,
        generation = generation,
        needsMigration = true,
      )
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
      formatVersion = format,
      generation = generation,
      needsMigration = !current,
    )
  }

  fun encode(
    prefix: String,
    runtime: HomeFeedRuntimeState,
    bootCount: Long?,
    nowElapsedMs: Long,
    generation: Long = 1L,
  ): Map<String, Any?>? {
    if (runtime.storageState != HomeFeedStorageState.AVAILABLE) return null
    val boot = bootCount ?: return null
    if (boot !in 0L..Int.MAX_VALUE.toLong()) return null
    if (nowElapsedMs !in 0L..HOME_FEED_MAX_SAFE_TIMESTAMP_MS) return null
    if (generation !in 1L..HOME_FEED_MAX_GENERATION) return null
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
    val resumeAt = if (state == STATE_ACTIVE) normalizedCapturedAt else 0L
    val checksum = checksum(prefix, generation, boot, normalized.usedMs, state, lockout, resumeAt, block ?: 0L)
    return mapOf(
      HomeFeedStatusKeys.key(prefix, HomeFeedStatusKeys.FORMAT_VERSION) to CURRENT_FORMAT_VERSION,
      HomeFeedStatusKeys.key(prefix, HomeFeedStatusKeys.GENERATION) to generation,
      HomeFeedStatusKeys.key(prefix, HomeFeedStatusKeys.CHECKSUM) to checksum,
      HomeFeedStatusKeys.key(prefix, HomeFeedStatusKeys.BOOT_COUNT) to boot,
      HomeFeedStatusKeys.key(prefix, HomeFeedStatusKeys.USED_MS) to normalized.usedMs,
      HomeFeedStatusKeys.key(prefix, HomeFeedStatusKeys.USAGE_STATE) to state,
      HomeFeedStatusKeys.key(prefix, HomeFeedStatusKeys.LOCKOUT_STATE) to lockout,
      HomeFeedStatusKeys.key(prefix, HomeFeedStatusKeys.RESUME_AT_ELAPSED) to resumeAt,
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
    val value = readScalarLong(values[key]) ?: return null
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

  private fun checksum(
    prefix: String,
    generation: Long,
    boot: Long,
    used: Long,
    state: Int,
    lockout: Int,
    resumeAt: Long,
    blockedUntil: Long,
  ): Long {
    var hash = -3750763034362895579L
    fun mix(value: Long) {
      hash = (hash xor value) * 1099511628211L
    }
    prefix.forEach { mix(it.code.toLong()) }
    mix(generation)
    mix(boot)
    mix(used)
    mix(state.toLong())
    mix(lockout.toLong())
    mix(resumeAt)
    mix(blockedUntil)
    return hash
  }
}

internal fun readHomeFeedBootCount(context: Context): Long? = try {
  Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toLong()
    .takeIf { it >= 0L }
} catch (_: Settings.SettingNotFoundException) {
  null
} catch (_: RuntimeException) {
  null
}

/** Shared in-process signal so a failed service write cannot leave the UI showing an old timer. */
internal object HomeFeedStatusFailureRegistry {
  private val failedPrefixes = mutableSetOf<String>()

  @Synchronized fun markFailed(prefix: String) {
    failedPrefixes += prefix
  }

  @Synchronized fun isFailed(prefix: String): Boolean = prefix in failedPrefixes

  @Synchronized fun clear(prefix: String) {
    failedPrefixes -= prefix
  }

  @Synchronized fun clearForTests() {
    failedPrefixes.clear()
  }
}

/**
 * Durable Home-feed snapshot shared by the service and Expo module.
 *
 * A persisted ACTIVE state is deliberately read as UNKNOWN: a new service instance cannot prove
 * that X stayed in the foreground across the gap, so it must wait for a fresh Home observation.
 * The independent integrity journal also prevents an older snapshot from becoming trusted after a
 * failed or interrupted write.
 */
internal class HomeFeedStatusStore(
  private val persistence: HomeFeedStatusPersistence,
  private val currentBootCount: () -> Long?,
  private val elapsedNowMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
  internal constructor(context: Context) : this(
    persistence = SharedPreferencesHomeFeedStatusPersistence(context),
    currentBootCount = { readHomeFeedBootCount(context) },
  )

  fun instagram(nowElapsedMs: Long = elapsedNowMs()): HomeFeedStatus =
    synchronized(JOURNAL_TRANSACTION_LOCK) { read(INSTAGRAM_PREFIX, nowElapsedMs) }

  fun x(nowElapsedMs: Long = elapsedNowMs()): HomeFeedStatus =
    synchronized(JOURNAL_TRANSACTION_LOCK) { read(X_PREFIX, nowElapsedMs) }

  fun recordInstagram(runtime: HomeFeedRuntimeState): Boolean =
    synchronized(JOURNAL_TRANSACTION_LOCK) {
      write(INSTAGRAM_PREFIX, runtime.copy(usageState = HomeFeedUsageState.PAUSED))
    }

  fun recordX(runtime: HomeFeedRuntimeState): Boolean =
    synchronized(JOURNAL_TRANSACTION_LOCK) { write(X_PREFIX, runtime) }

  /**
   * Establishes a new zeroed baseline after a fresh Home observation. This is the only write path
   * allowed to recover a provider after a durable corruption or commit failure; ordinary status
   * publications remain latched unavailable until this explicit boundary succeeds.
   */
  fun recoverInstagram(nowElapsedMs: Long = elapsedNowMs()): Boolean =
    synchronized(JOURNAL_TRANSACTION_LOCK) { recover(INSTAGRAM_PREFIX, nowElapsedMs) }

  fun recoverX(nowElapsedMs: Long = elapsedNowMs()): Boolean =
    synchronized(JOURNAL_TRANSACTION_LOCK) { recover(X_PREFIX, nowElapsedMs) }

  private fun read(prefix: String, nowElapsedMs: Long): HomeFeedStatus {
    if (HomeFeedStatusFailureRegistry.isFailed(prefix)) return failureStatus()
    val values = try {
      persistence.readAll()
    } catch (_: RuntimeException) {
      return markUnavailable(prefix)
    }
    val integrityValues = try {
      persistence.readIntegrity()
    } catch (_: RuntimeException) {
      return markUnavailable(prefix)
    }
    val hasSnapshot = hasPrefix(values, prefix)
    val hasIntegrity = hasPrefix(integrityValues, prefix)
    val currentBoot = safeBootCount()
    val decoded = HomeFeedStatusCodec.decode(prefix, values, currentBoot, nowElapsedMs)
    if (decoded.status.storageState == HomeFeedStorageState.UNAVAILABLE) return markUnavailable(prefix)

    if (!hasIntegrity) {
      // A current-format snapshot without its independent journal is an integrity failure. Older
      // snapshots can be upgraded once, but only after all scalar fields validate.
      if (hasSnapshot && decoded.formatVersion == HomeFeedStatusCodec.CURRENT_FORMAT_VERSION) {
        return markUnavailable(prefix)
      }
      if (hasSnapshot) {
        return if (commitRuntime(prefix, decoded.status.toRuntimeState(nowElapsedMs), 0L, currentBoot, nowElapsedMs)) {
          decoded.status
        } else {
          failureStatus()
        }
      }
      return if (initializeIntegrity(prefix)) {
        decoded.status
      } else {
        failureStatus()
      }
    }

    val integrity = HomeFeedStatusIntegrityCodec.decode(prefix, integrityValues)
      ?: return markUnavailable(prefix)
    if (integrity.phase != HomeFeedStatusIntegrityCodec.PHASE_STABLE) return markUnavailable(prefix)
    if (!hasSnapshot) {
      // Generation zero is the only legitimate no-record state: it is the durable first-run
      // marker. Once a snapshot was initialized, an empty/truncated record is unavailable.
      return if (integrity.generation == 0L) decoded.status else markUnavailable(prefix)
    }
    if (decoded.generation != integrity.generation) return markUnavailable(prefix)
    if (decoded.needsMigration) {
      return if (commitRuntime(prefix, decoded.status.toRuntimeState(nowElapsedMs), integrity.generation, currentBoot, nowElapsedMs)) {
        decoded.status
      } else {
        failureStatus()
      }
    }
    return decoded.status
  }

  private fun write(prefix: String, runtime: HomeFeedRuntimeState, allowRecovery: Boolean = false): Boolean {
    if (!allowRecovery && HomeFeedStatusFailureRegistry.isFailed(prefix)) return false
    val nowElapsedMs = elapsedNowMs()
    val currentBoot = safeBootCount()
    if (currentBoot == null) return markFailed(prefix)

    val integrityValues = try {
      persistence.readIntegrity()
    } catch (_: RuntimeException) {
      if (!allowRecovery) return markFailed(prefix)
      emptyMap()
    }
    val hasIntegrity = hasPrefix(integrityValues, prefix)
    val currentGeneration = if (!hasIntegrity) {
      val snapshotValues = try {
        persistence.readAll()
      } catch (_: RuntimeException) {
        if (!allowRecovery) return markFailed(prefix)
        emptyMap()
      }
      if (hasPrefix(snapshotValues, prefix)) {
        val decoded = HomeFeedStatusCodec.decode(prefix, snapshotValues, currentBoot, nowElapsedMs)
        if (!allowRecovery && (decoded.status.storageState == HomeFeedStorageState.UNAVAILABLE ||
            decoded.formatVersion == HomeFeedStatusCodec.CURRENT_FORMAT_VERSION)
        ) {
          return markFailed(prefix)
        }
        if (allowRecovery) decoded.generation ?: 0L else 0L
      } else {
        0L
      }
    } else {
      val integrity = HomeFeedStatusIntegrityCodec.decode(prefix, integrityValues)
        ?: return markFailed(prefix)
      if (!allowRecovery && integrity.phase != HomeFeedStatusIntegrityCodec.PHASE_STABLE) {
        return markFailed(prefix, integrity.generation)
      }
      integrity.generation
    }
    if (!allowRecovery && currentGeneration > 0L && runtimeMatchesPersisted(
        prefix,
        runtime,
        currentGeneration,
        currentBoot,
        nowElapsedMs,
      )
    ) {
      return true
    }
    val committed = commitRuntime(prefix, runtime, currentGeneration, currentBoot, nowElapsedMs)
    if (committed && allowRecovery) HomeFeedStatusFailureRegistry.clear(prefix)
    return committed
  }

  private fun recover(prefix: String, nowElapsedMs: Long): Boolean = write(
    prefix = prefix,
    runtime = HomeFeedRuntimeState(
      usedMs = 0L,
      blockedUntilElapsedMs = null,
      usageState = HomeFeedUsageState.PAUSED,
      lockoutState = HomeFeedLockoutState.NONE,
      storageState = HomeFeedStorageState.AVAILABLE,
      capturedAtElapsedMs = nowElapsedMs,
    ),
    allowRecovery = true,
  )

  /** Avoids a three-commit journal transaction when the durable policy state is unchanged. */
  private fun runtimeMatchesPersisted(
    prefix: String,
    runtime: HomeFeedRuntimeState,
    generation: Long,
    bootCount: Long,
    nowElapsedMs: Long,
  ): Boolean {
    val persisted = try {
      persistence.readAll()
    } catch (_: RuntimeException) {
      return false
    }
    val desired = HomeFeedStatusCodec.encode(prefix, runtime, bootCount, nowElapsedMs, generation)
      ?: return false
    val decoded = HomeFeedStatusCodec.decode(prefix, persisted, bootCount, nowElapsedMs)
    if (decoded.status.storageState != HomeFeedStorageState.AVAILABLE ||
      decoded.needsMigration || decoded.generation != generation
    ) return false
    return DURABLE_STATE_KEYS.all { suffix ->
      val key = HomeFeedStatusKeys.key(prefix, suffix)
      val desiredValue = desired[key]
      if (desiredValue == null) !persisted.containsKey(key) else persisted[key] == desiredValue
    }
  }

  private fun commitRuntime(
    prefix: String,
    runtime: HomeFeedRuntimeState,
    currentGeneration: Long,
    currentBoot: Long?,
    nowElapsedMs: Long,
  ): Boolean {
    val nextGeneration = nextGeneration(currentGeneration) ?: return markFailed(prefix)
    val values = HomeFeedStatusCodec.encode(prefix, runtime, currentBoot, nowElapsedMs, nextGeneration)
      ?: return markFailed(prefix)
    val pending = HomeFeedStatusIntegrityCodec.values(
      prefix,
      HomeFeedStatusIntegrityCodec.PHASE_PENDING,
      currentGeneration,
      nextGeneration,
    )
    if (!safeCommitIntegrity(pending)) {
      return markCommitFailure(prefix, currentGeneration, nextGeneration)
    }
    if (!safeCommit(values)) {
      return markCommitFailure(prefix, currentGeneration, nextGeneration)
    }
    val stable = HomeFeedStatusIntegrityCodec.values(
      prefix,
      HomeFeedStatusIntegrityCodec.PHASE_STABLE,
      nextGeneration,
      0L,
    )
    if (!safeCommitIntegrity(stable)) {
      return markCommitFailure(prefix, currentGeneration, nextGeneration)
    }
    return true
  }

  private fun initializeIntegrity(prefix: String): Boolean {
    val initialized = safeCommitIntegrity(
      HomeFeedStatusIntegrityCodec.values(
        prefix,
        HomeFeedStatusIntegrityCodec.PHASE_STABLE,
        0L,
        0L,
      ),
    )
    if (!initialized) markFailed(prefix, 0L)
    return initialized
  }

  private fun safeCommit(values: Map<String, Any?>): Boolean = try {
    persistence.commit(values)
  } catch (_: RuntimeException) {
    false
  }

  private fun safeCommitIntegrity(values: Map<String, Any?>): Boolean = try {
    persistence.commitIntegrity(values)
  } catch (_: RuntimeException) {
    false
  }

  private fun markCommitFailure(prefix: String, currentGeneration: Long, nextGeneration: Long): Boolean {
    HomeFeedStatusFailureRegistry.markFailed(prefix)
    persistFailureMarker(prefix, currentGeneration, nextGeneration)
    return false
  }

  private fun markFailed(prefix: String, generationHint: Long? = null): Boolean {
    HomeFeedStatusFailureRegistry.markFailed(prefix)
    persistFailureMarker(prefix, generationHint)
    return false
  }

  private fun markUnavailable(prefix: String): HomeFeedStatus {
    markFailed(prefix)
    return failureStatus()
  }

  /**
   * Make a failed read/encode/commit durable whenever either persistence seam is still writable.
   * The metadata journal is independent from the snapshot; invalidating the snapshot is the
   * fallback if that journal cannot be updated. If both commits fail, no API can manufacture a
   * durable bit, so the next read remains conservative whenever either boundary is observable.
   */
  private fun persistFailureMarker(
    prefix: String,
    generationHint: Long? = null,
    nextGenerationHint: Long? = null,
  ) {
    val generation = generationHint?.takeIf { it in 0L..HOME_FEED_MAX_GENERATION } ?: run {
      try {
        HomeFeedStatusIntegrityCodec.decode(prefix, persistence.readIntegrity())?.generation
      } catch (_: RuntimeException) {
        null
      }
    } ?: 0L
    val nextGeneration = nextGenerationHint
      ?.takeIf { it in 1L..HOME_FEED_MAX_GENERATION && it == generation + 1L }
      ?: nextGeneration(generation)
    val marker = if (nextGeneration != null) {
      HomeFeedStatusIntegrityCodec.values(
        prefix,
        HomeFeedStatusIntegrityCodec.PHASE_FAILED,
        generation,
        nextGeneration,
      )
    } else {
      HomeFeedStatusIntegrityCodec.values(
        prefix,
        HomeFeedStatusIntegrityCodec.PHASE_UNAVAILABLE,
        generation,
        0L,
      )
    }
    if (!safeCommitIntegrity(marker)) {
      // An invalid format is sufficient to reject an older snapshot even if the journal is
      // temporarily read-only. This also turns a failed first initialization into a durable
      // non-empty record instead of a future-looking first run.
      safeCommit(
        mapOf(HomeFeedStatusKeys.key(prefix, HomeFeedStatusKeys.FORMAT_VERSION) to HomeFeedStatusCodec.INVALID_FORMAT_VERSION),
      )
    }
  }

  private fun safeBootCount(): Long? = try {
    currentBootCount()?.takeIf { it in 0L..Int.MAX_VALUE.toLong() }
  } catch (_: RuntimeException) {
    null
  }

  private fun nextGeneration(current: Long): Long? =
    if (current in 0L until HOME_FEED_MAX_GENERATION) current + 1L else null

  private fun hasPrefix(values: Map<String, Any?>, prefix: String): Boolean {
    val marker = "${prefix}_"
    return values.keys.any { it.startsWith(marker) }
  }

  private fun HomeFeedStatus.toRuntimeState(nowElapsedMs: Long) = HomeFeedRuntimeState(
    usedMs = usedMs,
    blockedUntilElapsedMs = blockedUntilElapsedMs,
    usageState = usageState,
    lockoutState = lockoutState,
    storageState = storageState,
    capturedAtElapsedMs = nowElapsedMs,
  )

  private fun failureStatus() = HomeFeedStatus(
    usedMs = HOME_FEED_SAFE_FAIL_CLOSED_USAGE_MS,
    blockedUntilElapsedMs = null,
    usageState = HomeFeedUsageState.UNKNOWN,
    storageState = HomeFeedStorageState.UNAVAILABLE,
    lockoutState = HomeFeedLockoutState.UNKNOWN,
  )

  companion object {
    /** Shared by service and Expo-module store instances in this process. */
    private val JOURNAL_TRANSACTION_LOCK = Any()
    private val DURABLE_STATE_KEYS = listOf(
      HomeFeedStatusKeys.FORMAT_VERSION,
      HomeFeedStatusKeys.BOOT_COUNT,
      HomeFeedStatusKeys.USED_MS,
      HomeFeedStatusKeys.USAGE_STATE,
      HomeFeedStatusKeys.LOCKOUT_STATE,
      HomeFeedStatusKeys.RESUME_AT_ELAPSED,
      HomeFeedStatusKeys.BLOCKED_UNTIL_ELAPSED,
      HomeFeedStatusKeys.LEGACY_BLOCKED_UNTIL_WALL,
    )
    private const val INSTAGRAM_PREFIX = "instagram"
    private const val X_PREFIX = "x"
  }
}

private fun readScalarLong(raw: Any?): Long? = when (raw) {
  is Byte -> raw.toLong()
  is Short -> raw.toLong()
  is Int -> raw.toLong()
  is Long -> raw
  else -> null
}
