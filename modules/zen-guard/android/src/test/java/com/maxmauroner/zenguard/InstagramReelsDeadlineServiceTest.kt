package com.maxmauroner.zenguard

import android.content.Context
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import java.time.Duration
import org.junit.After
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** An untouched Reel emits no events; the service must still end the Continue window on time. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class InstagramReelsDeadlineServiceTest {
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

  @Test fun `untouched Reel is paused when the Continue window ends`() {
    val policy = grantOneMinuteWindow()

    advance(Duration.ofSeconds(59))
    assertNull(policy.debugState(SystemClock.elapsedRealtime()).blockerReason)

    advance(Duration.ofSeconds(2))
    assertEquals(
      InstagramBlockReason.REELS_WINDOW_EXPIRED,
      policy.debugState(SystemClock.elapsedRealtime()).blockerReason,
    )
  }

  @Test fun `deadline check does not block when Instagram is no longer in front`() {
    val policy = grantOneMinuteWindow()
    showRoot("com.whatsapp", "com.whatsapp:id/conversations_row")

    advance(Duration.ofSeconds(61))
    assertNull(policy.debugState(SystemClock.elapsedRealtime()).blockerReason)
  }

  /** Opens a Reel from a DM thread, swipes to the pause, waits, and taps Continue. */
  private fun grantOneMinuteWindow(): InstagramGuardStateMachine {
    val service = controller.get()
    val policy = service.privateField<InstagramGuardStateMachine>("instagramStateMachine")
    val settings = ZenGuardPreferences(context).instagramSettings()
    showRoot("com.instagram.android", "com.instagram.android:id/clips_viewer_view_pager")
    val start = SystemClock.elapsedRealtime()
    policy.next(InstagramGuardInput(InstagramSurface.DIRECT_MESSAGES, start, dmThreadVisible = true), settings)
    policy.next(InstagramGuardInput(InstagramSurface.REELS_VIEWER, start, reelPagerVisible = true), settings)
    advance(Duration.ofSeconds(5))
    val swipe = policy.next(
      InstagramGuardInput(
        InstagramSurface.REELS_VIEWER,
        SystemClock.elapsedRealtime(),
        reelPagerVisible = true,
        reelPagerScrolled = true,
      ),
      settings,
    )
    assertEquals(InstagramBlockReason.REELS_SWIPE, (swipe as InstagramGuardAction.ShowBlocker).reason)
    advance(Duration.ofSeconds(30))

    val onContinue = service.privateField<InstagramBlockerOverlay>("instagramOverlay")
      .privateField<() -> Boolean>("onContinue")
    assertTrue(onContinue())
    assertEquals(60_000L, policy.debugState(SystemClock.elapsedRealtime()).reelsWindowRemainingMs)
    return policy
  }

  private fun showRoot(packageName: String, viewId: String) {
    val root = AccessibilityNodeInfo.obtain().apply {
      this.packageName = packageName
      viewIdResourceName = viewId
      isVisibleToUser = true
    }
    shadowOf(controller.get()).setRootInActiveWindow(root)
  }

  private fun advance(duration: Duration) {
    shadowOf(Looper.getMainLooper()).idleFor(duration)
  }

  @Suppress("UNCHECKED_CAST")
  private fun <T> Any.privateField(name: String): T =
    javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T
}
