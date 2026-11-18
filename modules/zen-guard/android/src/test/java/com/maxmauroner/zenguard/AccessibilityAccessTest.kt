package com.maxmauroner.zenguard

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystem
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], instrumentedPackages = ["com.maxmauroner.zenguard"])
class AccessibilityAccessTest {
  private val context = RuntimeEnvironment.getApplication()

  @Test fun `first use and unrelated accessibility changes have no off period`() {
    assertEquals(AccessibilityAccess.Status(false, null), AccessibilityAccess.read(context))
    setEnabled(true)
    assertEquals(AccessibilityAccess.Status(true, null), AccessibilityAccess.read(context))
    setEnabled(true, otherService = true)
    assertEquals(AccessibilityAccess.Status(true, null), AccessibilityAccess.read(context))
  }

  @Test fun `unbind records an off period even if access returns before the next status check`() {
    val controller = Robolectric.buildService(ZenGuardAccessibilityService::class.java).create()
    try {
      setEnabled(true)
      connect(controller.get())
      setEnabled(false, otherService = true)
      val disabledAt = ShadowSystem.currentTimeMillis()
      controller.get().onUnbind(Intent())
      ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
      setEnabled(true)
      assertEquals(
        AccessibilityAccess.Status(true, AccessibilityAccess.OffPeriod(disabledAt, ShadowSystem.currentTimeMillis())),
        AccessibilityAccess.read(context),
      )
    } finally {
      controller.destroy()
    }
  }

  @Test fun `destroy with access still enabled reports a long gap when a new service connects`() {
    val original = Robolectric.buildService(ZenGuardAccessibilityService::class.java).create()
    setEnabled(true)
    connect(original.get())
    val disconnectedAt = ShadowSystem.currentTimeMillis()
    original.destroy()

    ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
    assertEquals(AccessibilityAccess.Status(true, null), AccessibilityAccess.read(context))
    val replacement = Robolectric.buildService(ZenGuardAccessibilityService::class.java).create()
    try {
      connect(replacement.get())
      assertEquals(
        AccessibilityAccess.Status(true, AccessibilityAccess.OffPeriod(disconnectedAt, ShadowSystem.currentTimeMillis())),
        AccessibilityAccess.read(context),
      )
    } finally {
      replacement.destroy()
    }
  }

  @Test fun `a quick update or reboot reconnect leaves no notice`() {
    val original = Robolectric.buildService(ZenGuardAccessibilityService::class.java).create()
    setEnabled(true)
    connect(original.get())
    original.get().onUnbind(Intent())
    original.destroy()

    ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
    val replacement = Robolectric.buildService(ZenGuardAccessibilityService::class.java).create()
    try {
      connect(replacement.get())
      ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
      assertEquals(AccessibilityAccess.Status(true, null), AccessibilityAccess.read(context))
    } finally {
      replacement.destroy()
    }
  }

  @Test fun `two off periods retain the earliest start and latest end and reject stale acknowledgment`() {
    setEnabled(true)
    AccessibilityAccess.read(context)
    setEnabled(false)
    val firstOff = requireNotNull(AccessibilityAccess.read(context).offPeriod)
    ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
    setEnabled(true)
    val firstRestored = requireNotNull(AccessibilityAccess.read(context).offPeriod)
    assertTrue(firstRestored.through > firstOff.through)
    ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
    setEnabled(false)
    AccessibilityAccess.acknowledge(context, firstRestored.through)
    assertEquals(firstOff.since, AccessibilityAccess.read(context).offPeriod?.since)
    ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
    setEnabled(true)
    val combined = AccessibilityAccess.OffPeriod(firstOff.since, ShadowSystem.currentTimeMillis())
    assertEquals(AccessibilityAccess.Status(true, combined), AccessibilityAccess.read(context))

    AccessibilityAccess.acknowledge(context, combined.through)
    assertNull(AccessibilityAccess.read(context).offPeriod)
  }

  private fun connect(service: ZenGuardAccessibilityService) {
    ZenGuardAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply {
      isAccessible = true
    }.invoke(service)
  }

  private fun setEnabled(enabled: Boolean, otherService: Boolean = false) {
    val services = buildList {
      if (enabled) add(ComponentName(context, ZenGuardAccessibilityService::class.java).flattenToShortString())
      if (otherService) add("com.example/.OtherService")
    }
    Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services.joinToString(":"))
  }
}
