package com.maxmauroner.zenguard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeShortsPagerTest {
  @Test fun acceptsTheObservedPagerOwnedForwardScroll() {
    assertTrue(YouTubeShortsPager.isVerifiedAdvance(
      isViewScrolled = true,
      sourceViewId = "com.google.android.youtube:id/reel_recycler",
      scrollY = 1,
    ))
  }

  @Test fun playbackAndAmbiguousEventsFailOpen() {
    assertFalse(YouTubeShortsPager.isVerifiedAdvance(false, "com.google.android.youtube:id/reel_recycler", 1))
    assertFalse(YouTubeShortsPager.isVerifiedAdvance(true, "com.google.android.youtube:id/reel_progress_bar", 1))
    assertFalse(YouTubeShortsPager.isVerifiedAdvance(true, null, 1))
    assertFalse(YouTubeShortsPager.isVerifiedAdvance(true, "com.google.android.youtube:id/reel_recycler", 0))
  }
}
