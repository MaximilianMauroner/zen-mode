package com.maxmauroner.zenguard

import android.content.Context
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
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

/** Continue ends a block, so the next blocker on screen is a new stop in today's tally. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class InstagramContinueTallyTest {
  private lateinit var context: Context
  private lateinit var controller: ServiceController<ZenGuardAccessibilityService>

  @Before fun setUp() {
    context = RuntimeEnvironment.getApplication()
    HomeFeedStatusFailureRegistry.clearForTests()
    Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 7)
    ZenGuardPreferences(context).apply {
      acceptCurrentConsent()
      protectionEnabled = true
      instagramObservationMode = false
      instagramWaitSeconds = 30
      instagramReelsMinutes = 1
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

  @Test fun `a Reels pause after Continue counts as a new stop`() {
    swipeReelFromDm()
    observeReel()
    assertEquals(1, stoppedToday())

    advance(Duration.ofSeconds(31))
    tapContinue()
    assertTrue(overlayViews().isEmpty())
    // An untouched Reel sends no event during the window, so no other path clears the old block.
    advance(Duration.ofSeconds(61))
    observeReel()
    assertEquals(2, stoppedToday())

    advance(Duration.ofSeconds(31))
    tapContinue()
    advance(Duration.ofSeconds(61))
    observeReel()
    assertEquals(3, stoppedToday())
    assertEquals(2, DailyTally(context).counts(System.currentTimeMillis()).continued)
    assertEquals(3L, EnforcementStatsStore(context).snapshot().counts[EnforcementReason.INSTAGRAM_REELS.key])
  }

  private fun swipeReelFromDm() {
    val policy = controller.get().privateField<InstagramGuardStateMachine>("instagramStateMachine")
    val settings = ZenGuardPreferences(context).instagramSettings()
    val start = SystemClock.elapsedRealtime()
    policy.next(InstagramGuardInput(InstagramSurface.DIRECT_MESSAGES, start, dmThreadVisible = true), settings)
    policy.next(InstagramGuardInput(InstagramSurface.REELS_VIEWER, start, reelPagerVisible = true), settings)
    advance(Duration.ofSeconds(5))
    policy.next(
      InstagramGuardInput(
        InstagramSurface.REELS_VIEWER,
        SystemClock.elapsedRealtime(),
        reelPagerVisible = true,
        reelPagerScrolled = true,
      ),
      settings,
    )
  }

  private fun tapContinue() {
    val label = context.resources.getQuantityString(R.plurals.zen_guard_continue_doomscrolling, 1, 1)
    val matches = ArrayList<View>()
    overlayViews().forEach { it.findViewsWithText(matches, label, View.FIND_VIEWS_WITH_TEXT) }
    val button = matches.filterIsInstance<Button>().single()
    assertTrue(button.isEnabled)
    button.performClick()
  }

  private fun observeReel() {
    val service = controller.get()
    val root = AccessibilityNodeInfo.obtain().apply {
      packageName = "com.instagram.android"
      viewIdResourceName = "com.instagram.android:id/clips_viewer_view_pager"
      isVisibleToUser = true
    }
    shadowOf(service).setRootInActiveWindow(root)
    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
      packageName = "com.instagram.android"
    }
    service.onAccessibilityEvent(event)
  }

  private fun overlayViews(): List<View> =
    Shadow.extract<ShadowWindowManagerImpl>(controller.get().getSystemService(WindowManager::class.java)).views

  private fun stoppedToday(): Int = DailyTally(context).counts(System.currentTimeMillis()).stopped

  private fun advance(duration: Duration) {
    shadowOf(Looper.getMainLooper()).idleFor(duration)
  }

  @Suppress("UNCHECKED_CAST")
  private fun <T> Any.privateField(name: String): T =
    javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T
}
