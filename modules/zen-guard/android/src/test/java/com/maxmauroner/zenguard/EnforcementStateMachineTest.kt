package com.maxmauroner.zenguard

import org.junit.Assert.assertEquals
import org.junit.Test

class EnforcementStateMachineTest {
  @Test fun allowsTheFirstShortIncludingRepeatedAndPlaybackEvents() {
    val state = EnforcementStateMachine()
    assertEquals(EnforcementAction.NONE, state.next(true, 7, 0))
    assertEquals(EnforcementAction.NONE, state.next(true, 7, 60_000))
    assertEquals(EnforcementAction.NONE, state.next(true, null, 61_000))
    assertEquals(EnforcementAction.LEAVE_SHORTS, state.next(true, 8, 62_000))
  }

  @Test fun blocksBothSwipeDirectionsAndBoundsRepeatedActions() {
    val state = EnforcementStateMachine(cooldownMs = 100)
    assertEquals(EnforcementAction.NONE, state.next(true, 7, 0))
    assertEquals(EnforcementAction.LEAVE_SHORTS, state.next(true, 6, 1_000))
    assertEquals(EnforcementAction.NONE, state.next(true, 6, 1_050))
    assertEquals(EnforcementAction.LEAVE_SHORTS, state.next(true, 6, 1_200))
  }

  @Test fun aNewVisitAllowsOneVideoAndUnknownIndicesDoNotCountAsVideos() {
    val state = EnforcementStateMachine()
    assertEquals(EnforcementAction.NONE, state.next(true, -1, 0))
    assertEquals(EnforcementAction.NONE, state.next(true, 3, 100))
    assertEquals(EnforcementAction.LEAVE_SHORTS, state.next(true, 4, 200))
    assertEquals(EnforcementAction.NONE, state.next(false, null, 300))
    assertEquals(EnforcementAction.NONE, state.next(true, 4, 400))
    assertEquals(EnforcementAction.LEAVE_SHORTS, state.next(true, 5, 500))
  }

}
