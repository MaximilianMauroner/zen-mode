package com.maxmauroner.zenguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramGuardStateMachineTest {
  private val settings = InstagramGuardSettings(
    waitMs = 30_000,
    reelsWindowMs = 300_000,
    homeAllowanceMs = 300_000,
    exploreBlocked = true,
  )

  @Test
  fun serviceRestartPreservesHomeLockout() {
    val beforeRestart = InstagramGuardStateMachine()
    beforeRestart.next(InstagramSurface.HOME_FEED, false, 1_000, settings)
    beforeRestart.next(InstagramSurface.HOME_FEED, false, 301_000, settings)
    val persisted = beforeRestart.homeRuntimeState(301_000)

    val afterRestart = InstagramGuardStateMachine()
    afterRestart.restore(persisted, 301_001)
    assertEquals(persisted.blockedUntilElapsedMs, afterRestart.homeRuntimeState(301_001).blockedUntilElapsedMs)
    assertEquals(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.HOME_LIMIT, null),
      afterRestart.next(InstagramSurface.HOME_FEED, false, 301_001, settings),
    )
    afterRestart.next(InstagramSurface.DIRECT_MESSAGES, false, 302_000, settings)
    assertEquals(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.HOME_LIMIT, null),
      afterRestart.next(InstagramSurface.HOME_FEED, false, 3_900_999, settings),
    )
    assertEquals(InstagramGuardAction.None, afterRestart.next(InstagramSurface.HOME_FEED, false, 3_901_000, settings))
    assertEquals(0L, afterRestart.homeRuntimeState(3_901_000).usedMs)
  }

  @Test
  fun serviceRestartPreservesPartialUsageWithoutChargingUnobservedGap() {
    val beforeRestart = InstagramGuardStateMachine()
    beforeRestart.next(InstagramSurface.HOME_FEED, false, 1_000, settings)
    val afterRestart = InstagramGuardStateMachine()
    afterRestart.restore(beforeRestart.homeRuntimeState(121_000), 501_000)

    assertEquals(120_000L, afterRestart.homeRuntimeState(501_000).usedMs)
    assertEquals(HomeFeedUsageState.UNKNOWN, afterRestart.homeRuntimeState(501_000).usageState)
    afterRestart.onTransientSurfaceLost(550_000)
    assertEquals(InstagramGuardAction.None, afterRestart.next(InstagramSurface.HOME_FEED, false, 600_000, settings))
    assertEquals(120_000L, afterRestart.homeRuntimeState(600_000).usedMs)
    assertEquals(InstagramGuardAction.None, afterRestart.next(InstagramSurface.HOME_FEED, false, 779_999, settings))
    assertEquals(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.HOME_LIMIT, null),
      afterRestart.next(InstagramSurface.HOME_FEED, false, 780_000, settings),
    )
  }

  @Test
  fun knownNonHomeSurfaceEndsRestoredPartialVisit() {
    val afterRestart = InstagramGuardStateMachine()
    afterRestart.restore(HomeFeedRuntimeState(120_000L, null, HomeFeedUsageState.UNKNOWN), 500_000)
    afterRestart.next(InstagramSurface.DIRECT_MESSAGES, false, 501_000, settings)
    assertEquals(0L, afterRestart.homeRuntimeState(501_000).usedMs)
    assertEquals(InstagramGuardAction.None, afterRestart.next(InstagramSurface.HOME_FEED, false, 600_000, settings))
    assertEquals(0L, afterRestart.homeRuntimeState(600_000).usedMs)
  }

  @Test
  fun expiredRestoredLockoutStartsFreshAllowance() {
    val afterRestart = InstagramGuardStateMachine()
    afterRestart.restore(
      HomeFeedRuntimeState(300_000L, 3_901_000L, capturedAtElapsedMs = 301_000L),
      3_901_000L,
    )
    assertEquals(0L, afterRestart.homeRuntimeState(3_901_000).usedMs)
    assertEquals(HomeFeedLockoutState.NONE, afterRestart.homeRuntimeState(3_901_000).lockoutState)
    assertEquals(InstagramGuardAction.None, afterRestart.next(InstagramSurface.HOME_FEED, false, 3_901_001, settings))
  }

  @Test
  fun expiredLockoutSnapshotDoesNotRestoreExhaustedUsage() {
    val beforeRestart = InstagramGuardStateMachine()
    beforeRestart.next(InstagramSurface.HOME_FEED, false, 1_000, settings)
    beforeRestart.next(InstagramSurface.HOME_FEED, false, 301_000, settings)
    val expired = beforeRestart.homeRuntimeState(3_901_000)
    assertEquals(0L, expired.usedMs)
    assertEquals(HomeFeedLockoutState.NONE, expired.lockoutState)

    val afterRestart = InstagramGuardStateMachine()
    afterRestart.restore(expired, 3_901_001)
    assertEquals(InstagramGuardAction.None, afterRestart.next(InstagramSurface.HOME_FEED, false, 3_901_001, settings))
  }

  @Test
  fun unverifiableRebootLockoutWaitsForCompleteMonotonicInterval() {
    val afterRestart = InstagramGuardStateMachine()
    afterRestart.restore(
      HomeFeedRuntimeState(
        usedMs = 300_000L,
        blockedUntilElapsedMs = null,
        usageState = HomeFeedUsageState.UNKNOWN,
        lockoutState = HomeFeedLockoutState.UNKNOWN,
      ),
      60_000L,
    )
    assertEquals(HomeFeedLockoutState.UNKNOWN, afterRestart.homeRuntimeState(60_000).lockoutState)
    assertEquals(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.HOME_LIMIT, null),
      afterRestart.next(InstagramSurface.HOME_FEED, false, 61_000, settings),
    )
    assertEquals(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.HOME_LIMIT, null),
      afterRestart.next(InstagramSurface.HOME_FEED, false, 3_659_999, settings),
    )
    assertEquals(InstagramGuardAction.None, afterRestart.next(InstagramSurface.HOME_FEED, false, 3_660_000, settings))
    assertEquals(0L, afterRestart.homeRuntimeState(3_660_000).usedMs)
    assertEquals(HomeFeedLockoutState.NONE, afterRestart.homeRuntimeState(3_660_000).lockoutState)
  }

  @Test
  fun unavailableRestoredStorageDoesNotGrantHomeAllowance() {
    val afterRestart = InstagramGuardStateMachine()
    afterRestart.restore(
      HomeFeedRuntimeState(0L, null, storageState = HomeFeedStorageState.UNAVAILABLE),
      60_000L,
    )
    assertEquals(HomeFeedStorageState.UNAVAILABLE, afterRestart.homeRuntimeState(60_000).storageState)
    assertEquals(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.HOME_LIMIT, null),
      afterRestart.next(InstagramSurface.HOME_FEED, false, 3_660_000, settings),
    )
    afterRestart.recoverStorage(3_660_001)
    assertEquals(InstagramGuardAction.None, afterRestart.next(InstagramSurface.HOME_FEED, false, 3_660_001, settings))
  }

  @Test
  fun storageRecoveryRequiresForegroundHomeObservation() {
    assertFalse(shouldRecoverInstagramStorage(false, InstagramSurface.HOME_FEED, false))
    assertFalse(shouldRecoverInstagramStorage(false, InstagramSurface.REELS_VIEWER, true))
    assertFalse(shouldRecoverInstagramStorage(true, InstagramSurface.HOME_FEED, true))
    assertTrue(shouldRecoverInstagramStorage(false, InstagramSurface.HOME_FEED, true))
  }

  @Test
  fun dmVisitWithoutThreadClickDoesNotAuthorizeReels() {
    val state = InstagramGuardStateMachine()
    assertEquals(InstagramGuardAction.None, state.next(InstagramSurface.DIRECT_MESSAGES, false, 1_000, settings))
    assertEquals(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.REELS_ENTRY, null),
      state.next(
        InstagramSurface.REELS_VIEWER,
        isScrollEvent = false,
        nowMs = 2_000,
        settings = settings,
        reelPagerVisible = true,
      ),
    )
  }

  @Test
  fun corroboratedDmThreadClickAllowsFirstReel() {
    val state = InstagramGuardStateMachine()
    assertEquals(
      InstagramGuardAction.None,
      state.next(
        InstagramSurface.DIRECT_MESSAGES,
        isScrollEvent = false,
        nowMs = 1_000,
        settings = settings,
        dmThreadClicked = true,
      ),
    )
    assertEquals(
      InstagramGuardAction.None,
      state.next(
        InstagramSurface.REELS_VIEWER,
        isScrollEvent = false,
        nowMs = 2_000,
        settings = settings,
        reelPagerVisible = true,
      ),
    )
  }

  @Test
  fun visibleOpenDmThreadAllowsFirstReelWithoutClickEvent() {
    val state = InstagramGuardStateMachine()
    assertEquals(
      InstagramGuardAction.None,
      state.next(
        InstagramGuardInput(
          surface = InstagramSurface.DIRECT_MESSAGES,
          nowMs = 1_000,
          dmThreadVisible = true,
        ),
        settings,
      ),
    )
    assertEquals(
      InstagramGuardAction.None,
      state.next(
        InstagramGuardInput(
          surface = InstagramSurface.REELS_VIEWER,
          nowMs = 20_000,
          reelPagerVisible = true,
        ),
        settings,
      ),
    )
  }

  @Test
  fun transientTreeDoesNotDropActiveDmThreadProvenance() {
    val state = InstagramGuardStateMachine()
    state.next(
      InstagramGuardInput(
        surface = InstagramSurface.DIRECT_MESSAGES,
        nowMs = 1_000,
        dmThreadVisible = true,
      ),
      settings,
    )

    state.onTransientSurfaceLost(2_000)

    assertEquals(
      InstagramGuardAction.None,
      state.next(
        InstagramGuardInput(
          surface = InstagramSurface.REELS_VIEWER,
          nowMs = 2_500,
          reelPagerVisible = true,
        ),
        settings,
      ),
    )
  }

  @Test
  fun staleTransientTreeDoesNotAuthorizeDirectReelsEntry() {
    val state = InstagramGuardStateMachine()
    state.next(
      InstagramGuardInput(
        surface = InstagramSurface.DIRECT_MESSAGES,
        nowMs = 1_000,
        dmThreadVisible = true,
      ),
      settings,
    )
    state.onTransientSurfaceLost(2_000)

    assertEquals(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.REELS_ENTRY, null),
      state.next(
        InstagramGuardInput(
          surface = InstagramSurface.REELS_VIEWER,
          nowMs = 5_001,
          reelPagerVisible = true,
        ),
        settings,
      ),
    )
  }

  @Test
  fun dmInboxStillDoesNotAuthorizeReels() {
    val state = InstagramGuardStateMachine()
    state.next(
      InstagramGuardInput(
        surface = InstagramSurface.DIRECT_MESSAGES,
        nowMs = 1_000,
      ),
      settings,
    )
    assertEquals(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.REELS_ENTRY, null),
      state.next(
        InstagramGuardInput(
          surface = InstagramSurface.REELS_VIEWER,
          nowMs = 2_000,
          reelPagerVisible = true,
        ),
        settings,
      ),
    )
  }

  @Test
  fun dmClickExpiresBeforeReelsOpens() {
    val state = InstagramGuardStateMachine()
    state.next(
      InstagramSurface.DIRECT_MESSAGES,
      isScrollEvent = false,
      nowMs = 1_000,
      settings = settings,
      dmThreadClicked = true,
    )
    assertEquals(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.REELS_ENTRY, null),
      state.next(
        InstagramSurface.REELS_VIEWER,
        isScrollEvent = false,
        nowMs = 16_001,
        settings = settings,
        reelPagerVisible = true,
      ),
    )
  }

  @Test
  fun onlyPagerScrollCanTriggerReelSwipeBlock() {
    val state = InstagramGuardStateMachine()
    state.next(
      InstagramSurface.DIRECT_MESSAGES,
      isScrollEvent = false,
      nowMs = 1_000,
      settings = settings,
      dmThreadClicked = true,
    )
    state.next(
      InstagramSurface.REELS_VIEWER,
      isScrollEvent = false,
      nowMs = 2_000,
      settings = settings,
      reelPagerVisible = true,
    )

    assertEquals(
      InstagramGuardAction.None,
      state.next(
        InstagramGuardInput(
          surface = InstagramSurface.REELS_VIEWER,
          nowMs = 3_000,
          reelPagerVisible = false,
          reelPagerScrolled = true,
        ),
        settings,
      ),
    )
    assertEquals(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.REELS_SWIPE, 34_000),
      state.next(
        InstagramGuardInput(
          surface = InstagramSurface.REELS_VIEWER,
          nowMs = 4_000,
          reelPagerVisible = true,
          reelPagerScrolled = true,
        ),
        settings,
      ),
    )
  }

  @Test
  fun homeStorageFailurePreservesActiveReelsBlocker() {
    val state = InstagramGuardStateMachine()
    state.next(InstagramSurface.DIRECT_MESSAGES, false, 1_000, settings, dmThreadClicked = true)
    state.next(InstagramSurface.REELS_VIEWER, false, 2_000, settings, reelPagerVisible = true)
    val blocker = state.next(
      InstagramGuardInput(
        surface = InstagramSurface.REELS_VIEWER,
        nowMs = 4_000,
        reelPagerVisible = true,
        reelPagerScrolled = true,
      ),
      settings,
    )
    assertEquals(InstagramGuardAction.ShowBlocker(InstagramBlockReason.REELS_SWIPE, 34_000), blocker)

    state.markStorageUnavailable()

    assertEquals(
      blocker,
      state.next(
        InstagramGuardInput(
          surface = InstagramSurface.REELS_VIEWER,
          nowMs = 5_000,
          reelPagerVisible = true,
        ),
        settings,
      ),
    )
  }

  @Test
  fun ignoresAutomaticPagerScrollWhenFirstDmReelOpens() {
    val state = InstagramGuardStateMachine()
    state.next(
      InstagramSurface.DIRECT_MESSAGES,
      isScrollEvent = false,
      nowMs = 1_000,
      settings = settings,
      dmThreadClicked = true,
    )
    assertEquals(
      InstagramGuardAction.None,
      state.next(
        InstagramSurface.REELS_VIEWER,
        isScrollEvent = false,
        nowMs = 2_000,
        settings = settings,
        reelPagerVisible = true,
      ),
    )
    assertEquals(
      InstagramGuardAction.None,
      state.next(
        InstagramSurface.REELS_VIEWER,
        isScrollEvent = true,
        nowMs = 2_166,
        settings = settings,
        reelPagerVisible = true,
      ),
    )
    assertEquals(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.REELS_SWIPE, 32_751),
      state.next(
        InstagramSurface.REELS_VIEWER,
        isScrollEvent = true,
        nowMs = 2_751,
        settings = settings,
        reelPagerVisible = true,
      ),
    )
  }

  @Test
  fun directReelsEntryBlocks() {
    val state = InstagramGuardStateMachine()
    assertEquals(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.REELS_ENTRY, null),
      state.next(
        InstagramGuardInput(
          surface = InstagramSurface.REELS_VIEWER,
          nowMs = 1_000,
          reelPagerVisible = true,
        ),
        settings,
      ),
    )
  }

  @Test
  fun visitingHomeClearsDmProvenance() {
    val state = InstagramGuardStateMachine()
    state.next(
      InstagramSurface.DIRECT_MESSAGES,
      isScrollEvent = false,
      nowMs = 1_000,
      settings = settings,
      dmThreadClicked = true,
    )
    state.next(InstagramSurface.HOME_FEED, false, 2_000, settings)
    assertEquals(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.REELS_ENTRY, null),
      state.next(
        InstagramSurface.REELS_VIEWER,
        isScrollEvent = false,
        nowMs = 3_000,
        settings = settings,
        reelPagerVisible = true,
      ),
    )
  }

  @Test
  fun continueRequiresWaitAndGrantsFiveMinutes() {
    val state = InstagramGuardStateMachine()
    state.next(
      InstagramSurface.DIRECT_MESSAGES,
      isScrollEvent = false,
      nowMs = 500,
      settings = settings,
      dmThreadClicked = true,
    )
    state.next(
      InstagramSurface.REELS_VIEWER,
      isScrollEvent = false,
      nowMs = 1_000,
      settings = settings,
      reelPagerVisible = true,
    )
    state.next(
      InstagramSurface.REELS_VIEWER,
      isScrollEvent = true,
      nowMs = 2_000,
      settings = settings,
      reelPagerVisible = true,
    )
    assertFalse(state.continueReels(31_999, settings))
    assertTrue(state.continueReels(32_000, settings))
    assertEquals(
      InstagramGuardAction.None,
      state.next(
        InstagramSurface.REELS_VIEWER,
        isScrollEvent = true,
        nowMs = 331_999,
        settings = settings,
        reelPagerVisible = true,
      ),
    )
    assertEquals(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.REELS_WINDOW_EXPIRED, 362_000),
      state.next(
        InstagramSurface.REELS_VIEWER,
        isScrollEvent = true,
        nowMs = 332_000,
        settings = settings,
        reelPagerVisible = true,
      ),
    )
  }

  @Test
  fun homeTimerPausesAcrossBackground() {
    val state = InstagramGuardStateMachine()
    assertEquals(InstagramGuardAction.None, state.next(InstagramSurface.HOME_FEED, false, 1_000, settings))
    assertEquals(InstagramGuardAction.None, state.next(InstagramSurface.HOME_FEED, false, 101_000, settings))

    state.onAppBackground()
    assertEquals(InstagramGuardAction.None, state.next(InstagramSurface.HOME_FEED, false, 501_000, settings))
    assertEquals(InstagramGuardAction.None, state.next(InstagramSurface.HOME_FEED, false, 700_999, settings))
    assertEquals(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.HOME_LIMIT, null),
      state.next(InstagramSurface.HOME_FEED, false, 701_000, settings),
    )
  }

  @Test
  fun homeRuntimeSnapshotIncludesTimeUntilTheAppBackgrounds() {
    val state = InstagramGuardStateMachine()
    state.next(InstagramSurface.HOME_FEED, false, 1_000, settings)
    state.onAppBackground(121_000)

    assertEquals(120_000L, state.homeRuntimeState(500_000).usedMs)
    state.next(InstagramSurface.HOME_FEED, false, 500_000, settings)
    assertEquals(180_000L, state.homeRuntimeState(560_000).usedMs)
  }

  @Test
  fun backgroundingStartsTheHomeLockoutWhenTheFinalSliceExhaustsTheAllowance() {
    val shortSettings = settings.copy(homeAllowanceMs = 60_000L)
    val state = InstagramGuardStateMachine()
    state.next(InstagramSurface.HOME_FEED, false, 1_000, shortSettings)
    state.onAppBackground(61_000, shortSettings)

    assertEquals(3_661_000L, state.homeRuntimeState(61_000).blockedUntilElapsedMs)
  }

  @Test
  fun homeStaysBlockedForOneHourAfterLeavingAndReopening() {
    val state = InstagramGuardStateMachine()
    state.next(InstagramSurface.HOME_FEED, false, 1_000, settings)
    assertEquals(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.HOME_LIMIT, null),
      state.next(InstagramSurface.HOME_FEED, false, 301_000, settings),
    )

    state.leaveBlockedSurface()
    state.onSurfaceLost()
    assertEquals(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.HOME_LIMIT, null),
      state.next(InstagramSurface.HOME_FEED, false, 301_001, settings),
    )
    state.onSurfaceLost()
    assertEquals(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.HOME_LIMIT, null),
      state.next(InstagramSurface.HOME_FEED, false, 3_900_999, settings),
    )
    assertEquals(InstagramGuardAction.None, state.next(InstagramSurface.HOME_FEED, false, 3_901_000, settings))
  }

  @Test
  fun surfaceLossClearsStaleBlocker() {
    val state = InstagramGuardStateMachine()
    assertEquals(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.REELS_ENTRY, null),
      state.next(
        InstagramGuardInput(
          surface = InstagramSurface.REELS_VIEWER,
          nowMs = 1_000,
          reelPagerVisible = true,
        ),
        settings,
      ),
    )

    state.onSurfaceLost()
    assertEquals(InstagramGuardAction.None, state.next(InstagramSurface.DIRECT_MESSAGES, false, 2_000, settings))
  }

  @Test
  fun interruptionClearsStaleBlockerAndReelProvenance() {
    val state = InstagramGuardStateMachine()
    state.next(
      InstagramSurface.DIRECT_MESSAGES,
      isScrollEvent = false,
      nowMs = 1_000,
      settings = settings,
      dmThreadClicked = true,
    )
    state.next(
      InstagramSurface.REELS_VIEWER,
      isScrollEvent = false,
      nowMs = 2_000,
      settings = settings,
      reelPagerVisible = true,
    )
    state.onAppBackground()
    assertEquals(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.REELS_ENTRY, null),
      state.next(
        InstagramSurface.REELS_VIEWER,
        isScrollEvent = false,
        nowMs = 3_000,
        settings = settings,
        reelPagerVisible = true,
      ),
    )
  }

  @Test
  fun homeAndExploreUseTheirOwnLimits() {
    val state = InstagramGuardStateMachine()
    assertEquals(InstagramGuardAction.None, state.next(InstagramSurface.HOME_FEED, true, 1_000, settings))
    assertEquals(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.HOME_LIMIT, null),
      state.next(InstagramSurface.HOME_FEED, true, 301_000, settings),
    )

    state.leaveBlockedSurface()
    assertEquals(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.EXPLORE, null),
      state.next(InstagramSurface.EXPLORE, false, 302_000, settings),
    )
  }

  @Test
  fun unknownScreensClearStateAndDoNothing() {
    val state = InstagramGuardStateMachine()
    assertEquals(InstagramGuardAction.None, state.next(InstagramSurface.UNKNOWN, true, 1_000, settings))
  }
}
