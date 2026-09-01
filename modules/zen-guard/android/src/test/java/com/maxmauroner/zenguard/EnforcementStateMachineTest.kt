package com.maxmauroner.zenguard

import org.junit.Assert.assertEquals
import org.junit.Test

class EnforcementStateMachineTest {
  @Test
  fun boundsActionsAndResetsAfterLeavingShorts() {
    val state = EnforcementStateMachine(cooldownMs = 100, fallbackAfterMs = 200)

    assertEquals(EnforcementAction.BACK, state.next(true, 1_000))
    assertEquals(EnforcementAction.NONE, state.next(true, 1_050))
    assertEquals(EnforcementAction.HOME, state.next(true, 1_250))
    assertEquals(EnforcementAction.NONE, state.next(true, 1_500))
    assertEquals(EnforcementAction.NONE, state.next(false, 1_600))
    assertEquals(EnforcementAction.BACK, state.next(true, 1_700))
  }
}
