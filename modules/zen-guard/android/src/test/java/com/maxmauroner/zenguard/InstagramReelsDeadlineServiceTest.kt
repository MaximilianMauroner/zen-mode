package com.maxmauroner.zenguard

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
  private var destroyed = false

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
    if (!destroyed) controller.destroy()
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
    assertTrue(overlay().isShowing)
    assertFalse(hasPendingDeadline())
    assertEquals(0L, policy.reelsWindowRemainingMs(SystemClock.elapsedRealtime()))

    advance(Duration.ofSeconds(5))
    assertFalse(hasPendingDeadline())
    assertEquals(1L, expiryCount())
  }

  @Test fun `an early Reels deadline still pauses at the actual Continue expiry`() {
    val policy = grantOneMinuteWindow()
    val remainingMs = requireNotNull(policy.reelsWindowRemainingMs(SystemClock.elapsedRealtime()))
    advance(Duration.ofMillis(remainingMs - 1L))
    assertEquals(1L, policy.reelsWindowRemainingMs(SystemClock.elapsedRealtime()))

    val deadline = reelsDeadline()
    val post = deadline.privateField<Runnable>("current")
    deadline.privateField<android.os.Handler>("handler").removeCallbacks(post)
    post.run()

    assertNull(policy.debugState(SystemClock.elapsedRealtime()).blockerReason)
    assertFalse(overlay().isShowing)
    assertTrue(deadline.isPending)
    assertEquals(0L, expiryCount())

    advance(Duration.ofMillis(20L))

    assertEquals(InstagramBlockReason.REELS_WINDOW_EXPIRED, policy.debugState(SystemClock.elapsedRealtime()).blockerReason)
    assertTrue(overlay().isShowing)
    assertFalse(deadline.isPending)
    assertEquals(1L, expiryCount())
  }

  @Test fun `expired window retries an absent root then pauses a restored viewer without an event`() {
    assertRecoveryWithoutEvent { shadowOf(controller.get()).setRootInActiveWindow(null) }
  }

  @Test fun `expired window retries a foreign root then pauses a restored viewer without an event`() {
    assertRecoveryWithoutEvent { showRoot("com.android.systemui", "com.android.systemui:id/transient") }
  }

  @Test fun `expired window retries unknown Instagram then pauses a restored viewer without an event`() {
    assertRecoveryWithoutEvent { showRoot("com.instagram.android", "com.instagram.android:id/loading") }
  }

  @Test fun `expired window retries a Reel child without a pager then pauses the restored viewer without an event`() {
    assertRecoveryWithoutEvent { showRoot("com.instagram.android", "com.instagram.android:id/clips_viewer_metadata") }
  }

  @Test fun `an observed Messages return clears the expired window and stops retries`() {
    val policy = grantOneMinuteWindow()
    shadowOf(controller.get()).setRootInActiveWindow(null)
    advance(Duration.ofSeconds(61))
    assertTrue(hasPendingDeadline())
    showRoot("com.instagram.android", "com.instagram.android:id/direct_thread")

    advance(Duration.ofSeconds(2))

    assertNull(policy.reelsWindowRemainingMs(SystemClock.elapsedRealtime()))
    assertNull(policy.debugState(SystemClock.elapsedRealtime()).blockerReason)
    assertFalse(hasPendingDeadline())
    assertFalse(overlay().isShowing)
    showRoot("com.instagram.android", "com.instagram.android:id/clips_viewer_view_pager")
    advance(Duration.ofSeconds(5))
    assertNull(policy.debugState(SystemClock.elapsedRealtime()).blockerReason)
  }

  @Test fun `a viewer event that attaches the pause cancels the outstanding retry`() {
    val policy = grantOneMinuteWindow()
    shadowOf(controller.get()).setRootInActiveWindow(null)
    advance(Duration.ofSeconds(61))
    assertTrue(hasPendingDeadline())
    showRoot("com.instagram.android", "com.instagram.android:id/clips_viewer_view_pager")

    windowEvent("com.instagram.android")
    advance(Duration.ofMillis(20))

    assertTrue(overlay().isShowing)
    assertFalse(hasPendingDeadline())
    assertEquals(0L, policy.reelsWindowRemainingMs(SystemClock.elapsedRealtime()))
    advance(Duration.ofSeconds(5))
    assertEquals(1L, expiryCount())
    assertFalse(hasPendingDeadline())
  }

  @Test fun `deadline check does not block when Instagram is no longer in front`() {
    val policy = grantOneMinuteWindow()
    showRoot("com.whatsapp", "com.whatsapp:id/conversations_row")

    advance(Duration.ofSeconds(61))
    assertNull(policy.debugState(SystemClock.elapsedRealtime()).blockerReason)
    assertFalse(overlay().isShowing)
  }

  @Test fun `a confirmed app exit clears an outstanding expired-window retry`() {
    val policy = grantOneMinuteWindow()
    shadowOf(controller.get()).setRootInActiveWindow(null)
    advance(Duration.ofSeconds(61))
    assertTrue(hasPendingDeadline())
    showRoot("com.whatsapp", "com.whatsapp:id/conversations_row")

    windowEvent("com.whatsapp")

    assertNull(policy.reelsWindowRemainingMs(SystemClock.elapsedRealtime()))
    assertFalse(hasPendingDeadline())
    showRoot("com.instagram.android", "com.instagram.android:id/clips_viewer_view_pager")
    advance(Duration.ofSeconds(5))
    assertNull(policy.debugState(SystemClock.elapsedRealtime()).blockerReason)
    assertFalse(overlay().isShowing)
  }

  @Test fun `Settings cancels an outstanding retry before its accessibility event arrives`() {
    val policy = grantOneMinuteWindow()
    shadowOf(controller.get()).setRootInActiveWindow(null)
    advance(Duration.ofSeconds(61))
    assertTrue(hasPendingDeadline())
    controller.get().javaClass.getDeclaredField("appRuleSafety").apply { isAccessible = true }
      .set(controller.get(), AppRuleSafety.fromPackages(context.packageName, setOf("com.android.settings")))
    showRoot("com.android.settings", "com.android.settings:id/content")

    advance(Duration.ofSeconds(2))

    assertNull(policy.reelsWindowRemainingMs(SystemClock.elapsedRealtime()))
    assertNull(policy.debugState(SystemClock.elapsedRealtime()).blockerReason)
    assertFalse(hasPendingDeadline())
    assertFalse(overlay().isShowing)
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())
  }

  @Test fun `deadline check stays silent after the screen turned off before it`() {
    val policy = grantOneMinuteWindow()
    advance(Duration.ofSeconds(20))
    val power = shadowOf(context.getSystemService(PowerManager::class.java))
    power.setIsInteractive(false)
    context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
    shadowOf(Looper.getMainLooper()).idle()
    assertNull(policy.debugState(SystemClock.elapsedRealtime()).reelsWindowRemainingMs)
    assertFalse(hasPendingDeadline())
    power.setIsInteractive(true)

    advance(Duration.ofSeconds(45))
    assertNull(policy.debugState(SystemClock.elapsedRealtime()).blockerReason)
    assertFalse(overlay().isShowing)
  }

  @Test fun `screen off cancels an outstanding expired-window retry`() {
    val policy = grantOneMinuteWindow()
    shadowOf(controller.get()).setRootInActiveWindow(null)
    advance(Duration.ofSeconds(61))
    assertTrue(hasPendingDeadline())
    val power = shadowOf(context.getSystemService(PowerManager::class.java))
    power.setIsInteractive(false)

    context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
    shadowOf(Looper.getMainLooper()).idle()

    assertNull(policy.reelsWindowRemainingMs(SystemClock.elapsedRealtime()))
    assertFalse(hasPendingDeadline())
    power.setIsInteractive(true)
    showRoot("com.instagram.android", "com.instagram.android:id/clips_viewer_view_pager")
    advance(Duration.ofSeconds(5))
    assertNull(policy.debugState(SystemClock.elapsedRealtime()).blockerReason)
    assertFalse(overlay().isShowing)
  }

  @Test fun `deadline check stays silent once observation mode is on`() {
    val policy = grantOneMinuteWindow()
    ZenGuardPreferences(context).instagramObservationMode = true

    advance(Duration.ofSeconds(61))
    assertNull(policy.debugState(SystemClock.elapsedRealtime()).blockerReason)
    assertFalse(hasPendingDeadline())
    assertFalse(overlay().isShowing)
  }

  @Test fun `observation mode stops an outstanding expired-window retry`() {
    val policy = grantOneMinuteWindow()
    shadowOf(controller.get()).setRootInActiveWindow(null)
    advance(Duration.ofSeconds(61))
    assertTrue(hasPendingDeadline())
    ZenGuardPreferences(context).instagramObservationMode = true
    showRoot("com.instagram.android", "com.instagram.android:id/clips_viewer_view_pager")

    advance(Duration.ofSeconds(2))

    assertNull(policy.debugState(SystemClock.elapsedRealtime()).blockerReason)
    assertFalse(hasPendingDeadline())
    assertFalse(overlay().isShowing)
  }

  @Test fun `disabled protection clears an outstanding expired-window retry`() {
    val policy = grantOneMinuteWindow()
    shadowOf(controller.get()).setRootInActiveWindow(null)
    advance(Duration.ofSeconds(61))
    assertTrue(hasPendingDeadline())
    ZenGuardPreferences(context).protectionEnabled = false
    showRoot("com.instagram.android", "com.instagram.android:id/clips_viewer_view_pager")

    advance(Duration.ofSeconds(2))

    assertNull(policy.reelsWindowRemainingMs(SystemClock.elapsedRealtime()))
    assertNull(policy.debugState(SystemClock.elapsedRealtime()).blockerReason)
    assertFalse(hasPendingDeadline())
    assertFalse(overlay().isShowing)
  }

  @Test fun `service interruption cancels an outstanding expired-window retry`() {
    val policy = grantOneMinuteWindow()
    shadowOf(controller.get()).setRootInActiveWindow(null)
    advance(Duration.ofSeconds(61))
    assertTrue(hasPendingDeadline())

    controller.get().onInterrupt()

    assertNull(policy.reelsWindowRemainingMs(SystemClock.elapsedRealtime()))
    assertFalse(hasPendingDeadline())
    showRoot("com.instagram.android", "com.instagram.android:id/clips_viewer_view_pager")
    advance(Duration.ofSeconds(5))
    assertNull(policy.debugState(SystemClock.elapsedRealtime()).blockerReason)
  }

  @Test fun `service teardown cancels an outstanding expired-window retry`() {
    val policy = grantOneMinuteWindow()
    shadowOf(controller.get()).setRootInActiveWindow(null)
    advance(Duration.ofSeconds(61))
    assertTrue(hasPendingDeadline())

    controller.destroy()
    destroyed = true

    assertNull(policy.reelsWindowRemainingMs(SystemClock.elapsedRealtime()))
    assertFalse(hasPendingDeadline())
    showRoot("com.instagram.android", "com.instagram.android:id/clips_viewer_view_pager")
    advance(Duration.ofSeconds(5))
    assertNull(policy.debugState(SystemClock.elapsedRealtime()).blockerReason)
    assertFalse(overlay().isShowing)
  }

  private fun assertRecoveryWithoutEvent(showTransient: () -> Unit) {
    val policy = grantOneMinuteWindow()
    showTransient()
    advance(Duration.ofSeconds(61))
    assertEquals(0L, policy.reelsWindowRemainingMs(SystemClock.elapsedRealtime()))
    assertNull(policy.debugState(SystemClock.elapsedRealtime()).blockerReason)
    assertFalse(overlay().isShowing)
    assertTrue(hasPendingDeadline())

    showRoot("com.instagram.android", "com.instagram.android:id/clips_viewer_view_pager")
    advance(Duration.ofSeconds(2))

    assertEquals(InstagramBlockReason.REELS_WINDOW_EXPIRED, policy.debugState(SystemClock.elapsedRealtime()).blockerReason)
    assertTrue(overlay().isShowing)
    assertFalse(hasPendingDeadline())
    assertEquals(0L, policy.reelsWindowRemainingMs(SystemClock.elapsedRealtime()))
    advance(Duration.ofSeconds(5))
    assertFalse(hasPendingDeadline())
    assertEquals(1L, expiryCount())
  }

  private fun grantOneMinuteWindow(): InstagramGuardStateMachine {
    val instagram = controller.get().privateField<InstagramOwner>("instagram")
    val policy = instagram.privateField<InstagramGuardStateMachine>("policy")
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

    val continueReels = instagram.privateField<InstagramIntervention>("intervention")
      .privateField<() -> Boolean>("continueReels")
    assertTrue(continueReels())
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

  private fun windowEvent(packageName: String) {
    controller.get().onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
      this.packageName = packageName
    })
  }

  private fun overlay(): InstagramIntervention = controller.get().privateField<InstagramOwner>("instagram").privateField("intervention")

  private fun hasPendingDeadline(): Boolean = reelsDeadline().isPending

  private fun reelsDeadline(): DeadlineTask {
    val instagram = controller.get().privateField<InstagramOwner>("instagram")
    return instagram.javaClass.getDeclaredMethod("getReelsDeadline").apply {
      isAccessible = true
    }.invoke(instagram) as DeadlineTask
  }

  private fun expiryCount(): Long =
    EnforcementStatsStore(context).snapshot().counts[EnforcementReason.INSTAGRAM_REELS.key] ?: 0L

  @Suppress("UNCHECKED_CAST")
  private fun <T> Any.privateField(name: String): T =
    javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T
}
