package com.maxmauroner.zenguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentSessionTrackerTest {
  @Test
  fun `nothing is active before a grant`() {
    assertFalse(IntentSessionTracker().isActive("com.example.a", 1_000L))
  }

  @Test
  fun `a grant stays active until its minutes run out`() {
    val tracker = IntentSessionTracker()
    tracker.grant("com.example.a", 5, 0L)

    assertTrue(tracker.isActive("com.example.a", 299_999L))
    assertFalse(tracker.isActive("com.example.a", 300_000L))
  }

  @Test
  fun `remaining time counts down to zero and never below`() {
    val tracker = IntentSessionTracker()
    tracker.grant("com.example.a", 2, 10_000L)

    assertEquals(120_000L, tracker.remainingMs("com.example.a", 10_000L))
    assertEquals(30_000L, tracker.remainingMs("com.example.a", 100_000L))
    assertEquals(0L, tracker.remainingMs("com.example.a", 200_000L))
    assertEquals(0L, tracker.remainingMs("com.example.unknown", 10_000L))
  }

  @Test
  fun `sessions are tracked per app`() {
    val tracker = IntentSessionTracker()
    tracker.grant("com.example.a", 1, 0L)
    tracker.grant("com.example.b", 10, 0L)

    assertFalse(tracker.isActive("com.example.a", 60_000L))
    assertTrue(tracker.isActive("com.example.b", 60_000L))
  }

  @Test
  fun `a new grant replaces the previous one`() {
    val tracker = IntentSessionTracker()
    tracker.grant("com.example.a", 1, 0L)
    tracker.grant("com.example.a", 10, 30_000L)

    assertTrue(tracker.isActive("com.example.a", 300_000L))
    assertFalse(tracker.isActive("com.example.a", 630_000L))
  }

  @Test
  fun `grants within one to sixty minutes are accepted`() {
    val tracker = IntentSessionTracker()
    tracker.grant("com.example.a", 1, 0L)
    tracker.grant("com.example.b", 60, 0L)

    assertTrue(tracker.isActive("com.example.a", 1_000L))
    assertTrue(tracker.isActive("com.example.b", 1_000L))
  }

  @Test(expected = IllegalArgumentException::class)
  fun `a zero minute grant is rejected`() {
    IntentSessionTracker().grant("com.example.a", 0, 0L)
  }

  @Test(expected = IllegalArgumentException::class)
  fun `a grant above sixty minutes is rejected`() {
    IntentSessionTracker().grant("com.example.a", 61, 0L)
  }

  @Test
  fun `ending a session deactivates it`() {
    val tracker = IntentSessionTracker()
    tracker.grant("com.example.a", 5, 0L)

    assertTrue(tracker.end("com.example.a"))
    assertFalse(tracker.isActive("com.example.a", 1_000L))
    assertFalse(tracker.end("com.example.a"))
  }

  @Test
  fun `reset drops every session`() {
    val tracker = IntentSessionTracker()
    tracker.grant("com.example.a", 5, 0L)
    tracker.grant("com.example.b", 10, 0L)
    tracker.reset()

    assertFalse(tracker.isActive("com.example.a", 1_000L))
    assertFalse(tracker.isActive("com.example.b", 1_000L))
  }
}
