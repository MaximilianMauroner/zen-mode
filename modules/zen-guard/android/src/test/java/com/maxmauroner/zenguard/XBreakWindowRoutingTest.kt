package com.maxmauroner.zenguard

import android.content.Context
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

/** The Home break is the active window while it shows; X routes under it must still be read. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class XBreakWindowRoutingTest {
  private lateinit var context: Context
  private lateinit var controller: ServiceController<ZenGuardAccessibilityService>

  @Before fun setUp() {
    context = RuntimeEnvironment.getApplication()
    HomeFeedStatusFailureRegistry.clearForTests()
    Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 7)
    ZenGuardPreferences(context).apply {
      acceptCurrentConsent()
      protectionEnabled = true
      xObservationMode = false
      xHomeEnabled = true
      xHomeMinutes = 1
    }
    shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
    controller = Robolectric.buildService(ZenGuardAccessibilityService::class.java).create()
    ZenGuardAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply {
      isAccessible = true
    }.invoke(controller.get())
  }

  @After fun tearDown() {
    controller.destroy()
    HomeFeedStatusFailureRegistry.clearForTests()
  }

  @Test fun `confirmed Search under the Home break closes it and keeps the rest`() {
    startHomeBreak()

    showUnderBreak(appWindow(X_PACKAGE, "Search"))
    advance(Duration.ofSeconds(1))

    assertFalse(overlay().isShowing)
    val rest = xPolicy().homeRuntimeState(SystemClock.elapsedRealtime()).blockedUntilElapsedMs
    assertNotNull(rest)
  }

  @Test fun `Home under the break keeps it`() {
    startHomeBreak()

    showUnderBreak(appWindow(X_PACKAGE, "scaffold_home_tabbed"))
    advance(Duration.ofSeconds(1))

    assertTrue(overlay().isShowing)
  }

  @Test fun `X window below another app is not read through the break`() {
    startHomeBreak()

    showUnderBreak(appWindow("com.whatsapp", "com.whatsapp:id/conversations_row"), appWindow(X_PACKAGE, "Search"))
    advance(Duration.ofSeconds(1))

    assertTrue(overlay().isShowing)
  }

  /** Lets the one-second ticker count a still Home feed until the 1m allowance ends. */
  private fun startHomeBreak() {
    val home = node(X_PACKAGE, "scaffold_home_tabbed")
    shadowOf(controller.get()).apply {
      setRootInActiveWindow(home)
      setWindows(listOf(window(AccessibilityWindowInfo.TYPE_APPLICATION, home)))
    }
    advance(Duration.ofSeconds(65))
    assertTrue(overlay().isShowing)
  }

  /** Makes the break the active window, with [appWindows] below it in z-order. */
  private fun showUnderBreak(vararg appWindows: AccessibilityWindowInfo) {
    val breakRoot = node(context.packageName, "")
    shadowOf(controller.get()).apply {
      setRootInActiveWindow(breakRoot)
      setWindows(listOf(window(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, breakRoot)) + appWindows)
    }
  }

  private fun appWindow(packageName: String, viewId: String) =
    window(AccessibilityWindowInfo.TYPE_APPLICATION, node(packageName, viewId))

  private fun window(type: Int, root: AccessibilityNodeInfo) = AccessibilityWindowInfo().also {
    shadowOf(it).setType(type)
    shadowOf(it).setRoot(root)
  }

  private fun node(packageName: String, viewId: String) = AccessibilityNodeInfo.obtain().apply {
    this.packageName = packageName
    viewIdResourceName = viewId
    isVisibleToUser = true
  }

  private fun advance(duration: Duration) {
    shadowOf(Looper.getMainLooper()).idleFor(duration)
  }

  private fun overlay(): XBreakOverlay = controller.get().privateField("xOverlay")

  private fun xPolicy(): XGuardStateMachine = controller.get().privateField("xStateMachine")

  @Suppress("UNCHECKED_CAST")
  private fun <T> Any.privateField(name: String): T =
    javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

  private companion object {
    const val X_PACKAGE = "com.twitter.android"
  }
}
