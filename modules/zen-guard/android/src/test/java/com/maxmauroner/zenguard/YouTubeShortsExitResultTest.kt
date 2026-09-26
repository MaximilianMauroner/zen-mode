package com.maxmauroner.zenguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeShortsExitResultTest {
  @Test fun `only confirmed Home produces one result`() {
    val result = YouTubeShortsExitResult()
    result.requested(4, 1_000L)
    assertNull(result.confirmed(false, 1_100L))
    assertEquals(4, result.confirmed(true, 1_200L)?.pageIndex)
    assertNull(result.confirmed(true, 1_300L))
  }

  @Test fun `stale or cleared navigation never produces a result`() {
    val result = YouTubeShortsExitResult()
    result.requested(2, 1_000L)
    assertNull(result.confirmed(true, 4_001L))
    result.requested(3, 5_000L)
    result.clear()
    assertNull(result.confirmed(true, 5_100L))
  }
}
