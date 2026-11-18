package com.maxmauroner.zenguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], instrumentedPackages = ["com.maxmauroner.zenguard"])
class GuidedAppRulesTest {
  private val context = RuntimeEnvironment.getApplication()
  private val caps = AppLimitStore(context)
  private val visits = IntentAppStore(context)
  private val app = "com.google.android.youtube"

  @Test fun `a manual daily cap clears the pending guided visit`() {
    caps.setLimit(app, 60, guidedVisitPending = true)
    assertTrue(caps.hasPendingGuidedVisit(app))
    caps.setLimit(app, 60, guidedVisitPending = false)
    assertFalse(caps.hasPendingGuidedVisit(app))
  }

  @Test fun `saving a timed visit clears the pending guided visit`() {
    caps.setLimit(app, 60, guidedVisitPending = true)
    visits.setIntent(app, 5, 5)
    assertFalse(caps.hasPendingGuidedVisit(app))
  }

  @Test fun `removing a timed visit clears the pending guided visit even when no visit was saved`() {
    caps.setLimit(app, 60, guidedVisitPending = true)
    visits.removeIntent(app)
    assertFalse(caps.hasPendingGuidedVisit(app))
  }

  @Test fun `discarding unfinished setup removes only marked caps`() {
    val otherApp = "com.instagram.android"
    caps.setLimit(app, 60, guidedVisitPending = true)
    caps.setLimit(otherApp, 60)

    caps.removePendingGuidedCaps()

    assertEquals(mapOf(otherApp to 60), caps.limits())
    assertFalse(caps.hasPendingGuidedVisit(app))
  }
}
