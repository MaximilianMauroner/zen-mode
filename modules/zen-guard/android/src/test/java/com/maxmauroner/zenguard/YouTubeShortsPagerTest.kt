package com.maxmauroner.zenguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeShortsPagerTest {
  @Test fun returnsTheSettledPageOwnedByThePager() {
    val detector = YouTubeShortsPager()
    assertNull(detector.stablePageIndex(true, "com.google.android.youtube:id/reel_recycler", 6, 6, 0))
    assertNull(detector.stablePageIndex(true, "com.google.android.youtube:id/reel_recycler", 6, 7, 1))
    assertEquals(7, detector.stablePageIndex(
      isViewScrolled = true,
      sourceViewId = "com.google.android.youtube:id/reel_recycler",
      fromIndex = 7,
      toIndex = 7,
      scrollY = 1,
    ))
  }

  @Test fun snapBackAndStaleVisitSequencesFailOpen() {
    val detector = YouTubeShortsPager()
    val pager = "com.google.android.youtube:id/reel_recycler"
    assertNull(detector.stablePageIndex(true, pager, 4, 4, 0))
    assertNull(detector.stablePageIndex(true, pager, 4, 5, 1))
    assertNull(detector.stablePageIndex(true, pager, 4, 4, 0))
    assertNull(detector.stablePageIndex(true, pager, 4, 5, 1))
    detector.reset()
    assertNull(detector.stablePageIndex(true, pager, 5, 5, 0))
  }

  @Test fun partialPlaybackAndAmbiguousEventsFailOpen() {
    val detector = YouTubeShortsPager()
    assertNull(detector.stablePageIndex(false, "com.google.android.youtube:id/reel_recycler", 8, 8, 1))
    assertNull(detector.stablePageIndex(true, "com.google.android.youtube:id/reel_progress_bar", 8, 8, 1))
    assertNull(detector.stablePageIndex(true, null, 8, 8, 1))
    assertNull(detector.stablePageIndex(true, "com.google.android.youtube:id/reel_recycler", -1, -1, 1))
    assertNull(detector.stablePageIndex(true, "com.google.android.youtube:id/reel_recycler", 0, 0, 0))
    assertNull(detector.stablePageIndex(true, "com.google.android.youtube:id/reel_recycler", 0, 0, -1))
  }

  @Test fun pagerFixtureBlocksOnlyAfterACompletedPageTransition() {
    val state = EnforcementStateMachine()
    val detector = YouTubeShortsPager()
    val pager = "com.google.android.youtube:id/reel_recycler"
    assertEquals(EnforcementAction.NONE, state.next(true, null, 0))
    assertEquals(EnforcementAction.NONE, state.next(true, detector.stablePageIndex(true, pager, 0, 0, 0), 100))
    assertEquals(EnforcementAction.NONE, state.next(true, detector.stablePageIndex(true, pager, 0, 1, 1), 200))
    val destination = detector.stablePageIndex(true, pager, 1, 1, 1)
    assertEquals(EnforcementAction.LEAVE_SHORTS, state.next(true, destination, 300, pagerTransitionIndex = destination))
  }
}
