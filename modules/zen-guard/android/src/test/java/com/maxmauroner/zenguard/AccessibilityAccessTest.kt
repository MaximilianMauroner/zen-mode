package com.maxmauroner.zenguard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystem
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], instrumentedPackages = ["com.maxmauroner.zenguard"])
class AccessibilityAccessTest {
  private val context = RuntimeEnvironment.getApplication()

  @Before fun setBootCount() {
    Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 7)
  }

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

  @Test fun `a quick clean reconnect leaves no notice`() {
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

  @Test fun `a reconnect after a force stop reports the gap from the last idle heartbeat`() {
    val original = Robolectric.buildService(ZenGuardAccessibilityService::class.java).create()
    val replacement = Robolectric.buildService(ZenGuardAccessibilityService::class.java).create()
    try {
      setEnabled(true)
      connect(original.get())
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(90))
      val lastHeartbeat = ShadowSystem.currentTimeMillis()
      setEnabled(false)
      ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
      setEnabled(true)
      connect(replacement.get())
      assertEquals(
        AccessibilityAccess.Status(true, AccessibilityAccess.OffPeriod(lastHeartbeat, ShadowSystem.currentTimeMillis())),
        AccessibilityAccess.read(context),
      )
    } finally {
      replacement.destroy()
      original.destroy()
    }
  }

  @Test fun `access turned off after a force stop reports the gap from the last heartbeat`() {
    setEnabled(true)
    AccessibilityAccess.connected(context)
    val lastHeartbeat = ShadowSystem.currentTimeMillis()
    ShadowSystemClock.advanceBy(Duration.ofHours(4))
    setEnabled(false)
    assertEquals(
      AccessibilityAccess.Status(false, AccessibilityAccess.OffPeriod(lastHeartbeat, ShadowSystem.currentTimeMillis())),
      AccessibilityAccess.read(context),
    )
  }

  @Test fun `a new disable before connection does not reuse an acknowledged heartbeat`() {
    setEnabled(true)
    AccessibilityAccess.connected(context)
    ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
    setEnabled(false)
    val previous = requireNotNull(AccessibilityAccess.read(context).offPeriod)
    setEnabled(true)
    AccessibilityAccess.acknowledge(context, previous.through)
    assertNull(AccessibilityAccess.read(context).offPeriod)

    ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
    setEnabled(false)
    val disabledAt = ShadowSystem.currentTimeMillis()
    assertEquals(
      AccessibilityAccess.Status(false, AccessibilityAccess.OffPeriod(disabledAt, disabledAt)),
      AccessibilityAccess.read(context),
    )
  }

  @Test fun `a reboot after a force stop reports the gap from the last heartbeat`() {
    setEnabled(true)
    AccessibilityAccess.connected(context)
    val lastHeartbeat = ShadowSystem.currentTimeMillis()
    ShadowSystemClock.advanceBy(Duration.ofHours(1))
    Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 8)
    AccessibilityAccess.connected(context)
    assertEquals(
      AccessibilityAccess.Status(true, AccessibilityAccess.OffPeriod(lastHeartbeat, ShadowSystem.currentTimeMillis())),
      AccessibilityAccess.read(context),
    )
  }

  @Test fun `restoring access clears a heartbeat left by an older disable record`() {
    setEnabled(true)
    AccessibilityAccess.connected(context)
    val previousStart = ShadowSystem.currentTimeMillis()
    ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
    context.getSharedPreferences("zen_guard_accessibility_access", Context.MODE_PRIVATE)
      .edit()
      .putBoolean("enabled", false)
      .putLong("disabled_at", previousStart)
      .putLong("disabled_through", ShadowSystem.currentTimeMillis())
      .commit()
    val previous = requireNotNull(AccessibilityAccess.read(context).offPeriod)
    AccessibilityAccess.acknowledge(context, previous.through)
    assertNull(AccessibilityAccess.read(context).offPeriod)

    ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
    setEnabled(false)
    val disabledAt = ShadowSystem.currentTimeMillis()
    assertEquals(
      AccessibilityAccess.Status(false, AccessibilityAccess.OffPeriod(disabledAt, disabledAt)),
      AccessibilityAccess.read(context),
    )
  }

  @Test fun `a reconnect on a new boot ignores the previous clean connection`() {
    val original = Robolectric.buildService(ZenGuardAccessibilityService::class.java).create()
    try {
      setEnabled(true)
      connect(original.get())
      context.sendBroadcast(Intent(Intent.ACTION_SHUTDOWN))
      shadowOf(Looper.getMainLooper()).idle()
    } finally {
      original.destroy()
    }
    ShadowSystemClock.advanceBy(Duration.ofHours(1))
    Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 8)
    AccessibilityAccess.connected(context)
    assertEquals(AccessibilityAccess.Status(true, null), AccessibilityAccess.read(context))
  }

  @Test fun `a shutdown broadcast without a reboot does not hide a later force stop and reboot`() {
    setEnabled(true)
    AccessibilityAccess.connected(context)
    AccessibilityAccess.recordCleanShutdown(context)
    AccessibilityAccess.disconnected(context)
    ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
    AccessibilityAccess.connected(context)
    AccessibilityAccess.read(context).offPeriod?.let { AccessibilityAccess.acknowledge(context, it.through) }
    val lastHeartbeat = ShadowSystem.currentTimeMillis()
    ShadowSystemClock.advanceBy(Duration.ofHours(1))
    Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 8)
    AccessibilityAccess.connected(context)
    assertEquals(
      AccessibilityAccess.Status(true, AccessibilityAccess.OffPeriod(lastHeartbeat, ShadowSystem.currentTimeMillis())),
      AccessibilityAccess.read(context),
    )
  }

  @Test fun `a reconnect just before power off keeps the clean shutdown`() {
    setEnabled(true)
    AccessibilityAccess.connected(context)
    AccessibilityAccess.recordCleanShutdown(context)
    ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
    AccessibilityAccess.connected(context)
    ShadowSystemClock.advanceBy(Duration.ofHours(8))
    Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 8)
    AccessibilityAccess.connected(context)
    assertEquals(AccessibilityAccess.Status(true, null), AccessibilityAccess.read(context))
  }

  @Test fun `a new boot without a previous connection has no notice`() {
    setEnabled(true)
    AccessibilityAccess.read(context)
    ShadowSystemClock.advanceBy(Duration.ofHours(1))
    Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 8)
    AccessibilityAccess.connected(context)
    assertEquals(AccessibilityAccess.Status(true, null), AccessibilityAccess.read(context))
  }

  @Test fun `a quick unclean reconnect leaves no notice after deep sleep`() {
    setEnabled(true)
    AccessibilityAccess.connected(context)
    ShadowSystemClock.simulateDeepSleep(Duration.ofHours(1))
    ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
    AccessibilityAccess.connected(context)
    assertEquals(AccessibilityAccess.Status(true, null), AccessibilityAccess.read(context))
  }

  @Test fun `dismissing while access is off retains the period after access returns`() {
    setEnabled(true)
    AccessibilityAccess.read(context)
    setEnabled(false)
    val open = requireNotNull(AccessibilityAccess.read(context).offPeriod)
    AccessibilityAccess.acknowledge(context, open.through)
    assertEquals(open, AccessibilityAccess.read(context).offPeriod)
    ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
    setEnabled(true)
    val closed = AccessibilityAccess.OffPeriod(open.since, ShadowSystem.currentTimeMillis())
    assertEquals(AccessibilityAccess.Status(true, closed), AccessibilityAccess.read(context))
    AccessibilityAccess.acknowledge(context, closed.through)
    assertNull(AccessibilityAccess.read(context).offPeriod)
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
