package com.maxmauroner.zenguard

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeSurfaceDetectorTest {
  // Synthetic fixtures: these are safety regressions, not physical-device evidence.
  @Test fun keepsShortsSeparate() {
    assertEquals(
      YouTubeSurface.SHORTS,
      YouTubeSurfaceDetector.detect(listOf(NodeSignal(viewId = "com.google.android.youtube:id/reel_recycler"))),
    )
  }

  @Test fun ordinaryVideoIsPreserved() {
    assertEquals(
      YouTubeSurface.OTHER,
      YouTubeSurfaceDetector.detect(listOf(NodeSignal(viewId = "com.google.android.youtube:id/watch_player"))),
    )
  }

  @Test fun intentionalSelectedSurfacesArePreserved() {
    listOf("Search", "Subscriptions", "You", "Library", "History", "Notifications").forEach { label ->
      assertEquals(
        label,
        YouTubeSurface.OTHER,
        YouTubeSurfaceDetector.detect(listOf(NodeSignal(text = label, selected = true))),
      )
    }
  }

  @Test fun sparseAndCandidateHomeTreesFailOpenWithoutCapturedEvidence() {
    assertEquals(YouTubeSurface.UNKNOWN, YouTubeSurfaceDetector.detect(emptyList()))
    assertEquals(
      YouTubeSurface.UNKNOWN,
      YouTubeSurfaceDetector.detect(listOf(NodeSignal(text = "Home", selected = true))),
    )
  }
}
