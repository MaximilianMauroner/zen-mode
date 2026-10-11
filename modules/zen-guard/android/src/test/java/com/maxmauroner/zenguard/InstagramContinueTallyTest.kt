package com.maxmauroner.zenguard

import android.content.Context
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
    openReelFromDmAndContinue()

    advance(Duration.ofSeconds(61))
    observeReel()
    assertEquals(1, stoppedToday())

    advance(Duration.ofSeconds(30))
    assertTrue(tapContinue())
    // An untouched Reel sends no event during the window, so no other path clears the old block.
    advance(Duration.ofSeconds(61))
    observeReel()
    assertEquals(2, stoppedToday())
  }

  /** Opens a Reel from a DM thread, swipes to the pause, waits, and taps Continue. */
  private fun openReelFromDmAndContinue() {
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
    advance(Duration.ofSeconds(30))
    assertTrue(tapContinue())
  }

  /** Does what the Continue button does: run the service callback, then hide the blocker. */
  private fun tapContinue(): Boolean {
    val overlay = controller.get().privateField<InstagramBlockerOverlay>("instagramOverlay")
    return overlay.privateField<() -> Boolean>("onContinue").invoke().also { if (it) overlay.hide() }
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

  private fun stoppedToday(): Int = DailyTally(context).counts(System.currentTimeMillis()).stopped

  private fun advance(duration: Duration) {
    shadowOf(Looper.getMainLooper()).idleFor(duration)
  }

  @Suppress("UNCHECKED_CAST")
  private fun <T> Any.privateField(name: String): T =
    javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T
}
