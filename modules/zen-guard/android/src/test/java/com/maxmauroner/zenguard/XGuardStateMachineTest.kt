package com.maxmauroner.zenguard

import org.junit.Assert.assertEquals
import org.junit.Test

class XGuardStateMachineTest {
  @Test fun `Home remains blocked for one hour after leaving and reopening X`() {
    val guard = XGuardStateMachine()
    val settings = XSettings(homeAllowanceMs = 60_000L)
    assertEquals(XAction.NONE, guard.next(XSurface.HOME, 0L, settings))
    assertEquals(XAction.NONE, guard.next(XSurface.HOME, 30_000L, settings))
    guard.pause()
    assertEquals(XAction.NONE, guard.next(XSurface.HOME, 300_000L, settings))
    assertEquals(XAction.HOME_BREAK, guard.next(XSurface.HOME, 330_000L, settings))
    assertEquals(XAction.HOME_BREAK, guard.next(XSurface.HOME, 331_000L, settings))
    guard.leaveBlockedSurface()
    guard.pause()
    assertEquals(XAction.HOME_BREAK, guard.next(XSurface.HOME, 3_929_999L, settings))
    assertEquals(XAction.NONE, guard.next(XSurface.HOME, 3_930_000L, settings))
  }

  @Test fun `other X surfaces end the Home session while unknown trees only pause it`() {
    val guard = XGuardStateMachine()
    val settings = XSettings(homeAllowanceMs = 100L)
    guard.next(XSurface.HOME, 0L, settings)
    guard.next(XSurface.HOME, 60L, settings)
    guard.next(XSurface.UNKNOWN, 70L, settings)
    guard.next(XSurface.HOME, 300L, settings)
    assertEquals(XAction.NONE, guard.next(XSurface.HOME, 330L, settings))
    guard.next(XSurface.VIDEO, 350L, settings)
    assertEquals(XAction.NONE, guard.next(XSurface.HOME, 500L, settings))
  }

  @Test fun `other X surfaces stay allowed during the Home lockout`() {
    val guard = XGuardStateMachine()
    val settings = XSettings(homeAllowanceMs = 100L)
    guard.next(XSurface.HOME, 0L, settings)
    assertEquals(XAction.HOME_BREAK, guard.next(XSurface.HOME, 100L, settings))
    assertEquals(XAction.NONE, guard.next(XSurface.OTHER, 200L, settings))
    assertEquals(XAction.HOME_BREAK, guard.next(XSurface.HOME, 300L, settings))
  }

  @Test fun `first video and playback events stay open but pager advancement leaves`() {
    val guard = XGuardStateMachine()
    val settings = XSettings()
    assertEquals(XAction.NONE, guard.next(XSurface.VIDEO, 0L, settings))
    assertEquals(XAction.NONE, guard.next(XSurface.VIDEO, 5_000L, settings))
    assertEquals(XAction.LEAVE_VIDEO, guard.next(XSurface.VIDEO, 6_000L, settings, true))
    assertEquals(XAction.NONE, guard.next(XSurface.VIDEO, 6_100L, settings, true))
    assertEquals(XAction.LEAVE_VIDEO, guard.next(XSurface.VIDEO, 7_000L, settings, true))
    guard.next(XSurface.OTHER, 7_100L, settings)
    assertEquals(XAction.NONE, guard.next(XSurface.VIDEO, 7_200L, settings))
  }

  @Test fun `Home and video rules can each be disabled independently`() {
    val guard = XGuardStateMachine()
    val settings = XSettings(homeEnabled = false, videosEnabled = true, homeAllowanceMs = 1L)
    guard.next(XSurface.HOME, 0L, settings)
    assertEquals(XAction.NONE, guard.next(XSurface.HOME, 60_000L, settings))
    assertEquals(XAction.LEAVE_VIDEO, guard.next(XSurface.VIDEO, 61_000L, settings, true))
    assertEquals(XAction.NONE, guard.next(XSurface.VIDEO, 63_000L, settings.copy(videosEnabled = false), true))
  }

  @Test fun `X detection uses screen identifiers and ignores post text`() {
    assertEquals(XSurface.HOME, XDetector.detect(listOf(NodeSignal(viewId = "scaffold_home_tabbed"))))
    assertEquals(XSurface.VIDEO, XDetector.detect(listOf(NodeSignal(viewId = "VideoTab"))))
    assertEquals(XSurface.OTHER, XDetector.detect(listOf(NodeSignal(viewId = "Search"))))
    assertEquals(XSurface.UNKNOWN, XDetector.detect(listOf(NodeSignal(text = "VideoTab scaffold_home_tabbed"))))
  }
}
