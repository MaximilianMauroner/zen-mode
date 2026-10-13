package com.maxmauroner.zenguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class RollingWindowTest {
  @Test
  fun `nothing banked means nothing used`() {
    assertEquals(0L, RollingWindow.usedMs(emptyList(), 3_600_000L, 10_000_000L))
  }

  @Test
  fun `only slices inside the window count`() {
    val events = listOf(
      RollingEvent(atWallMs = 6_000_000L, durationMs = 120_000L),
      RollingEvent(atWallMs = 9_500_000L, durationMs = 60_000L),
      RollingEvent(atWallMs = 9_900_000L, durationMs = 30_000L),
    )

    assertEquals(90_000L, RollingWindow.usedMs(events, 3_600_000L, 10_000_000L))
  }

  @Test
  fun `a slice exactly on the cutoff has aged out`() {
    val events = listOf(RollingEvent(atWallMs = 6_400_000L, durationMs = 60_000L))

    assertEquals(0L, RollingWindow.usedMs(events, 3_600_000L, 10_000_000L))
    assertEquals(60_000L, RollingWindow.usedMs(events, 3_600_001L, 10_000_000L))
  }

  @Test
  fun `old usage refills the allowance on its own`() {
    val events = listOf(RollingEvent(atWallMs = 10_000_000L, durationMs = 300_000L))

    assertEquals(300_000L, RollingWindow.usedMs(events, 3_600_000L, 10_100_000L))
    assertEquals(0L, RollingWindow.usedMs(events, 3_600_000L, 13_700_000L))
  }

  @Test
  fun `access returns before the full window refills and equality remains blocked`() {
    val events = listOf(RollingEvent(2_000L, 30L), RollingEvent(1_000L, 20L), RollingEvent(3_000L, 50L))
    // Expiring the oldest 20 leaves exactly the 80 allowance, so access needs the next expiry.
    assertEquals(12_000L, RollingWindow.availableAgainAt(events, 80L, 10_000L, 4_000L))
    assertTrue(RollingWindow.usedMs(events, 10_000L, 11_999L) >= 80L)
    assertTrue(RollingWindow.usedMs(events, 10_000L, 12_000L) < 80L)
    assertNull(RollingWindow.availableAgainAt(events, 80L, 10_000L, 12_000L))
  }

  @Test
  fun `exhausted hourly allowance regains access at oldest necessary slice expiry`() {
    val events = listOf(RollingEvent(1_000_000L, 150_000L), RollingEvent(1_300_000L, 150_000L))
    assertEquals(4_600_000L, RollingWindow.availableAgainAt(events, 300_000L, 3_600_000L, 1_300_000L))
  }

  @Test
  fun `pruning drops aged out slices and caps the rest`() {
    val events = listOf(
      RollingEvent(atWallMs = 1_000L, durationMs = 10_000L),
      RollingEvent(atWallMs = 9_000_000L, durationMs = 10_000L),
      RollingEvent(atWallMs = 9_500_000L, durationMs = 10_000L),
      RollingEvent(atWallMs = 9_900_000L, durationMs = 10_000L),
    )

    val pruned = RollingWindow.pruned(events, 3_600_000L, 10_000_000L, 2)

    assertEquals(2, pruned.size)
    assertTrue(pruned.all { it.atWallMs > 6_400_000L })
  }
}
