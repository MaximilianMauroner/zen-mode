package com.maxmauroner.zenguard

import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
import android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class XServiceBehaviorTest {
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
    shadowOf(context.packageManager).addResolveInfoForIntent(Intent(Settings.ACTION_SETTINGS), ResolveInfo().apply {
      isDefault = true
      activityInfo = ActivityInfo().apply {
        packageName = SETTINGS
        name = "$SETTINGS.Settings"
        applicationInfo = ApplicationInfo().apply {
          packageName = SETTINGS
          flags = ApplicationInfo.FLAG_SYSTEM
        }
      }
    })
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

  @Test fun `the ticker exhausts a still Home feed and Leave opens Android Home`() {
    showRoot(node(X, "scaffold_home_tabbed"))
    deliver(X)
    assertEquals(ZenGuardPreferences.X_HOME_SIGNAL, ZenGuardPreferences(context).xSignalMask)

    advanceSeconds(59)

    assertEquals(59_000L, storedHome().usedMs)
    assertEquals(HomeFeedUsageState.UNKNOWN, storedHome().usageState)
    assertTrue(overlayViews().isEmpty())
    assertEquals(0L, homeStops())

    advanceSeconds(1)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))

    assertEquals(60_000L, storedHome().usedMs)
    assertEquals(HomeFeedUsageState.PAUSED, storedHome().usageState)
    assertEquals(HomeFeedLockoutState.ACTIVE, storedHome().lockoutState)
    assertEquals(1L, homeStops())
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())

    assertTrue(leaveButton().performClick())

    assertTrue(overlayViews().isEmpty())
    assertEquals(listOf(GLOBAL_ACTION_HOME), shadowOf(controller.get()).globalActionsPerformed)
  }

  @Test fun `missing and unknown X roots pause Home and do not charge their gaps`() {
    val home = node(X, "scaffold_home_tabbed")
    showRoot(home)
    deliver(X)
    advanceSeconds(10)

    showRoot(null)
    deliver(X)
    advanceSeconds(60)

    assertEquals(10_000L, storedHome().usedMs)
    assertEquals(HomeFeedUsageState.UNKNOWN, storedHome().usageState)
    assertTrue(overlayViews().isEmpty())

    showRoot(home)
    deliver(X)
    advanceSeconds(5)
    assertEquals(15_000L, storedHome().usedMs)
    assertEquals(HomeFeedUsageState.UNKNOWN, storedHome().usageState)

    showRoot(node(X))
    deliver(X)
    advanceSeconds(60)

    assertEquals(15_000L, storedHome().usedMs)
    assertEquals(HomeFeedUsageState.UNKNOWN, storedHome().usageState)
    assertTrue(overlayViews().isEmpty())
    assertEquals(0L, homeStops())
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())

    showRoot(home)
    deliver(X)
    assertEquals(15_000L, storedHome().usedMs)
    advanceSeconds(44)
    assertTrue(overlayViews().isEmpty())
    advanceSeconds(1)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
    assertEquals(60_000L, storedHome().usedMs)
    assertEquals(1L, homeStops())
    assertEquals(1, overlayViews().size)
  }

  @Test fun `an X event cannot enforce a Home signature in a foreign root`() {
    showRoot(node(X, "scaffold_home_tabbed"))
    deliver(X)
    advanceSeconds(10)

    showRoot(node(OTHER, "scaffold_home_tabbed"))
    deliver(X)
    advanceSeconds(60)

    assertEquals(10_000L, storedHome().usedMs)
    assertEquals(HomeFeedUsageState.PAUSED, storedHome().usageState)
    assertEquals(HomeFeedLockoutState.NONE, storedHome().lockoutState)
    assertTrue(overlayViews().isEmpty())
    assertEquals(0L, homeStops())
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())
  }

  @Test fun `self and keyboard window events keep the blocker while the X window remains`() {
    val home = node(X, "scaffold_home_tabbed")
    showRoot(home)
    deliver(X)
    advanceSeconds(60)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
    val overlay = overlayViews().single()
    val lockout = storedHome().blockedUntilElapsedMs

    showRoot(node(context.packageName), listOf(home))
    deliver(context.packageName, TYPE_WINDOW_STATE_CHANGED)
    deliver(context.packageName)
    advanceSeconds(1)

    assertSame(overlay, overlayViews().single())

    showRoot(node(KEYBOARD), listOf(home))
    deliver(KEYBOARD, TYPE_WINDOW_STATE_CHANGED)
    deliver(KEYBOARD)
    advanceSeconds(1)

    assertSame(overlay, overlayViews().single())
    assertEquals(60_000L, storedHome().usedMs)
    assertEquals(HomeFeedUsageState.PAUSED, storedHome().usageState)
    assertEquals(lockout, storedHome().blockedUntilElapsedMs)
    assertEquals(1L, homeStops())
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())
  }

  @Test fun `confirmed departure pauses Home and removes its blocker without clearing the lockout`() {
    val home = node(X, "scaffold_home_tabbed")
    showRoot(home)
    deliver(X)
    advanceSeconds(10)

    showRoot(node(OTHER))
    deliver(OTHER, TYPE_WINDOW_STATE_CHANGED)
    advanceSeconds(60)

    assertEquals(10_000L, storedHome().usedMs)
    assertEquals(HomeFeedUsageState.PAUSED, storedHome().usageState)
    assertTrue(overlayViews().isEmpty())

    showRoot(home)
    deliver(X)
    advanceSeconds(50)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
    assertEquals(1, overlayViews().size)
    val lockout = storedHome().blockedUntilElapsedMs

    showRoot(node(OTHER))
    deliver(OTHER, TYPE_WINDOW_STATE_CHANGED)

    assertTrue(overlayViews().isEmpty())
    assertEquals(60_000L, storedHome().usedMs)
    assertEquals(HomeFeedUsageState.PAUSED, storedHome().usageState)
    assertEquals(HomeFeedLockoutState.ACTIVE, storedHome().lockoutState)
    assertEquals(lockout, storedHome().blockedUntilElapsedMs)
    advanceSeconds(60)
    assertTrue(overlayViews().isEmpty())
    assertEquals(60_000L, storedHome().usedMs)
    assertEquals(1L, homeStops())
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())
  }

  @Test fun `live Settings beneath the overlay stops the next tick and a late Leave tap`() {
    val home = node(X, "scaffold_home_tabbed")
    showRoot(home)
    deliver(X)
    advanceSeconds(60)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
    val leave = leaveButton()

    showRoot(node(context.packageName), listOf(node(SETTINGS), home))
    advanceSeconds(1)

    assertTrue(overlayViews().isEmpty())
    assertEquals(HomeFeedUsageState.PAUSED, storedHome().usageState)
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())

    assertTrue(leave.performClick())
    advanceSeconds(2)

    assertTrue(overlayViews().isEmpty())
    assertEquals(1L, homeStops())
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())
  }

  private fun node(packageName: String, viewId: String = ""): AccessibilityNodeInfo =
    AccessibilityNodeInfo.obtain().apply {
      this.packageName = packageName
      viewIdResourceName = viewId
      isVisibleToUser = true
    }

  private fun showRoot(
    root: AccessibilityNodeInfo?,
    applicationRoots: List<AccessibilityNodeInfo> = listOfNotNull(root),
  ) {
    val windows = applicationRoots.map { applicationRoot ->
      AccessibilityWindowInfo().also {
        shadowOf(it).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        shadowOf(it).setRoot(applicationRoot)
      }
    }
    shadowOf(controller.get()).apply {
      setRootInActiveWindow(root)
      setWindows(windows)
    }
  }

  private fun deliver(packageName: String, type: Int = TYPE_WINDOW_CONTENT_CHANGED) {
    controller.get().onAccessibilityEvent(AccessibilityEvent.obtain(type).apply {
      this.packageName = packageName
    })
  }

  private fun storedHome(): HomeFeedStatus = HomeFeedStatusStore(context).x(SystemClock.elapsedRealtime())

  private fun homeStops(): Long =
    EnforcementStatsStore(context).snapshot().counts[EnforcementReason.X_HOME.key] ?: 0L

  private fun overlayViews(): List<View> =
    Shadow.extract<ShadowWindowManagerImpl>(controller.get().getSystemService(WindowManager::class.java)).views

  private fun leaveButton(): Button {
    val matches = ArrayList<View>()
    overlayViews().forEach {
      it.findViewsWithText(matches, context.getString(R.string.zen_guard_leave_x), View.FIND_VIEWS_WITH_TEXT)
    }
    return matches.filterIsInstance<Button>().single()
  }

  private fun advanceSeconds(seconds: Long) {
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
  }

  private companion object {
    const val X = "com.twitter.android"
    const val OTHER = "com.example.notes"
    const val KEYBOARD = "com.google.android.inputmethod.latin"
    const val SETTINGS = "com.android.settings"
  }
}
