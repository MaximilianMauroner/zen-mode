package com.maxmauroner.zenguard

import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
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
import android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class InstagramConnectionServiceTest {
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
      instagramExploreBlocked = true
      instagramWaitSeconds = 30
      instagramReelsMinutes = 1
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
    reconnect()
  }

  @After fun tearDown() {
    controller.destroy()
    HomeFeedStatusFailureRegistry.clearForTests()
  }

  @Test fun `another connection retains Messages retries and the original suppression deadline`() {
    val tree = beginMessagesNavigation()
    advance(Duration.ofMillis(100))

    reconnect()
    deliver()

    assertTrue(overlayViews().isEmpty())
    assertEquals(1, stoppedToday())
    assertTrue(shadowOf(tree.tab).performedActions.isEmpty())
    advance(Duration.ofMillis(150))
    assertEquals(listOf(ACTION_CLICK), shadowOf(tree.tab).performedActions)

    advance(Duration.ofMillis(2_749))
    deliver()
    assertTrue(overlayViews().isEmpty())
    assertEquals(1L, enforcementCount(EnforcementReason.INSTAGRAM_EXPLORE))

    advance(Duration.ofMillis(1))
    deliver()
    advance(Duration.ofMillis(20))
    assertTrue(overlayViews().single().isAttachedToWindow)
    assertEquals(2, stoppedToday())
    assertEquals(2L, enforcementCount(EnforcementReason.INSTAGRAM_EXPLORE))
    assertEquals(listOf(GLOBAL_ACTION_BACK), shadowOf(controller.get()).globalActionsPerformed)
  }

  @Test fun `Settings after another connection cancels the preconnection Messages retries`() {
    val tree = beginMessagesNavigation()
    advance(Duration.ofMillis(250))
    assertEquals(listOf(ACTION_CLICK), shadowOf(tree.tab).performedActions)
    reconnect()
    advance(Duration.ofMillis(250))
    assertEquals(listOf(ACTION_CLICK, ACTION_CLICK), shadowOf(tree.tab).performedActions)

    showRoot(node(SETTINGS, "content"))
    deliver(SETTINGS, TYPE_WINDOW_STATE_CHANGED)

    showRoot(tree.root)
    deliver()
    advance(Duration.ofMillis(20))
    assertTrue(overlayViews().single().isAttachedToWindow)
    assertEquals(2, stoppedToday())
    advance(Duration.ofSeconds(4))
    assertEquals(listOf(ACTION_CLICK, ACTION_CLICK), shadowOf(tree.tab).performedActions)
    assertEquals(listOf(GLOBAL_ACTION_BACK), shadowOf(controller.get()).globalActionsPerformed)
    assertEquals(2L, enforcementCount(EnforcementReason.INSTAGRAM_EXPLORE))
  }

  @Test fun `disable after another connection cancels Messages retries before protection resumes`() {
    val tree = beginMessagesNavigation()
    advance(Duration.ofMillis(250))
    assertEquals(listOf(ACTION_CLICK), shadowOf(tree.tab).performedActions)
    reconnect()
    advance(Duration.ofMillis(250))
    assertEquals(listOf(ACTION_CLICK, ACTION_CLICK), shadowOf(tree.tab).performedActions)

    ZenGuardPreferences(context).protectionEnabled = false
    deliver()
    ZenGuardPreferences(context).protectionEnabled = true
    deliver()
    advance(Duration.ofMillis(20))

    assertTrue(overlayViews().single().isAttachedToWindow)
    assertEquals(2, stoppedToday())
    advance(Duration.ofSeconds(4))
    assertEquals(listOf(ACTION_CLICK, ACTION_CLICK), shadowOf(tree.tab).performedActions)
    assertEquals(listOf(GLOBAL_ACTION_BACK), shadowOf(controller.get()).globalActionsPerformed)
    assertEquals(2L, enforcementCount(EnforcementReason.INSTAGRAM_EXPLORE))
  }

  @Test fun `the pending Reels deadline cancels after another connection restores away its grant`() {
    val policy = grantOneMinuteWindow()
    val deadline = reelsDeadline()
    assertTrue(deadline.isPending)
    assertTrue(overlayViews().isEmpty())
    assertEquals(0L, enforcementCount(EnforcementReason.INSTAGRAM_REELS))

    advance(Duration.ofSeconds(61))

    assertEquals(InstagramBlockReason.REELS_WINDOW_EXPIRED, policy.debugState(SystemClock.elapsedRealtime()).blockerReason)
    assertTrue(overlayViews().single().isAttachedToWindow)
    assertFalse(deadline.isPending)
    assertEquals(1, stoppedToday())
    assertEquals(1L, enforcementCount(EnforcementReason.INSTAGRAM_REELS))
    advance(Duration.ofSeconds(31))
    val label = context.resources.getQuantityString(R.plurals.zen_guard_continue_doomscrolling, 1, 1)
    val continueButton = button(label)
    assertTrue(continueButton.isEnabled)
    assertTrue(continueButton.performClick())
    assertTrue(overlayViews().isEmpty())
    assertEquals(2, DailyTally(context).counts(System.currentTimeMillis()).continued)
    assertEquals(60_000L, policy.reelsWindowRemainingMs(SystemClock.elapsedRealtime()))
    assertTrue(deadline.isPending)
    advance(Duration.ofSeconds(10))

    reconnect()

    assertNull(policy.reelsWindowRemainingMs(SystemClock.elapsedRealtime()))
    assertNull(policy.debugState(SystemClock.elapsedRealtime()).blockerReason)
    assertTrue(deadline.isPending)
    assertTrue(reelsDeadline().isPending)
    advance(Duration.ofSeconds(49))
    assertTrue(deadline.isPending)
    assertTrue(overlayViews().isEmpty())
    advance(Duration.ofSeconds(2))
    assertFalse(deadline.isPending)
    assertFalse(reelsDeadline().isPending)
    assertTrue(overlayViews().isEmpty())
    assertEquals(1, stoppedToday())
    assertEquals(1L, enforcementCount(EnforcementReason.INSTAGRAM_REELS))

    deliver()
    advance(Duration.ofMillis(20))

    assertEquals(InstagramBlockReason.REELS_ENTRY, policy.debugState(SystemClock.elapsedRealtime()).blockerReason)
    assertTrue(overlayViews().single().isAttachedToWindow)
    assertEquals(2, stoppedToday())
    assertEquals(2L, enforcementCount(EnforcementReason.INSTAGRAM_REELS))
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())
  }

  private data class MessagesTree(val root: AccessibilityNodeInfo, val tab: AccessibilityNodeInfo)

  private fun beginMessagesNavigation(): MessagesTree {
    val root = node(INSTAGRAM, "explore_grid")
    val tab = node(INSTAGRAM, "direct_tab").apply { isClickable = true }
    // Initialize the action log before Android clones the node so the clones share it.
    assertTrue(shadowOf(tab).performedActions.isEmpty())
    shadowOf(tab).setOnPerformActionListener { action, _ -> action == ACTION_CLICK }
    shadowOf(root).addChild(tab)
    showRoot(root)
    deliver()
    advance(Duration.ofMillis(20))
    assertTrue(overlayViews().single().isAttachedToWindow)
    assertEquals(1, stoppedToday())
    assertEquals(1L, enforcementCount(EnforcementReason.INSTAGRAM_EXPLORE))
    assertTrue(button(context.getString(R.string.zen_guard_open_messages)).performClick())
    assertTrue(overlayViews().isEmpty())
    assertEquals(listOf(GLOBAL_ACTION_BACK), shadowOf(controller.get()).globalActionsPerformed)
    return MessagesTree(root, tab)
  }

  private fun grantOneMinuteWindow(): InstagramGuardStateMachine {
    val policy = controller.get().privateField<InstagramOwner>("instagram").privateField<InstagramGuardStateMachine>("policy")
    val settings = ZenGuardPreferences(context).instagramSettings()
    showRoot(node(INSTAGRAM, "clips_viewer_view_pager"))
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
    val continueReels = controller.get().privateField<InstagramOwner>("instagram").privateField<InstagramIntervention>("intervention")
      .privateField<() -> Boolean>("continueReels")
    assertTrue(continueReels())
    assertEquals(60_000L, policy.reelsWindowRemainingMs(SystemClock.elapsedRealtime()))
    return policy
  }

  private fun reconnect() {
    ZenGuardAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply {
      isAccessible = true
    }.invoke(controller.get())
  }

  private fun node(packageName: String, viewId: String): AccessibilityNodeInfo =
    AccessibilityNodeInfo.obtain().apply {
      this.packageName = packageName
      viewIdResourceName = "$packageName:id/$viewId"
      isVisibleToUser = true
    }

  private fun showRoot(root: AccessibilityNodeInfo) {
    val window = AccessibilityWindowInfo().also {
      shadowOf(it).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
      shadowOf(it).setRoot(root)
    }
    shadowOf(controller.get()).apply {
      setRootInActiveWindow(root)
      setWindows(listOf(window))
    }
  }

  private fun deliver(packageName: String = INSTAGRAM, type: Int = TYPE_WINDOW_CONTENT_CHANGED) {
    controller.get().onAccessibilityEvent(AccessibilityEvent.obtain(type).apply {
      this.packageName = packageName
    })
  }

  private fun button(label: String): Button {
    val matches = ArrayList<View>()
    overlayViews().forEach { it.findViewsWithText(matches, label, View.FIND_VIEWS_WITH_TEXT) }
    return matches.filterIsInstance<Button>().single()
  }

  private fun overlayViews(): List<View> =
    Shadow.extract<ShadowWindowManagerImpl>(controller.get().getSystemService(WindowManager::class.java)).views

  private fun stoppedToday(): Int = DailyTally(context).counts(System.currentTimeMillis()).stopped

  private fun enforcementCount(reason: EnforcementReason): Long =
    EnforcementStatsStore(context).snapshot().counts[reason.key] ?: 0L

  private fun reelsDeadline(): DeadlineTask =
    controller.get().privateField<InstagramOwner>("instagram").javaClass.getDeclaredMethod("getReelsDeadline").apply {
      isAccessible = true
    }.invoke(controller.get().privateField<InstagramOwner>("instagram")) as DeadlineTask

  private fun advance(duration: Duration) {
    shadowOf(Looper.getMainLooper()).idleFor(duration)
  }

  @Suppress("UNCHECKED_CAST")
  private fun <T> Any.privateField(name: String): T =
    javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

  private companion object {
    const val INSTAGRAM = "com.instagram.android"
    const val SETTINGS = "com.android.settings"
  }
}
