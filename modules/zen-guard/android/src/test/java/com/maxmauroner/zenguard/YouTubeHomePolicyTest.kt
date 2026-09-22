package com.maxmauroner.zenguard

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeHomePolicyTest {
  @Test fun requiresEnabledObservedUnambiguousHome() {
    assertEquals(YouTubeHomeAction.NONE, YouTubeHomePolicy.next(YouTubeSurface.HOME, false, true))
    assertEquals(YouTubeHomeAction.NONE, YouTubeHomePolicy.next(YouTubeSurface.HOME, true, false))
    assertEquals(YouTubeHomeAction.NONE, YouTubeHomePolicy.next(YouTubeSurface.UNKNOWN, true, true))
    assertEquals(YouTubeHomeAction.NONE, YouTubeHomePolicy.next(YouTubeSurface.OTHER, true, true))
    assertEquals(YouTubeHomeAction.NONE, YouTubeHomePolicy.next(YouTubeSurface.SHORTS, true, true))
    assertEquals(YouTubeHomeAction.LEAVE_HOME, YouTubeHomePolicy.next(YouTubeSurface.HOME, true, true))
  }
}
