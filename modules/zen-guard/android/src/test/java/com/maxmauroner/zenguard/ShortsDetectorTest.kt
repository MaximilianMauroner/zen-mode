package com.maxmauroner.zenguard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortsDetectorTest {
  @Test
  fun reelWatchPlayerIsNotAnOrdinaryVideoPlayer() {
    assertTrue(ShortsDetector.detect(listOf(
      NodeSignal(viewId = "com.google.android.youtube:id/watch_while_layout_coordinator_layout"),
      NodeSignal(viewId = "com.google.android.youtube:id/reel_watch_fragment_root"),
      NodeSignal(viewId = "com.google.android.youtube:id/reel_recycler"),
      NodeSignal(viewId = "com.google.android.youtube:id/reel_watch_player"),
    )).isShortsViewer)
  }

  @Test
  fun detectsViewerSpecificResourceId() {
    val result = ShortsDetector.detect(
      listOf(NodeSignal(viewId = "com.google.android.youtube:id/reel_player_page_container")),
    )
    assertTrue(result.isShortsViewer)
  }

  @Test
  fun doesNotTreatShortsShelfAsViewer() {
    val result = ShortsDetector.detect(
      listOf(
        NodeSignal(viewId = "com.google.android.youtube:id/reel_shelf"),
        NodeSignal(text = "Shorts"),
      ),
    )
    assertFalse(result.isShortsViewer)
  }

  @Test
  fun leavesOrdinaryYouTubeSurfacesAlone() {
    val result = ShortsDetector.detect(
      listOf(
        NodeSignal(text = "Search"),
        NodeSignal(text = "Subscriptions", selected = true),
        NodeSignal(description = "Share"),
        NodeSignal(description = "Comments"),
      ),
    )
    assertFalse(result.isShortsViewer)
  }

  @Test
  fun playerActionsWithoutSelectedShortsTabAreNotEnough() {
    val result = ShortsDetector.detect(
      listOf(
        NodeSignal(description = "Like this video"),
        NodeSignal(description = "Share"),
      ),
    )
    assertFalse(result.isShortsViewer)
  }

  @Test
  fun ordinaryWatchWinsOverAmbiguousReelNode() {
    val result = ShortsDetector.detect(
      listOf(
        NodeSignal(viewId = "com.google.android.youtube:id/reel_progress_bar"),
        NodeSignal(viewId = "com.google.android.youtube:id/watch_player"),
      ),
    )
    assertFalse(result.isShortsViewer)
  }

  @Test
  fun selectedTabRequiresCorroboratingPlayerActions() {
    val weak = ShortsDetector.detect(listOf(NodeSignal(text = "Shorts", selected = true)))
    val strong = ShortsDetector.detect(
      listOf(
        NodeSignal(text = "Shorts", selected = true),
        NodeSignal(description = "Comments"),
        NodeSignal(description = "Share"),
      ),
    )
    assertFalse(weak.isShortsViewer)
    assertTrue(strong.isShortsViewer)
  }
}
