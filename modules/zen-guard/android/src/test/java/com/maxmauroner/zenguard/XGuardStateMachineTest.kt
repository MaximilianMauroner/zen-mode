package com.maxmauroner.zenguard

import org.junit.Assert.assertEquals
import org.junit.Test

class XGuardStateMachineTest {
  @Test fun `Home runtime snapshot includes time until X backgrounds`() {
    val guard = XGuardStateMachine()
    val settings = XSettings(homeAllowanceMs = 300_000L)
    guard.next(XSurface.HOME, 1_000L, settings)
    guard.pause(121_000L)

    assertEquals(120_000L, guard.homeRuntimeState(500_000L).usedMs)
    guard.next(XSurface.HOME, 500_000L, settings)
    assertEquals(180_000L, guard.homeRuntimeState(560_000L).usedMs)
  }

  @Test fun `backgrounding starts the Home lockout when the final slice exhausts the allowance`() {
    val guard = XGuardStateMachine()
    val settings = XSettings(homeAllowanceMs = 60_000L)
    guard.next(XSurface.HOME, 1_000L, settings)
    guard.pause(61_000L, settings)

    assertEquals(3_661_000L, guard.homeRuntimeState(61_000L).blockedUntilElapsedMs)
  }

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

  @Test fun `an unknown tree flushes the known Home interval before pausing`() {
    val guard = XGuardStateMachine()
    val settings = XSettings(homeAllowanceMs = 300_000L)

    guard.next(XSurface.HOME, 0L, settings)
    guard.next(XSurface.UNKNOWN, 60_000L, settings)

    assertEquals(60_000L, guard.homeRuntimeState(60_000L).usedMs)
  }

  @Test fun `a restored active Home snapshot waits for a fresh observation`() {
    val settings = XSettings(homeAllowanceMs = 300_000L)
    val beforeRestart = XGuardStateMachine()
    beforeRestart.next(XSurface.HOME, 0L, settings)
    beforeRestart.next(XSurface.HOME, 60_000L, settings)

    val afterRestart = XGuardStateMachine()
    afterRestart.restore(beforeRestart.homeRuntimeState(60_000L))

    assertEquals(60_000L, afterRestart.homeRuntimeState(60_000L).usedMs)
    afterRestart.next(XSurface.HOME, 120_000L, settings)
    assertEquals(60_000L, afterRestart.homeRuntimeState(120_000L).usedMs)
    afterRestart.next(XSurface.HOME, 180_000L, settings)
    assertEquals(120_000L, afterRestart.homeRuntimeState(180_000L).usedMs)
  }

  @Test fun `a restored Home lockout cannot grant a new allowance`() {
    val settings = XSettings(homeAllowanceMs = 60_000L)
    val beforeRestart = XGuardStateMachine()
    beforeRestart.next(XSurface.HOME, 0L, settings)
    assertEquals(XAction.HOME_BREAK, beforeRestart.next(XSurface.HOME, 60_000L, settings))

    val afterRestart = XGuardStateMachine()
    afterRestart.restore(beforeRestart.homeRuntimeState(60_000L))

    assertEquals(XAction.HOME_BREAK, afterRestart.next(XSurface.HOME, 61_000L, settings))
    assertEquals(XAction.NONE, afterRestart.next(XSurface.HOME, 3_660_000L, settings))
  }

  @Test fun `an unverifiable service gap is not charged before fresh Home observation`() {
    val settings = XSettings(homeAllowanceMs = 300_000L)
    val beforeRestart = XGuardStateMachine()
    beforeRestart.next(XSurface.HOME, 0L, settings)
    beforeRestart.next(XSurface.HOME, 60_000L, settings)
    val restored = XGuardStateMachine()
    restored.restore(beforeRestart.homeRuntimeState(60_000L), 60_000L)

    assertEquals(XAction.NONE, restored.next(XSurface.OTHER, 600_000L, settings))
    assertEquals(HomeFeedUsageState.UNKNOWN, restored.homeRuntimeState(600_000L).usageState)
    assertEquals(60_000L, restored.homeRuntimeState(600_000L).usedMs)
    assertEquals(XAction.NONE, restored.next(XSurface.HOME, 600_001L, settings))
    assertEquals(60_000L, restored.homeRuntimeState(600_001L).usedMs)
    restored.next(XSurface.HOME, 660_001L, settings)
    assertEquals(120_000L, restored.homeRuntimeState(660_001L).usedMs)
  }

  @Test fun `an unverifiable reboot lockout blocks without trusting wall time`() {
    val settings = XSettings(homeAllowanceMs = 60_000L, homeLockoutMs = 3_600_000L)
    val restored = XGuardStateMachine()
    restored.restore(
      HomeFeedRuntimeState(
        usedMs = 60_000L,
        blockedUntilElapsedMs = null,
        usageState = HomeFeedUsageState.PAUSED,
        lockoutState = HomeFeedLockoutState.UNKNOWN,
        capturedAtElapsedMs = 60_000L,
      ),
      60_000L,
    )

    assertEquals(XAction.HOME_UNAVAILABLE, restored.next(XSurface.HOME, 61_000L, settings))
    assertEquals(HomeFeedLockoutState.UNKNOWN, restored.homeRuntimeState(61_000L).lockoutState)
    assertEquals(XAction.NONE, restored.next(XSurface.HOME, 3_660_000L, settings))
    assertEquals(0L, restored.homeRuntimeState(3_660_000L).usedMs)
  }

