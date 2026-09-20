package com.maxmauroner.zenguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeShortsPagerTest {
  @Test fun returnsTheSettledPageOwnedByThePager() {
    assertEquals(7, YouTubeShortsPager.stablePageIndex(
      isViewScrolled = true,
      sourceViewId = "com.google.android.youtube:id/reel_recycler",
      fromIndex = 7,
      toIndex = 7,
      scrollY = 1,
    ))
  }

  @Test fun partialPlaybackAndAmbiguousEventsFailOpen() {
    assertNull(YouTubeShortsPager.stablePageIndex(true, "com.google.android.youtube:id/reel_recycler", 7, 8, 1))
    assertNull(YouTubeShortsPager.stablePageIndex(false, "com.google.android.youtube:id/reel_recycler", 8, 8, 1))
    assertNull(YouTubeShortsPager.stablePageIndex(true, "com.google.android.youtube:id/reel_progress_bar", 8, 8, 1))
    assertNull(YouTubeShortsPager.stablePageIndex(true, null, 8, 8, 1))
    assertNull(YouTubeShortsPager.stablePageIndex(true, "com.google.android.youtube:id/reel_recycler", -1, -1, 1))
    assertEquals(0, YouTubeShortsPager.stablePageIndex(true, "com.google.android.youtube:id/reel_recycler", 0, 0, 0))
    assertNull(YouTubeShortsPager.stablePageIndex(true, "com.google.android.youtube:id/reel_recycler", 0, 0, -1))
  }

  @Test fun pagerFixtureBlocksOnlyAfterACompletedPageTransition() {
    val state = EnforcementStateMachine()
    val pager = "com.google.android.youtube:id/reel_recycler"
    assertEquals(EnforcementAction.NONE, state.next(true, null, 0))
    assertEquals(EnforcementAction.NONE, state.next(true, YouTubeShortsPager.stablePageIndex(true, pager, 0, 0, 0), 100))
    assertEquals(EnforcementAction.NONE, state.next(true, YouTubeShortsPager.stablePageIndex(true, pager, 0, 1, 1), 200))
    val destination = YouTubeShortsPager.stablePageIndex(true, pager, 1, 1, 1)
    assertEquals(EnforcementAction.LEAVE_SHORTS, state.next(true, destination, 300, pagerTransitionIndex = destination))
  }
}
