package com.maxmauroner.zenguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeShortsExitResultTest {
  @Test fun `only confirmed Home produces one result`() {
    val result = YouTubeShortsExitResult()
    result.requested(4, 1_000L)
    assertNull(result.confirmed(YouTubeSurface.HOME, false, 1_100L))
    assertEquals(4, result.confirmed(YouTubeSurface.HOME, true, 1_200L)?.pageIndex)
    assertNull(result.confirmed(YouTubeSurface.HOME, true, 1_300L))
  }

  @Test fun `stale or cleared navigation never produces a result`() {
    val result = YouTubeShortsExitResult()
    result.requested(2, 1_000L)
    assertNull(result.confirmed(YouTubeSurface.HOME, true, 4_001L))
    result.requested(3, 5_000L)
    result.clear()
    assertNull(result.confirmed(YouTubeSurface.HOME, true, 5_100L))
  }

  @Test fun `unverified trees never confirm even with a selected Home tab`() {
    val candidates = listOf(
      emptyList(),
      listOf(NodeSignal(text = "Home", selected = true)),
      listOf(NodeSignal(viewId = "com.google.android.youtube:id/pivot_bar", selected = true)),
      listOf(NodeSignal(viewId = "com.google.android.youtube:id/watch_player")),
      listOf(NodeSignal(text = "Subscriptions", selected = true)),
      listOf(NodeSignal(viewId = "com.google.android.youtube:id/reel_recycler")),
    )
    candidates.forEach { nodes ->
      val result = YouTubeShortsExitResult()
      result.requested(4, 1_000L)
      val surface = YouTubeSurfaceDetector.detect(nodes)
      assertNull(result.confirmed(surface, true, 1_100L))
      // An unknown observation must not consume a still-valid pending request.
      assertEquals(4, result.confirmed(YouTubeSurface.HOME, true, 1_200L)?.pageIndex)
    }
  }
}
