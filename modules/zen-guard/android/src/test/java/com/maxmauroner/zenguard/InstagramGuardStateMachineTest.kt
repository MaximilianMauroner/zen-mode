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
