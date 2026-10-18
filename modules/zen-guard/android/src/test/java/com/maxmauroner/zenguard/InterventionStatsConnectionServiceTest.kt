package com.maxmauroner.zenguard

import android.content.Context
import android.provider.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class InterventionStatsConnectionServiceTest {
  private lateinit var context: Context
  private lateinit var controller: ServiceController<ZenGuardAccessibilityService>

  @Before fun setUp() {
    context = RuntimeEnvironment.getApplication()
    HomeFeedStatusFailureRegistry.clearForTests()
    Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 7)
    controller = Robolectric.buildService(ZenGuardAccessibilityService::class.java).create()
    connect()
  }

  @After fun tearDown() {
    controller.destroy()
    HomeFeedStatusFailureRegistry.clearForTests()
  }

  @Test fun `another connection on the same service preserves callback dedupe and later counts`() {
    val service = controller.get()
    assertTrue(service.recordAppLimitStats("other.app", EnforcementStatsOutcome.SUCCESS))
    assertTrue(recordVideoStats("callback:1", EnforcementStatsOutcome.SUCCESS))

    connect()

    assertFalse(recordVideoStats("callback:1", EnforcementStatsOutcome.SUCCESS))
    assertTrue(recordVideoStats("callback:2", EnforcementStatsOutcome.SUCCESS))
    val snapshot = EnforcementStatsStore(context).snapshot()
    assertEquals(3L, snapshot.total)
    assertEquals(1L, snapshot.counts[EnforcementReason.APP_LIMIT.key])
    assertEquals(2L, snapshot.counts[EnforcementReason.X_VIDEOS.key])
  }

  private fun recordVideoStats(callbackKey: String, outcome: EnforcementStatsOutcome): Boolean {
    val owner = ZenGuardAccessibilityService::class.java.getDeclaredField("x").apply {
      isAccessible = true
    }.get(controller.get()) as XOwner
    return XOwner::class.java.getDeclaredMethod(
      "recordXVideoStats",
      String::class.java,
      EnforcementStatsOutcome::class.java,
    ).apply { isAccessible = true }.invoke(owner, callbackKey, outcome) as Boolean
  }

  private fun connect() {
    ZenGuardAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply {
      isAccessible = true
    }.invoke(controller.get())
  }
}
