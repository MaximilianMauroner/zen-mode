package com.maxmauroner.zenguard

import org.junit.Assert.assertEquals
import org.junit.Test

class VisitChoicesTest {
  @Test fun `offers the longest visit and up to two shorter steps, shortest first`() {
    assertEquals(listOf(15, 20, 30), visitChoices(30, null))
    assertEquals(listOf(30, 45, 60), visitChoices(60, null))
    assertEquals(listOf(2, 5, 10), visitChoices(10, null))
  }

  @Test fun `a short rule offers only the lengths that fit`() {
    assertEquals(listOf(1), visitChoices(1, null))
    assertEquals(listOf(1, 2), visitChoices(2, null))
  }

  @Test fun `the minutes left today lower every choice`() {
    assertEquals(listOf(1, 2, 5), visitChoices(60, 5))
    assertEquals(listOf(10, 15, 17), visitChoices(60, 17))
    assertEquals(listOf(15, 20, 30), visitChoices(30, 90))
  }

  @Test fun `no minutes left today offers no visit`() {
    assertEquals(emptyList<Int>(), visitChoices(60, 0))
  }
}
