package com.maxmauroner.zenguard

internal data class InstagramGuardSettings(
  val waitMs: Long = 30_000,
  val reelsWindowMs: Long = 300_000,
  val homeAllowanceMs: Long = 300_000,
  val exploreBlocked: Boolean = true,
)

internal enum class InstagramBlockReason {
  REELS_ENTRY,
  REELS_SWIPE,
  REELS_WINDOW_EXPIRED,
  HOME_LIMIT,
  EXPLORE,
}

internal sealed interface InstagramGuardAction {
  data object None : InstagramGuardAction
  data class ShowBlocker(val reason: InstagramBlockReason, val continueAvailableAt: Long?) : InstagramGuardAction
}

/** Pure policy engine. Android views and accessibility actions stay outside this class. */
internal class InstagramGuardStateMachine(
  private val dmProvenanceMs: Long = 15_000,
) {
  private var lastDmAt: Long? = null
  private var reelsAllowedFromDm = false
  private var reelsWindowUntil: Long? = null
  private var homeStartedAt: Long? = null
  private var blocker: InstagramGuardAction.ShowBlocker? = null

  fun next(
    surface: InstagramSurface,
    isScrollEvent: Boolean,
    nowMs: Long,
    settings: InstagramGuardSettings,
  ): InstagramGuardAction {
    blocker?.let { return it }

    when (surface) {
      InstagramSurface.DIRECT_MESSAGES -> {
        resetHomeSession()
        lastDmAt = nowMs
        reelsAllowedFromDm = false
        reelsWindowUntil = null
        return InstagramGuardAction.None
      }
      InstagramSurface.REELS_VIEWER -> {
        resetHomeSession()
        return handleReels(isScrollEvent, nowMs, settings)
      }
      InstagramSurface.HOME_FEED -> return handleHome(nowMs, settings)
      InstagramSurface.EXPLORE -> {
        resetHomeSession()
        resetReelsProvenance()
        if (settings.exploreBlocked) return block(InstagramBlockReason.EXPLORE, null)
      }
      InstagramSurface.UNKNOWN -> Unit
    }
    return InstagramGuardAction.None
  }

  fun continueReels(nowMs: Long, settings: InstagramGuardSettings): Boolean {
    val active = blocker ?: return false
    val availableAt = active.continueAvailableAt ?: return false
    if (nowMs < availableAt) return false
    blocker = null
    reelsAllowedFromDm = true
    reelsWindowUntil = nowMs + settings.reelsWindowMs
    return true
  }

  fun leaveBlockedSurface() {
    blocker = null
    resetReelsProvenance()
  }

  private fun handleReels(
    isScrollEvent: Boolean,
    nowMs: Long,
    settings: InstagramGuardSettings,
  ): InstagramGuardAction {
    val windowUntil = reelsWindowUntil
    if (windowUntil != null) {
      return if (nowMs < windowUntil) InstagramGuardAction.None
      else block(InstagramBlockReason.REELS_WINDOW_EXPIRED, nowMs + settings.waitMs)
    }

    if (!reelsAllowedFromDm) {
      val cameFromDm = lastDmAt?.let { nowMs - it in 0..dmProvenanceMs } == true
      if (!cameFromDm) return block(InstagramBlockReason.REELS_ENTRY, null)
      reelsAllowedFromDm = true
      return InstagramGuardAction.None
    }

    return if (isScrollEvent) block(InstagramBlockReason.REELS_SWIPE, nowMs + settings.waitMs)
    else InstagramGuardAction.None
  }

  private fun handleHome(nowMs: Long, settings: InstagramGuardSettings): InstagramGuardAction {
    resetReelsProvenance()
    val startedAt = homeStartedAt ?: nowMs.also { homeStartedAt = it }
    return if (nowMs - startedAt >= settings.homeAllowanceMs) {
      block(InstagramBlockReason.HOME_LIMIT, null)
    } else InstagramGuardAction.None
  }

  private fun resetHomeSession() {
    homeStartedAt = null
  }

  private fun resetReelsProvenance() {
    lastDmAt = null
    reelsAllowedFromDm = false
    reelsWindowUntil = null
  }

  private fun block(reason: InstagramBlockReason, continueAt: Long?): InstagramGuardAction.ShowBlocker {
    return InstagramGuardAction.ShowBlocker(reason, continueAt).also { blocker = it }
  }
}
