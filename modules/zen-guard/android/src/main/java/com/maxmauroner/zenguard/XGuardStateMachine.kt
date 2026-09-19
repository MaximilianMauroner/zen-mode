package com.maxmauroner.zenguard

internal enum class XSurface { HOME, VIDEO, OTHER, UNKNOWN }
internal enum class XAction { NONE, HOME_BREAK, HOME_UNAVAILABLE, LEAVE_VIDEO }
internal data class XSettings(
  val homeEnabled: Boolean = true,
  val videosEnabled: Boolean = true,
  val homeAllowanceMs: Long = 300_000L,
  val homeLockoutMs: Long = 3_600_000L,
)

/** Home sessions match Instagram; only a verified video-pager scroll consumes the video visit. */
internal class XGuardStateMachine {
  private var homeElapsedMs = 0L
  private var lastHomeAt: Long? = null
  private var homeBlockedUntil: Long? = null
  private var homeLockoutState = HomeFeedLockoutState.NONE
  private var unknownLockoutSinceMs: Long? = null
  private var homeObservationRequired = false
  private var storageUnavailable = false
  private var lastVideoExitAt: Long? = null

  fun next(surface: XSurface, nowMs: Long, settings: XSettings, videoPagerAdvanced: Boolean = false): XAction {
    val now = nowMs.coerceIn(0L, HOME_FEED_MAX_SAFE_TIMESTAMP_MS)
    normalizeExpiredLockout(now)

    if (surface == XSurface.UNKNOWN) {
      pause(now, settings)
      homeObservationRequired = true
      return XAction.NONE
    }
    if (!settings.homeEnabled) {
      resetHomeSession()
      homeBlockedUntil = null
      homeLockoutState = HomeFeedLockoutState.NONE
      unknownLockoutSinceMs = null
      homeObservationRequired = false
    } else if (surface != XSurface.HOME) {
      resetHomeSession()
    }
    if (surface != XSurface.VIDEO) lastVideoExitAt = null
    if (surface == XSurface.HOME && settings.homeEnabled) {
      if (storageUnavailable) return XAction.HOME_UNAVAILABLE
      if (homeLockoutState == HomeFeedLockoutState.UNKNOWN) {
        val unknownSince = unknownLockoutSinceMs
        val lockoutElapsed = unknownSince?.let { elapsedMsSince(now, it) } ?: 0L
        if (unknownSince == null || lockoutElapsed < settings.homeLockoutMs.coerceAtLeast(0L)) {
          return XAction.HOME_UNAVAILABLE
        }
        // We waited a complete fresh-boot lockout interval without trusting wall time.
        homeLockoutState = HomeFeedLockoutState.NONE
        unknownLockoutSinceMs = null
        resetHomeSession()
        homeObservationRequired = true
      }
      val blockedUntil = homeBlockedUntil
      if (blockedUntil != null) {
        if (now < blockedUntil) return XAction.HOME_BREAK
        normalizeExpiredLockout(now)
      }
      if (homeObservationRequired) {
        // A reconnect or unverifiable root gap never contributes its whole gap to X usage.
        homeObservationRequired = false
        lastHomeAt = now
      } else {
        lastHomeAt?.let { homeElapsedMs = saturatingUsageAdd(homeElapsedMs, elapsedMsSince(now, it)) }
        lastHomeAt = now
      }
      if (homeElapsedMs >= settings.homeAllowanceMs.coerceAtLeast(0L)) {
        beginLockout(now, settings)
        return XAction.HOME_BREAK
      }
    }
    if (surface == XSurface.VIDEO && settings.videosEnabled && videoPagerAdvanced) {
      if (lastVideoExitAt?.let { elapsedMsSince(now, it) < 900L } == true) return XAction.NONE
      lastVideoExitAt = now
      return XAction.LEAVE_VIDEO
    }
    return XAction.NONE
  }

  fun pause(nowMs: Long? = null, settings: XSettings? = null) {
    if (storageUnavailable) {
      lastHomeAt = null
      homeObservationRequired = true
      return
    }
    val now = nowMs?.coerceIn(0L, HOME_FEED_MAX_SAFE_TIMESTAMP_MS)
    if (homeLockoutState == HomeFeedLockoutState.NONE && now != null) {
      lastHomeAt?.let { homeElapsedMs = saturatingUsageAdd(homeElapsedMs, elapsedMsSince(now, it)) }
      if (settings != null && homeElapsedMs >= settings.homeAllowanceMs.coerceAtLeast(0L)) {
        beginLockout(now, settings)
      }
    }
    lastHomeAt = null
  }

  /** Ends the known interval and marks the following Home visit as requiring fresh evidence. */
  fun markUnverifiableGap(nowMs: Long, settings: XSettings? = null) {
    pause(nowMs, settings)
    homeObservationRequired = true
  }

  fun leaveBlockedSurface() { lastHomeAt = null }

  fun reset() {
    resetHomeSession()
    homeBlockedUntil = null
    homeLockoutState = HomeFeedLockoutState.NONE
    unknownLockoutSinceMs = null
    homeObservationRequired = false
    lastVideoExitAt = null
  }

