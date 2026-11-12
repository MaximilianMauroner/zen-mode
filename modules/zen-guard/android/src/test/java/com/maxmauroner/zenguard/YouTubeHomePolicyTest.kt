package com.maxmauroner.zenguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class YouTubeHomePolicyTest {
  @Test fun `a confirmed visit keeps its first monotonic deadline through continuous events`() {
    val policy = YouTubeHomePolicy()
    val visit = requireNotNull(policy.observe(YouTubeSurface.HOME, true, true, 500L))
    assertEquals(500L, visit.startedElapsedMs)
    assertEquals(10_500L, visit.deadlineElapsedMs)
    assertSame(visit, policy.observe(YouTubeSurface.HOME, true, true, 10_499L))
    assertSame(visit, policy.observe(YouTubeSurface.HOME, true, true, 20_000L))
  }

  @Test fun `a departure or disabled or unobserved rule ends the visit`() {
    val policy = YouTubeHomePolicy()
    val visit = policy.observe(YouTubeSurface.HOME, true, true, 0L)
    for (surface in listOf(YouTubeSurface.UNKNOWN, YouTubeSurface.OTHER, YouTubeSurface.SHORTS)) {
      assertNull(policy.observe(surface, true, true, 1L))
    }
    assertNull(policy.observe(YouTubeSurface.HOME, false, true, 1L))
    assertNull(policy.observe(YouTubeSurface.HOME, true, false, 1L))
    val returned = policy.observe(YouTubeSurface.HOME, true, true, 4_000L)
    assertNotSame(visit, returned)
    assertEquals(14_000L, returned?.deadlineElapsedMs)
    policy.reset()
    assertNotSame(returned, policy.observe(YouTubeSurface.HOME, true, true, 5_000L))
  }
}
