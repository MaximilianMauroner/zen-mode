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

  @Test fun firstSettledPagerIdentityIsATransitionAfterTheViewerWasAlreadyObserved() {
    val state = EnforcementStateMachine()
    assertEquals(EnforcementAction.NONE, state.next(true, null, 0))
    assertEquals(EnforcementAction.NONE, state.next(true, null, 100))
    assertEquals(EnforcementAction.LEAVE_SHORTS, state.next(true, 1, 200, pagerTransitionIndex = 1))
  }

  @Test fun anInitialPagerCallbackStillAllowsTheEntryShort() {
    val state = EnforcementStateMachine()
    assertEquals(EnforcementAction.NONE, state.next(true, 0, 0, pagerTransitionIndex = 0))
    assertEquals(EnforcementAction.LEAVE_SHORTS, state.next(true, 1, 100, pagerTransitionIndex = 1))
  }

  @Test fun aSettledPagerIndexZeroProvesAReverseTransitionFromAKnownBaseline() {
    val state = EnforcementStateMachine()
    assertEquals(EnforcementAction.NONE, state.next(true, 7, 0, pagerTransitionIndex = 7))
    assertEquals(EnforcementAction.LEAVE_SHORTS, state.next(true, 0, 100, pagerTransitionIndex = 0))
  }

  @Test fun aFailedFallbackExitRemainsPendingWithoutAnotherSwipe() {
    val state = EnforcementStateMachine(cooldownMs = 100)
    assertEquals(EnforcementAction.NONE, state.next(true, null, 0))
    assertEquals(EnforcementAction.LEAVE_SHORTS, state.next(true, 1, 100, pagerTransitionIndex = 1))
    assertEquals(EnforcementAction.NONE, state.next(true, null, 150))
    assertEquals(EnforcementAction.LEAVE_SHORTS, state.next(true, null, 200))
    assertEquals(EnforcementAction.NONE, state.next(false, null, 250))
    assertEquals(EnforcementAction.NONE, state.next(true, null, 300))
  }

}