  /**
   * Restores only durable usage and known lockout state. A persisted ACTIVE session is never
   * resumed: without foreground continuity, the next verified Home observation starts a new
   * interval at that observation's monotonic timestamp.
   */
  fun restore(state: HomeFeedRuntimeState, nowMs: Long = state.capturedAtElapsedMs ?: 0L) {
    val normalized = state.normalizedAt(nowMs)
    homeElapsedMs = normalized.usedMs.coerceIn(0L, HOME_FEED_MAX_SAFE_USAGE_MS)
    storageUnavailable = normalized.storageState == HomeFeedStorageState.UNAVAILABLE
    homeLockoutState = normalized.lockoutState
    homeBlockedUntil = if (homeLockoutState == HomeFeedLockoutState.ACTIVE) {
      normalized.blockedUntilElapsedMs
    } else {
      null
    }
    unknownLockoutSinceMs = if (homeLockoutState == HomeFeedLockoutState.UNKNOWN) {
      nowMs.coerceIn(0L, HOME_FEED_MAX_SAFE_TIMESTAMP_MS)
    } else {
      null
    }
    homeObservationRequired = storageUnavailable ||
      normalized.usageState == HomeFeedUsageState.UNKNOWN ||
      homeLockoutState == HomeFeedLockoutState.UNKNOWN
    // Never restore the active timestamp. This is the core service-gap boundary.
    lastHomeAt = null
    lastVideoExitAt = null
  }

  fun markStorageUnavailable() {
    storageUnavailable = true
    homeLockoutState = HomeFeedLockoutState.UNKNOWN
    homeBlockedUntil = null
    unknownLockoutSinceMs = null
    homeObservationRequired = true
    lastHomeAt = null
  }

  fun requiresFreshHomeObservation(): Boolean = homeObservationRequired

  /** Snapshot for presentation and persistence; it also normalizes an expired lockout in memory. */
  fun homeRuntimeState(nowMs: Long): HomeFeedRuntimeState {
    val now = nowMs.coerceIn(0L, HOME_FEED_MAX_SAFE_TIMESTAMP_MS)
    normalizeExpiredLockout(now)
    val pendingMs = if (
      !storageUnavailable &&
      homeLockoutState == HomeFeedLockoutState.NONE &&
      !homeObservationRequired
    ) {
      lastHomeAt?.let { elapsedMsSince(now, it) } ?: 0L
    } else {
      0L
    }
    val usageState = when {
      storageUnavailable || homeLockoutState == HomeFeedLockoutState.UNKNOWN || homeObservationRequired -> HomeFeedUsageState.UNKNOWN
      lastHomeAt != null && homeLockoutState == HomeFeedLockoutState.NONE -> HomeFeedUsageState.ACTIVE
      else -> HomeFeedUsageState.PAUSED
    }
    return HomeFeedRuntimeState(
      usedMs = saturatingUsageAdd(homeElapsedMs, pendingMs),
      blockedUntilElapsedMs = homeBlockedUntil,
      usageState = usageState,
      lockoutState = homeLockoutState,
      storageState = if (storageUnavailable) HomeFeedStorageState.UNAVAILABLE else HomeFeedStorageState.AVAILABLE,
      capturedAtElapsedMs = now,
    ).normalizedAt(now)
  }

  private fun beginLockout(nowMs: Long, settings: XSettings) {
    homeBlockedUntil = saturatingTimestampAdd(nowMs, settings.homeLockoutMs)
    homeLockoutState = HomeFeedLockoutState.ACTIVE
    unknownLockoutSinceMs = null
    lastHomeAt = null
  }

  private fun normalizeExpiredLockout(nowMs: Long) {
    if (homeLockoutState == HomeFeedLockoutState.ACTIVE && homeBlockedUntil != null && nowMs >= homeBlockedUntil!!) {
      // A completed lockout is a clean new visit boundary. Do not serialize allowance-exhausted
      // usage together with a cleared deadline, or the next Home observation will re-lock.
      homeElapsedMs = 0L
      homeBlockedUntil = null
      homeLockoutState = HomeFeedLockoutState.NONE
      unknownLockoutSinceMs = null
      lastHomeAt = null
      homeObservationRequired = false
    }
    if (homeLockoutState == HomeFeedLockoutState.ACTIVE && homeBlockedUntil == null) {
      homeLockoutState = HomeFeedLockoutState.UNKNOWN
      unknownLockoutSinceMs = nowMs
      homeObservationRequired = true
      lastHomeAt = null
    }
  }

  private fun resetHomeSession() {
    homeElapsedMs = 0L
    lastHomeAt = null
  }
}

/** IDs observed in X's Compose accessibility tree. Post text is never a surface signal. */
internal object XDetector {
  fun detect(nodes: List<NodeSignal>): XSurface = when {
    nodes.any { it.viewId == "VideoTab" } -> XSurface.VIDEO
    nodes.any { it.viewId == "scaffold_home_tabbed" } -> XSurface.HOME
    nodes.any { it.viewId in setOf("Search", "PostDetail", "MainLanding") } -> XSurface.OTHER
    else -> XSurface.UNKNOWN
  }
}