  @Test fun `expired lockout serializes as a clean post-lockout state`() {
    val settings = XSettings(homeAllowanceMs = 60_000L, homeLockoutMs = 3_600_000L)
    val guard = XGuardStateMachine()
    guard.next(XSurface.HOME, 0L, settings)
    assertEquals(XAction.HOME_BREAK, guard.next(XSurface.HOME, 60_000L, settings))

    val expired = guard.homeRuntimeState(3_660_000L)
    assertEquals(0L, expired.usedMs)
    assertEquals(null, expired.blockedUntilElapsedMs)
    assertEquals(HomeFeedLockoutState.NONE, expired.lockoutState)

    val restored = XGuardStateMachine()
    restored.restore(expired, 3_660_000L)
    assertEquals(XAction.NONE, restored.next(XSurface.HOME, 3_660_001L, settings))
  }

  @Test fun `usage and lockout arithmetic saturates at safe bounds`() {
    assertEquals(HOME_FEED_MAX_SAFE_USAGE_MS, saturatingUsageAdd(HOME_FEED_MAX_SAFE_USAGE_MS - 1L, Long.MAX_VALUE))
    assertEquals(HOME_FEED_MAX_SAFE_TIMESTAMP_MS, saturatingTimestampAdd(HOME_FEED_MAX_SAFE_TIMESTAMP_MS, Long.MAX_VALUE))
  }

  @Test fun `storage failure blocks Home without disabling video protection`() {
    val guard = XGuardStateMachine()
    guard.markStorageUnavailable()

    assertEquals(XAction.HOME_UNAVAILABLE, guard.next(XSurface.HOME, 1_000L, XSettings()))
    assertEquals(XAction.LEAVE_VIDEO, guard.next(XSurface.VIDEO, 2_000L, XSettings(), videoPagerAdvanced = true))
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

  @Test fun `an unobserved feed does not pause a feed that is already running`() {
    // Home is observed and enforcing. The user then switches Videos on, before
    // the service has ever seen the video pager. Home must keep enforcing.
    val guard = XGuardStateMachine()
    val homeOnly = XSettings(homeEnabled = true, videosEnabled = false, homeAllowanceMs = 100L)
    guard.next(XSurface.HOME, 0L, homeOnly)
    assertEquals(XAction.HOME_BREAK, guard.next(XSurface.HOME, 100L, homeOnly))

    guard.leaveBlockedSurface()
    guard.reset()

    // Videos is switched on but its signal is still missing, so videosEnabled
    // stays false while homeEnabled stays true.
    val videosPending = homeOnly.copy(videosEnabled = false)
    guard.next(XSurface.HOME, 200L, videosPending)
    assertEquals(XAction.HOME_BREAK, guard.next(XSurface.HOME, 300L, videosPending))
    // The unobserved video feed enforces nothing yet.
    assertEquals(XAction.NONE, guard.next(XSurface.VIDEO, 400L, videosPending, true))
  }

  @Test fun `a feed starts enforcing on its own once its signal arrives`() {
    val guard = XGuardStateMachine()
    val bothObserved = XSettings(homeEnabled = true, videosEnabled = true, homeAllowanceMs = 100L)
    assertEquals(XAction.NONE, guard.next(XSurface.VIDEO, 0L, bothObserved))
    assertEquals(XAction.LEAVE_VIDEO, guard.next(XSurface.VIDEO, 1_000L, bothObserved, true))
  }

  @Test fun `required signals follow the switched-on feeds`() {
    assertEquals(0, requiredXSignals(homeEnabled = false, videosEnabled = false))
    assertEquals(ZenGuardPreferences.X_HOME_SIGNAL, requiredXSignals(homeEnabled = true, videosEnabled = false))
    assertEquals(ZenGuardPreferences.X_VIDEO_SIGNAL, requiredXSignals(homeEnabled = false, videosEnabled = true))
    assertEquals(
      ZenGuardPreferences.X_HOME_SIGNAL or ZenGuardPreferences.X_VIDEO_SIGNAL,
      requiredXSignals(homeEnabled = true, videosEnabled = true),
    )
  }

  private fun requiredXSignals(homeEnabled: Boolean, videosEnabled: Boolean): Int =
    (if (homeEnabled) ZenGuardPreferences.X_HOME_SIGNAL else 0) or
      (if (videosEnabled) ZenGuardPreferences.X_VIDEO_SIGNAL else 0)

  @Test fun `X detection uses screen identifiers and ignores post text`() {
    assertEquals(XSurface.HOME, XDetector.detect(listOf(NodeSignal(viewId = "scaffold_home_tabbed"))))
    assertEquals(XSurface.VIDEO, XDetector.detect(listOf(NodeSignal(viewId = "VideoTab"))))
    assertEquals(XSurface.OTHER, XDetector.detect(listOf(NodeSignal(viewId = "Search"))))
    assertEquals(XSurface.UNKNOWN, XDetector.detect(listOf(NodeSignal(text = "VideoTab scaffold_home_tabbed"))))
  }
}
