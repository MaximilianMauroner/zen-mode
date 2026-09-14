package com.maxmauroner.zenguard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentPolicyTest {
  @Test fun acceptsOnlyTheCurrentConsentVersion() {
    assertTrue(ConsentPolicy.isCurrent(ConsentPolicy.CURRENT_VERSION))
    assertFalse(ConsentPolicy.isCurrent(ConsentPolicy.CURRENT_VERSION - 1))
  }

  @Test fun missingOrUnreadableConsentFailsClosed() {
    assertFalse(ConsentPolicy.isCurrent(null))
    assertFalse(ConsentPolicy.isCurrent("2"))
    assertFalse(ConsentPolicy.isCurrent(true))
  }
}
