package com.maxmauroner.zenguard

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock

/** Exercise the real service event, publication, destruction, and reconnect paths. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class InstagramServiceRestartTest {
  private lateinit var context: Context
  private val controllers = mutableListOf<ServiceController<ZenGuardAccessibilityService>>()

  @Before fun setUp() {
    context = RuntimeEnvironment.getApplication()
    HomeFeedStatusFailureRegistry.clearForTests()
    Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 7)
    val preferences = ZenGuardPreferences(context)
    preferences.acceptCurrentConsent()
    preferences.protectionEnabled = true
    preferences.instagramObservationMode = false
    shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
  }

  @After fun tearDown() {
    controllers.forEach { it.destroy() }
    controllers.clear()
    HomeFeedStatusFailureRegistry.clearForTests()
  }

  @Test fun `ordinary Home events checkpoint usage before abrupt reconnect`() {
    val first = connect()
    observeHome(first.get())
    ShadowSystemClock.advanceBy(Duration.ofSeconds(120))
    observeHome(first.get())
    assertEquals(120_000L, storedUsage())

    // No onDestroy or background callback: the new instance must read the event checkpoint.
    ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
    val reconnected = connect()
    assertEquals(120_000L, storedUsage())
    observeHome(reconnected.get())
    assertEquals(120_000L, storedUsage())
  }

  @Test fun `active service destruction banks partial usage before reconnect`() {
    val first = connect()
    observeHome(first.get())
    ShadowSystemClock.advanceBy(Duration.ofSeconds(120))
    observeHome(first.get())
    ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
    first.destroy()
    controllers.remove(first)
    assertEquals(150_000L, storedUsage())

    ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
    connect()
    assertEquals(150_000L, storedUsage())
  }

  @Test fun `disabled protection still clears partial usage at destruction`() {
    val first = connect()
    observeHome(first.get())
    ShadowSystemClock.advanceBy(Duration.ofSeconds(120))
    observeHome(first.get())
    ZenGuardPreferences(context).protectionEnabled = false
    first.destroy()
    controllers.remove(first)
    assertEquals(0L, storedUsage())
  }

  private fun connect(): ServiceController<ZenGuardAccessibilityService> {
    val controller = Robolectric.buildService(ZenGuardAccessibilityService::class.java).create()
    controllers.add(controller)
    ZenGuardAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply {
      isAccessible = true
    }.invoke(controller.get())
    return controller
  }

  private fun observeHome(service: ZenGuardAccessibilityService) {
    val root = AccessibilityNodeInfo.obtain().apply {
      packageName = "com.instagram.android"
      viewIdResourceName = "com.instagram.android:id/feed_recycler_view"
      isVisibleToUser = true
    }
    shadowOf(service).setRootInActiveWindow(root)
    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
      packageName = "com.instagram.android"
    }
    service.onAccessibilityEvent(event)
  }

  private fun storedUsage(): Long = HomeFeedStatusStore(context).instagram(SystemClock.elapsedRealtime()).usedMs
}
