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
  fun allowsFirstDmReelThenBlocksSwipe() {
    val state = InstagramGuardStateMachine()
    assertEquals(InstagramGuardAction.None, state.next(InstagramSurface.DIRECT_MESSAGES, false, 1_000, settings))
    assertEquals(InstagramGuardAction.None, state.next(InstagramSurface.REELS_VIEWER, false, 2_000, settings))
    assertEquals(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.REELS_SWIPE, 33_000),
      state.next(InstagramSurface.REELS_VIEWER, true, 3_000, settings),
    )
  }

  @Test
  fun firstReelsEventFromDmIsAllowedEvenWhenReportedAsScroll() {
    val state = InstagramGuardStateMachine()
    state.next(InstagramSurface.DIRECT_MESSAGES, false, 1_000, settings)
    assertEquals(InstagramGuardAction.None, state.next(InstagramSurface.REELS_VIEWER, true, 2_000, settings))
  }

  @Test
  fun directReelsEntryBlocks() {
    val state = InstagramGuardStateMachine()
    assertEquals(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.REELS_ENTRY, null),
      state.next(InstagramSurface.REELS_VIEWER, false, 1_000, settings),
    )
  }

  @Test
  fun visitingHomeClearsDmProvenance() {
    val state = InstagramGuardStateMachine()
    state.next(InstagramSurface.DIRECT_MESSAGES, false, 1_000, settings)
    state.next(InstagramSurface.HOME_FEED, false, 2_000, settings)
    assertEquals(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.REELS_ENTRY, null),
      state.next(InstagramSurface.REELS_VIEWER, false, 3_000, settings),
    )
  }

  @Test
  fun continueRequiresWaitAndGrantsFiveMinutes() {
    val state = InstagramGuardStateMachine()
    state.next(InstagramSurface.DIRECT_MESSAGES, false, 500, settings)
    state.next(InstagramSurface.REELS_VIEWER, false, 1_000, settings)
    state.next(InstagramSurface.REELS_VIEWER, true, 1_000, settings)
    assertFalse(state.continueReels(30_999, settings))
    assertTrue(state.continueReels(31_000, settings))
    assertEquals(InstagramGuardAction.None, state.next(InstagramSurface.REELS_VIEWER, true, 330_999, settings))
    assertEquals(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.REELS_WINDOW_EXPIRED, 361_000),
      state.next(InstagramSurface.REELS_VIEWER, true, 331_000, settings),
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
  fun unknownScreensDoNothing() {
    val state = InstagramGuardStateMachine()
    assertEquals(InstagramGuardAction.None, state.next(InstagramSurface.UNKNOWN, true, 1_000, settings))
  }
}
