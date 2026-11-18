package com.maxmauroner.zenguard

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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
      val service = controller.get()
      ZenGuardAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply {
        isAccessible = true
      }.invoke(service)

      service.onUnbind(Intent())
      assertNull(AccessibilityAccess.read(context).disabledAt)

      setEnabled(false, otherService = true)
      val disabledBefore = System.currentTimeMillis()
      service.onUnbind(Intent())
      val disabledAfter = System.currentTimeMillis()
      ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
      setEnabled(true)
      val restored = AccessibilityAccess.read(context)
      assertTrue(restored.enabled)
      assertTrue(requireNotNull(restored.disabledAt) in disabledBefore..disabledAfter)
    } finally {
      controller.destroy()
    }
  }

  @Test fun `acknowledging an off period leaves a newer period available`() {
    setEnabled(true)
    AccessibilityAccess.read(context)
    setEnabled(false)
    val firstOff = requireNotNull(AccessibilityAccess.read(context).disabledAt)
    setEnabled(true)
    AccessibilityAccess.read(context)
    ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
    setEnabled(false)
    val secondOff = requireNotNull(AccessibilityAccess.read(context).disabledAt)
    assertNotEquals(firstOff, secondOff)

    AccessibilityAccess.acknowledge(context, firstOff)
    assertEquals(AccessibilityAccess.Status(false, secondOff), AccessibilityAccess.read(context))
    AccessibilityAccess.acknowledge(context, secondOff)
    assertEquals(AccessibilityAccess.Status(false, null), AccessibilityAccess.read(context))
  }

  private fun setEnabled(enabled: Boolean, otherService: Boolean = false) {
    val services = buildList {
      if (enabled) add(ComponentName(context, ZenGuardAccessibilityService::class.java).flattenToShortString())
      if (otherService) add("com.example/.OtherService")
    }
    Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services.joinToString(":"))
  }
}
