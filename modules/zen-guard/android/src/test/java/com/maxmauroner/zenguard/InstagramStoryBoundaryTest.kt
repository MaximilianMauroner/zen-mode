package com.maxmauroner.zenguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Negative unit inputs from existing detector tests, not captured Story-viewer fixtures. */
class InstagramStoryBoundaryTest {
  @Test
  fun ambiguousSharedMediaCannotStartEnforcementOrAHomeTimer() {
    val detection = InstagramDetector.detect(
      listOf(
        NodeSignal(viewId = "com.instagram.android:id/reel_viewer_front_avatar"),
        NodeSignal(viewId = "com.instagram.android:id/clips_media_component"),
      ),
    )
    val state = InstagramGuardStateMachine()
    val settings = InstagramGuardSettings()

    for (nowMs in listOf(1_000L, 2_000L, 301_000L, 901_000L)) {
      assertEquals(
        InstagramGuardAction.None,
        state.next(InstagramGuardInput(surface = detection.surface, nowMs = nowMs), settings),
      )
    }
    assertEquals(0L, state.homeElapsedMs())
    assertNull(state.debugState(901_000L).blockerReason)
  }

  @Test
  fun repeatedEmbeddedMediaEventsInDmDoNotStartAReelsPause() {
    val detection = InstagramDetector.detect(
      listOf(
        NodeSignal(viewId = "com.instagram.android:id/thread_fragment_container"),
        NodeSignal(viewId = "com.instagram.android:id/message_list"),
        NodeSignal(viewId = "com.instagram.android:id/reel_viewer_front_avatar"),
        NodeSignal(viewId = "com.instagram.android:id/clips_media_component"),
      ),
    )
    val state = InstagramGuardStateMachine()
    val settings = InstagramGuardSettings()

    for (nowMs in listOf(1_000L, 2_000L, 301_000L, 901_000L)) {
      assertEquals(
        InstagramGuardAction.None,
        state.next(
          InstagramGuardInput(
            surface = detection.surface,
            nowMs = nowMs,
            dmThreadVisible = true,
            reelPagerScrolled = true,
          ),
          settings,
        ),
      )
    }
    assertNull(state.debugState(901_000L).reelsWindowRemainingMs)
    assertNull(state.debugState(901_000L).blockerReason)
  }
}
