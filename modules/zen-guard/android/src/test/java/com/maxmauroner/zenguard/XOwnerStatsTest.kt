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
class XOwnerStatsTest {
  private lateinit var context: Context
  private lateinit var controller: ServiceController<ZenGuardAccessibilityService>
  private lateinit var owner: XOwner
  private lateinit var store: EnforcementStatsStore

  @Before fun setUp() {
    context = RuntimeEnvironment.getApplication()
    HomeFeedStatusFailureRegistry.clearForTests()
    Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 7)
    connectNewService()
    store = EnforcementStatsStore(context)
  }

  @After fun tearDown() {
    controller.destroy()
    HomeFeedStatusFailureRegistry.clearForTests()
  }

  @Test fun `failed actions and unattached overlays never reach the ledger`() {
    assertFalse(recordVideoStats("failed-node-action", EnforcementStatsOutcome.FAILED))
    assertFalse(recordHomeStats(false, false, EnforcementStatsOutcome.SUCCESS))

    val snapshot = store.snapshot()
    assertEquals(0L, snapshot.total)
    assertTrue(snapshot.counts.values.all { it == 0L })
  }

  @Test fun `successful video actions deduplicate retries but accept a later intervention`() {
    assertTrue(recordVideoStats("scroll-callback-1", EnforcementStatsOutcome.SUCCESS))
    assertFalse(recordVideoStats("scroll-callback-1", EnforcementStatsOutcome.SUCCESS))
    assertTrue(recordVideoStats("scroll-callback-2", EnforcementStatsOutcome.SUCCESS))

    val snapshot = store.snapshot()
    assertEquals(2L, snapshot.total)
    assertEquals(2L, snapshot.counts[EnforcementReason.X_VIDEOS.key])
  }

  @Test fun `X and app limits share one ledger with separate eligibility`() {
    assertTrue(recordHomeStats(false, true, EnforcementStatsOutcome.SUCCESS))
    assertFalse(recordHomeStats(true, true, EnforcementStatsOutcome.SUCCESS))
    assertTrue(recordVideoStats("video-callback-1", EnforcementStatsOutcome.SUCCESS))
    assertFalse(recordVideoStats("video-callback-1", EnforcementStatsOutcome.SUCCESS))
    val stats = ZenGuardAccessibilityService::class.java.getDeclaredField("stats").apply {
      isAccessible = true
    }.get(controller.get()) as InterventionStats
    assertTrue(stats.record(EnforcementReason.APP_LIMIT, EnforcementStatsOutcome.SUCCESS, "app.one"))
    assertFalse(stats.record(EnforcementReason.APP_LIMIT, EnforcementStatsOutcome.SUCCESS, "app.one"))
    assertTrue(stats.record(EnforcementReason.APP_LIMIT, EnforcementStatsOutcome.SUCCESS, "app.two"))

    val snapshot = store.snapshot()
    assertEquals(4L, snapshot.total)
    assertEquals(1L, snapshot.counts[EnforcementReason.X_HOME.key])
    assertEquals(1L, snapshot.counts[EnforcementReason.X_VIDEOS.key])
    assertEquals(2L, snapshot.counts[EnforcementReason.APP_LIMIT.key])
  }

  @Test fun `Home lockout identity survives owner reconnection and unrelated stats`() {
    assertTrue(recordHomeStats(false, true, EnforcementStatsOutcome.SUCCESS, 9_000L))
    assertTrue(recordVideoStats("video:1", EnforcementStatsOutcome.SUCCESS))

    controller.destroy()
    connectNewService()
    store = EnforcementStatsStore(context)

    assertFalse(recordHomeStats(false, true, EnforcementStatsOutcome.SUCCESS, 9_000L))
    val snapshot = store.snapshot()
    assertEquals(2L, snapshot.total)
    assertEquals(1L, snapshot.counts[EnforcementReason.X_HOME.key])
  }

  private fun connectNewService() {
    controller = Robolectric.buildService(ZenGuardAccessibilityService::class.java).create()
    ZenGuardAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply {
      isAccessible = true
    }.invoke(controller.get())
    owner = ZenGuardAccessibilityService::class.java.getDeclaredField("x").apply {
      isAccessible = true
    }.get(controller.get()) as XOwner
  }

  private fun recordHomeStats(
    wasShowing: Boolean,
    overlayAttached: Boolean,
    outcome: EnforcementStatsOutcome,
    lockoutDeadlineElapsedMs: Long? = null,
  ): Boolean = XOwner::class.java.getDeclaredMethod(
    "recordXHomeStats",
    Boolean::class.javaPrimitiveType,
    Boolean::class.javaPrimitiveType,
    EnforcementStatsOutcome::class.java,
    Long::class.javaObjectType,
  ).apply { isAccessible = true }.invoke(owner, wasShowing, overlayAttached, outcome, lockoutDeadlineElapsedMs) as Boolean

  private fun recordVideoStats(callbackKey: String, outcome: EnforcementStatsOutcome): Boolean =
    XOwner::class.java.getDeclaredMethod(
      "recordXVideoStats",
      String::class.java,
      EnforcementStatsOutcome::class.java,
    ).apply { isAccessible = true }.invoke(owner, callbackKey, outcome) as Boolean
}
