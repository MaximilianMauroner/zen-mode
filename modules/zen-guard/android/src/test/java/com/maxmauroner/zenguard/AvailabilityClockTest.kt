package com.maxmauroner.zenguard

import java.time.ZonedDateTime
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class AvailabilityClockTest {
  private val originalZone = TimeZone.getDefault()
  @After fun restoreZone() { TimeZone.setDefault(originalZone) }

  @Test fun `daily reset follows local midnight on both DST changes`() {
    TimeZone.setDefault(TimeZone.getTimeZone("Europe/Vienna"))
    val spring = ZonedDateTime.parse("2026-03-29T00:00:00+01:00[Europe/Vienna]").toInstant().toEpochMilli()
    val autumn = ZonedDateTime.parse("2026-10-25T00:00:00+02:00[Europe/Vienna]").toInstant().toEpochMilli()
    assertEquals(23L * 3_600_000L, millisUntilLocalMidnight(spring))
    assertEquals(25L * 3_600_000L, millisUntilLocalMidnight(autumn))
    assertEquals(1L, millisUntilLocalMidnight(autumn + 25L * 3_600_000L - 1L))
    assertEquals(24L * 3_600_000L, millisUntilLocalMidnight(autumn + 25L * 3_600_000L))
  }

  @Test fun `countdown rounds positive seconds up and includes hours`() {
    assertEquals("0:00", formatAvailabilityCountdown(0L))
    assertEquals("0:01", formatAvailabilityCountdown(1L))
    assertEquals("1:01", formatAvailabilityCountdown(60_001L))
    assertEquals("1:00:00", formatAvailabilityCountdown(3_600_000L))
    assertEquals("25:00:00", formatAvailabilityCountdown(25L * 3_600_000L))
  }
}
