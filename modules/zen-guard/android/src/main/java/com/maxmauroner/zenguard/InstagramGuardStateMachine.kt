package com.maxmauroner.zenguard

internal data class InstagramGuardSettings(
  val waitMs: Long = 30_000,
  val reelsWindowMs: Long = 300_000,
  val homeAllowanceMs: Long = 300_000,
  val homeLockoutMs: Long = 3_600_000,
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

internal data class InstagramGuardDebugState(
  val homeElapsedMs: Long,
  val reelsWindowRemainingMs: Long?,
  val dmProvenanceAgeMs: Long?,
  val dmThreadActive: Boolean,
  val blockerReason: InstagramBlockReason?,
)

/**
 * The evidence the Android accessibility adapter collected for one policy event.
 *
 * A surface classification alone is not enough to establish intent. In particular, a DM list
 * visit is not provenance for opening Reels, and a scroll from a nested Reel child is not a swipe
 * of the full-screen pager.
 */
internal data class InstagramGuardInput(
  val surface: InstagramSurface,
  val nowMs: Long,
  val isForeground: Boolean = true,
  val dmThreadClicked: Boolean = false,
  val dmThreadVisible: Boolean = false,
  val reelPagerVisible: Boolean = false,
  val reelPagerScrolled: Boolean = false,
)

/** Pure policy engine. Android views and accessibility actions stay outside this class. */
internal class InstagramGuardStateMachine(
  private val dmProvenanceMs: Long = 3_000,
  private val reelEntryScrollGraceMs: Long = 750,
) {
  private var lastDmThreadClickAt: Long? = null
  private var dmThreadActive = false
  private var dmThreadLostAt: Long? = null
  private var reelsAllowedFromDm = false
  private var dmReelOpenedAt: Long? = null
  private var reelsWindowUntil: Long? = null

  private var homeElapsedMs = 0L
  private var homeLastActiveAt: Long? = null
  private var homeBlockedUntil: Long? = null

  private var blocker: InstagramGuardAction.ShowBlocker? = null
  private var blockedSurface: InstagramSurface? = null

  /**
   * Compatibility entry point for callers that already classified a surface. Reel scrolls from
   * this entry point are treated as pager events because the caller has no finer evidence. The
   * Android service uses the evidence-bearing overload below.
   */
  fun next(
    surface: InstagramSurface,
    isScrollEvent: Boolean,
    nowMs: Long,
    settings: InstagramGuardSettings,
    dmThreadClicked: Boolean = false,
    dmThreadVisible: Boolean = false,
    reelPagerVisible: Boolean = surface == InstagramSurface.REELS_VIEWER,
    isForeground: Boolean = true,
  ): InstagramGuardAction = next(
    InstagramGuardInput(
      surface = surface,
      nowMs = nowMs,
      isForeground = isForeground,
      dmThreadClicked = dmThreadClicked,
      dmThreadVisible = dmThreadVisible,
      reelPagerVisible = reelPagerVisible,
      reelPagerScrolled = isScrollEvent,
    ),
    settings,
  )

  fun next(input: InstagramGuardInput, settings: InstagramGuardSettings): InstagramGuardAction {
    if (!input.isForeground) {
      onAppBackground()
      return InstagramGuardAction.None
    }

    if (input.surface == InstagramSurface.UNKNOWN) {
      onSurfaceLost()
      return InstagramGuardAction.None
    }

    clearStaleBlocker(input)
    blocker?.let { return it }

    return when (input.surface) {
      InstagramSurface.DIRECT_MESSAGES -> handleDirectMessages(input)
      InstagramSurface.REELS_VIEWER -> handleReels(input, settings)
      InstagramSurface.HOME_FEED -> handleHome(input, settings)
      InstagramSurface.EXPLORE -> handleExplore(input, settings)
      InstagramSurface.UNKNOWN -> InstagramGuardAction.None
    }
  }

  fun continueReels(nowMs: Long, settings: InstagramGuardSettings): Boolean {
    val active = blocker ?: return false
    val availableAt = active.continueAvailableAt ?: return false
    if (active.reason != InstagramBlockReason.REELS_SWIPE &&
      active.reason != InstagramBlockReason.REELS_WINDOW_EXPIRED
    ) {
      return false
    }
    if (nowMs < availableAt) return false

    blocker = null
    blockedSurface = null
    reelsAllowedFromDm = true
    dmReelOpenedAt = null
    reelsWindowUntil = nowMs + settings.reelsWindowMs
    return true
  }

  fun debugState(nowMs: Long): InstagramGuardDebugState = InstagramGuardDebugState(
    homeElapsedMs = homeElapsedMs,
    reelsWindowRemainingMs = reelsWindowUntil?.let { (it - nowMs).coerceAtLeast(0L) },
    dmProvenanceAgeMs = lastDmThreadClickAt?.let { (nowMs - it).coerceAtLeast(0L) },
    dmThreadActive = dmThreadActive,
    blockerReason = blocker?.reason,
  )

  fun homeElapsedMs(): Long = homeElapsedMs

  /** Snapshot for presentation only; enforcement continues to use the private state above. */
  fun homeRuntimeState(nowMs: Long): HomeFeedRuntimeState {
    val pendingMs = if (homeBlockedUntil == null) {
      homeLastActiveAt?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
    } else {
      0L
    }
    return HomeFeedRuntimeState(homeElapsedMs + pendingMs, homeBlockedUntil)
  }

  /** Clears enforcement state when the service loses the Instagram app or a known surface. */
  fun onSurfaceLost() {
    blocker = null
    blockedSurface = null
    resetHomeSession()
    resetReelsProvenance()
  }

  /**
   * Handles the short-lived empty/foreign tree Android reports while Instagram replaces one
   * screen with another. Keep Reel provenance through that transition so a Reel opened from an
   * active DM thread is not mistaken for a direct Reels launch.
   */
  fun onTransientSurfaceLost(nowMs: Long) {
    homeLastActiveAt = null
    if (dmThreadActive) dmThreadLostAt = nowMs
  }

  /**
   * Pauses foreground accounting and drops blockers while Android is backgrounded or interrupted.
   * The active Home allowance is kept so only foreground time is charged on resume.
   */
  fun onAppBackground(nowMs: Long? = null, settings: InstagramGuardSettings? = null) {
    if (homeBlockedUntil == null && nowMs != null) {
      homeLastActiveAt?.let { homeElapsedMs += (nowMs - it).coerceAtLeast(0L) }
      if (settings != null && homeElapsedMs >= settings.homeAllowanceMs) {
        homeBlockedUntil = nowMs + settings.homeLockoutMs
      }
    }
    blocker = null
    blockedSurface = null
    homeLastActiveAt = null
    resetReelsProvenance()
  }

  /** Leaves the blocked surface without clearing an active Home lockout. */
  fun leaveBlockedSurface() {
    blocker = null
    blockedSurface = null
    resetHomeSession()
    resetReelsProvenance()
  }

  private fun handleDirectMessages(input: InstagramGuardInput): InstagramGuardAction {
    resetHomeSession()
    reelsAllowedFromDm = false
    dmReelOpenedAt = null
    reelsWindowUntil = null

    if (input.dmThreadVisible) {
      // Instagram does not always dispatch TYPE_VIEW_CLICKED for a Reel media tile. An observed
      // open thread is stronger provenance than the Messages tab and remains valid until the
      // surface changes, so the first Reel opened from that thread is still intentional.
      dmThreadActive = true
      lastDmThreadClickAt = input.nowMs
      dmThreadLostAt = null
    } else if (input.dmThreadClicked) {
      dmThreadActive = false
      lastDmThreadClickAt = input.nowMs
      dmThreadLostAt = null
    } else {
      dmThreadActive = false
      lastDmThreadClickAt = null
      dmThreadLostAt = null
    }
    return InstagramGuardAction.None
  }

  private fun handleReels(
    input: InstagramGuardInput,
    settings: InstagramGuardSettings,
  ): InstagramGuardAction {
    resetHomeSession()

    // The detector can see a Reel child before the full-screen pager. Such a tree is observational
    // evidence only and must not enter or advance Reel policy.
    if (!input.reelPagerVisible) return InstagramGuardAction.None

    val windowUntil = reelsWindowUntil
    if (windowUntil != null) {
      return if (input.nowMs < windowUntil) InstagramGuardAction.None
      else block(InstagramBlockReason.REELS_WINDOW_EXPIRED, input.nowMs + settings.waitMs)
    }

    if (!reelsAllowedFromDm) {
      val cameFromDmClick = lastDmThreadClickAt?.let { clickAt ->
        input.nowMs - clickAt in 0..dmProvenanceMs
      } == true
      val cameFromActiveDmThread = dmThreadActive && dmThreadLostAt?.let { lostAt ->
        input.nowMs - lostAt in 0..dmProvenanceMs
      } != false
      val cameFromDm = cameFromDmClick || cameFromActiveDmThread
      if (!cameFromDm) return block(InstagramBlockReason.REELS_ENTRY, null)

      reelsAllowedFromDm = true
      dmReelOpenedAt = input.nowMs
      return InstagramGuardAction.None
    }

    if (!input.reelPagerScrolled) return InstagramGuardAction.None
    val openedAt = dmReelOpenedAt
    return if (openedAt != null && input.nowMs - openedAt <= reelEntryScrollGraceMs) {
      // ViewPager can emit a scroll while it settles on the first Reel. Give that automatic
      // transition a small grace window after the pager itself first becomes visible.
      InstagramGuardAction.None
    } else {
      block(InstagramBlockReason.REELS_SWIPE, input.nowMs + settings.waitMs)
    }
  }

  private fun handleHome(
    input: InstagramGuardInput,
    settings: InstagramGuardSettings,
  ): InstagramGuardAction {
    resetReelsProvenance()

    val blockedUntil = homeBlockedUntil
    if (blockedUntil != null) {
      if (input.nowMs < blockedUntil) return block(InstagramBlockReason.HOME_LIMIT, null)
      homeBlockedUntil = null
      resetHomeSession()
    }

    val previousActiveAt = homeLastActiveAt
    if (previousActiveAt != null) {
      homeElapsedMs += (input.nowMs - previousActiveAt).coerceAtLeast(0L)
    }
    homeLastActiveAt = input.nowMs

    return if (homeElapsedMs >= settings.homeAllowanceMs) {
      homeBlockedUntil = input.nowMs + settings.homeLockoutMs
      block(InstagramBlockReason.HOME_LIMIT, null)
    } else {
      InstagramGuardAction.None
    }
  }

  private fun handleExplore(
    input: InstagramGuardInput,
    settings: InstagramGuardSettings,
  ): InstagramGuardAction {
    resetHomeSession()
    resetReelsProvenance()
    return if (settings.exploreBlocked) {
      block(InstagramBlockReason.EXPLORE, null)
    } else {
      InstagramGuardAction.None
    }
  }

  private fun clearStaleBlocker(input: InstagramGuardInput) {
    val active = blocker ?: return
    val sameSurface = blockedSurface == input.surface
    val homeLockExpired = active.reason == InstagramBlockReason.HOME_LIMIT &&
      homeBlockedUntil?.let { input.nowMs >= it } == true
    if (!sameSurface || homeLockExpired) {
      blocker = null
      blockedSurface = null
    }
    if (homeLockExpired) {
      homeBlockedUntil = null
      resetHomeSession()
    }
  }

  private fun resetHomeSession() {
    homeElapsedMs = 0L
    homeLastActiveAt = null
  }

  private fun resetReelsProvenance() {
    lastDmThreadClickAt = null
    dmThreadActive = false
    dmThreadLostAt = null
    reelsAllowedFromDm = false
    dmReelOpenedAt = null
    reelsWindowUntil = null
  }

  private fun block(reason: InstagramBlockReason, continueAt: Long?): InstagramGuardAction.ShowBlocker {
    return InstagramGuardAction.ShowBlocker(reason, continueAt).also {
      blocker = it
      blockedSurface = when (reason) {
        InstagramBlockReason.HOME_LIMIT -> InstagramSurface.HOME_FEED
        InstagramBlockReason.EXPLORE -> InstagramSurface.EXPLORE
        InstagramBlockReason.REELS_ENTRY,
        InstagramBlockReason.REELS_SWIPE,
        InstagramBlockReason.REELS_WINDOW_EXPIRED,
        -> InstagramSurface.REELS_VIEWER
      }
    }
  }
}
