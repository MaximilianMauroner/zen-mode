package com.maxmauroner.zenguard

import org.junit.Assert.assertEquals
import org.junit.Test

class InstagramDetectorTest {
  @Test
  fun detectsStrongResourceIds() {
    assertEquals(
      InstagramSurface.DIRECT_MESSAGES,
      InstagramDetector.detect(listOf(NodeSignal(viewId = "com.instagram.android:id/direct_thread"))).surface,
    )
    assertEquals(
      InstagramSurface.REELS_VIEWER,
      InstagramDetector.detect(listOf(NodeSignal(viewId = "com.instagram.android:id/clips_viewer_view_pager"))).surface,
    )
    assertEquals(
      InstagramSurface.HOME_FEED,
      InstagramDetector.detect(listOf(NodeSignal(viewId = "com.instagram.android:id/feed_recycler_view"))).surface,
    )
    assertEquals(
      InstagramSurface.EXPLORE,
      InstagramDetector.detect(listOf(NodeSignal(viewId = "com.instagram.android:id/explore_grid"))).surface,
    )
  }

  @Test
  fun labelsAndUnknownTreesFailOpen() {
    assertEquals(
      InstagramSurface.UNKNOWN,
      InstagramDetector.detect(
        listOf(
          NodeSignal(text = "Reels", selected = true),
          NodeSignal(description = "Explore"),
          NodeSignal(text = "Messages"),
        ),
      ).surface,
    )
  }

  @Test
  fun conflictingStrongSignalsFailOpen() {
    assertEquals(
      InstagramSurface.UNKNOWN,
      InstagramDetector.detect(
        listOf(
          NodeSignal(viewId = "com.instagram.android:id/direct_thread"),
          NodeSignal(viewId = "com.instagram.android:id/clips_viewer_view_pager"),
        ),
      ).surface,
    )
  }
}
