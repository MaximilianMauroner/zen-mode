package com.maxmauroner.zenguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUsageTrackerTest {
  @Test
  fun `first app in the foreground banks nothing`() {
    assertNull(AppUsageTracker().onForeground("com.example.a", 1_000L))
  }

  @Test
  fun `switching apps banks the time the previous app was in front`() {
    val tracker = AppUsageTracker()
    tracker.onForeground("com.example.a", 1_000L)

    val banked = tracker.onForeground("com.example.b", 6_000L)

    assertEquals(AppUsageTracker.Attribution("com.example.a", 5_000L), banked)
  }

  @Test
  fun `repeating the same package does not double count`() {
    val tracker = AppUsageTracker()
    tracker.onForeground("com.example.a", 1_000L)

    assertNull(tracker.onForeground("com.example.a", 9_000L))
  }

  @Test
  fun `a tick banks time so far and keeps the app in front`() {
    val tracker = AppUsageTracker()
    tracker.onForeground("com.example.a", 0L)

    assertEquals(AppUsageTracker.Attribution("com.example.a", 15_000L), tracker.tick(15_000L))
    assertEquals(AppUsageTracker.Attribution("com.example.a", 15_000L), tracker.tick(30_000L))
  }

  @Test
  fun `time already banked by a tick is not banked again on switch`() {
    val tracker = AppUsageTracker()
    tracker.onForeground("com.example.a", 0L)
    tracker.tick(15_000L)

    assertEquals(AppUsageTracker.Attribution("com.example.a", 5_000L), tracker.onForeground("com.example.b", 20_000L))
  }

  @Test
  fun `a clock that jumps backwards banks nothing rather than negative time`() {
    val tracker = AppUsageTracker()
    tracker.onForeground("com.example.a", 10_000L)

    assertNull(tracker.onForeground("com.example.b", 4_000L))
  }

  @Test
  fun `reset drops unbanked time`() {
    val tracker = AppUsageTracker()
    tracker.onForeground("com.example.a", 0L)
    tracker.reset()

    assertNull(tracker.tick(60_000L))
  }
}
