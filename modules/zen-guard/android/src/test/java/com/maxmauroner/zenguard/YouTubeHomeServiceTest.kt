package com.maxmauroner.zenguard

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
import android.view.accessibility.AccessibilityWindowInfo
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class YouTubeHomeServiceTest {
  private lateinit var context: Context
  private lateinit var preferences: ZenGuardPreferences
  private lateinit var controller: ServiceController<ZenGuardAccessibilityService>
  private var destroyed = false

  @Before fun setUp() {
    context = RuntimeEnvironment.getApplication()
    HomeFeedStatusFailureRegistry.clearForTests()
    Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 7)
    preferences = ZenGuardPreferences(context).apply {
      acceptCurrentConsent()
      protectionEnabled = true
      observationMode = false
      shortsEnabled = false
      youtubeHomeEnabled = true
    }
    shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
    controller = Robolectric.buildService(ZenGuardAccessibilityService::class.java).create()
    connect()
  }

  @After fun tearDown() {
    if (!destroyed) controller.destroy()
    HomeFeedStatusFailureRegistry.clearForTests()
  }

  @Test fun `idle Home switches to Subscriptions at ten seconds without another event`() {
    val tree = homeTree()
    enter(tree)
    assertTrue(preferences.youtubeHomeObserved)
    assertEquals(0L, preferences.lastDetectionAt)
    advance(9_999)
    assertNoClick(tree)
    advance(1)
    assertEquals(listOf(ACTION_CLICK), shadowOf(tree.subscriptions).performedActions)
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())
  }

  @Test fun `continuous Home events cannot extend the visit or repeat a navigation attempt`() {
    val tree = homeTree()
    enter(tree)
    repeat(9) {
      advance(1_000)
      contentEvent()
    }
    assertNoClick(tree)
    advance(1_000)
    repeat(5) {
      contentEvent()
      advance(2_000)
    }
    assertEquals(listOf(ACTION_CLICK), shadowOf(tree.subscriptions).performedActions)
  }

  @Test fun `a failed Subscriptions click is attempted once for the visit`() {
    val tree = homeTree()
    shadowOf(tree.subscriptions).setOnPerformActionListener { _, _ -> false }
    enter(tree)
    advance(10_000)
    contentEvent()
    advance(10_000)
    assertEquals(listOf(ACTION_CLICK), shadowOf(tree.subscriptions).performedActions)
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())
  }

  @Test fun `deadline resolves the current tree instead of clicking a stale target`() {
    val stale = homeTree()
    enter(stale)
    advance(5_000)
    val current = homeTree()
    showRoot(current.root)
    advance(5_000)
    assertNoClick(stale)
    assertEquals(listOf(ACTION_CLICK), shadowOf(current.subscriptions).performedActions)
  }

  @Test fun `leaving Home cancels the visit and returning starts a full new window`() {
    val tree = homeTree()
    enter(tree)
    advance(5_000)
    showRoot(node())
    contentEvent()
    showRoot(tree.root)
    contentEvent()
    advance(5_000)
    assertNoClick(tree)
    advance(5_000)
    assertEquals(listOf(ACTION_CLICK), shadowOf(tree.subscriptions).performedActions)
  }

  @Test fun `returning to Zen Mode cancels the Home visit`() {
    val tree = homeTree()
    enter(tree)
    advance(5_000)
    showRoot(node().apply { packageName = context.packageName })
    val backgroundYouTube = AccessibilityWindowInfo().also {
      shadowOf(it).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
      shadowOf(it).setRoot(tree.root)
    }
    shadowOf(controller.get()).setWindows(controller.get().windows + backgroundYouTube)
    controller.get().onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
      packageName = context.packageName
    })
    showRoot(tree.root)
    advance(10_000)
    assertNoClick(tree)
    contentEvent()
    advance(10_000)
    assertEquals(listOf(ACTION_CLICK), shadowOf(tree.subscriptions).performedActions)
  }

  @Test fun `a foreign active root at the deadline cancels instead of navigating`() {
    val tree = homeTree()
    enter(tree)
    showRoot(homeTree().root.apply { packageName = "another.app" })
    advance(10_000)
    showRoot(tree.root)
    advance(10_000)
    assertNoClick(tree)
  }

  @Test fun `an unknown or absent active root at the deadline cancels instead of navigating`() {
    for (root in listOf(node(), null)) {
      val tree = homeTree()
      enter(tree)
      if (root == null) shadowOf(controller.get()).setRootInActiveWindow(null) else showRoot(root)
      advance(10_000)
      showRoot(tree.root)
      advance(10_000)
      assertNoClick(tree)
    }
  }

  @Test fun `watch search channel and other preserved surfaces veto a retained selected Home tab`() {
    for (id in listOf("watch_player", "search_results", "search_edit_text", "channel_header", "reel_recycler", "notification_panel", "playlist_panel")) {
      val tree = homeTree()
      enter(tree)
      shadowOf(tree.root).addChild(node().apply { viewIdResourceName = "$YOUTUBE:id/$id" })
      advance(10_000)
      assertNoClick(tree)
    }
  }

  @Test fun `a Home label outside the navigation bar cannot arm the rule`() {
    val tree = homeTree()
    tree.bar.viewIdResourceName = ""
    enter(tree)
    advance(10_000)
    assertFalse(preferences.youtubeHomeObserved)
    assertNoClick(tree)
  }

  @Test fun `a selected Home tab alone cannot identify a Home feed`() {
    val tree = homeTree()
    tree.content.contentDescription = ""
    enter(tree)
    advance(10_000)
    assertFalse(preferences.youtubeHomeObserved)
    assertNoClick(tree)
  }

  @Test fun `Home content without a selected Home control fails open`() {
    val tree = homeTree()
    tree.home.isSelected = false
    enter(tree)
    advance(10_000)
    assertFalse(preferences.youtubeHomeObserved)
    assertNoClick(tree)
  }

  @Test fun `selected state and semantics can live on a child of the navigation control`() {
    val tree = homeTree()
    tree.home.contentDescription = ""
    tree.home.isSelected = false
    shadowOf(tree.home).addChild(node().apply {
      text = "Home"
      isSelected = true
    })
    tree.subscriptions.contentDescription = ""
    shadowOf(tree.subscriptions).addChild(node().apply { text = "Subscriptions" })
    enter(tree)
    advance(10_000)
    assertEquals(listOf(ACTION_CLICK), shadowOf(tree.subscriptions).performedActions)
  }

  @Test fun `conflicting selected tabs fail open`() {
    val tree = homeTree()
    tree.subscriptions.isSelected = true
    enter(tree)
    advance(10_000)
    assertFalse(preferences.youtubeHomeObserved)
    assertNoClick(tree)
  }

  @Test fun `an invisible or disabled Subscriptions target fails open at the deadline`() {
    for (hide in listOf(true, false)) {
      val tree = homeTree()
      enter(tree)
      if (hide) tree.subscriptions.isVisibleToUser = false else tree.subscriptions.isEnabled = false
      advance(10_000)
      assertNoClick(tree)
    }
  }

  @Test fun `duplicate Home controls fail open`() {
    val tree = homeTree()
    shadowOf(tree.bar).addChild(tab("Home"))
    enter(tree)
    advance(10_000)
    assertFalse(preferences.youtubeHomeObserved)
    assertNoClick(tree)
  }

  @Test fun `duplicate Subscriptions controls and content lookalikes are never clicked`() {
    val tree = homeTree()
    val duplicate = tab("Subscriptions")
    shadowOf(tree.bar).addChild(duplicate)
    enter(tree)
    advance(10_000)
    assertNoClick(tree)
    assertTrue(shadowOf(duplicate).performedActions.isEmpty())

    val contentOnly = homeTree()
    contentOnly.subscriptions.contentDescription = "Unknown"
    val lookalike = tab("Subscriptions")
    shadowOf(contentOnly.content).addChild(lookalike)
    enter(contentOnly)
    advance(10_000)
    assertNoClick(contentOnly)
    assertTrue(shadowOf(lookalike).performedActions.isEmpty())
  }

  @Test fun `Subscriptions resolves by semantics when the tab order changes`() {
    val tree = homeTree(subscriptionsFirst = true)
    enter(tree)
    advance(10_000)
    assertEquals(listOf(ACTION_CLICK), shadowOf(tree.subscriptions).performedActions)
    assertTrue(shadowOf(tree.home).performedActions.isEmpty())
  }

  @Test fun `observation records a positive Home signal without navigation`() {
    preferences.observationMode = true
    val tree = homeTree()
    enter(tree)
    advance(20_000)
    assertTrue(preferences.youtubeHomeObserved)
    assertTrue(preferences.hasRequiredYouTubeSignals())
    assertNoClick(tree)
    preferences.observationMode = false
    contentEvent()
    advance(9_999)
    assertNoClick(tree)
    advance(1)
    assertEquals(listOf(ACTION_CLICK), shadowOf(tree.subscriptions).performedActions)
  }

  @Test fun `disabled Home never navigates even with a previously observed signal`() {
    preferences.youtubeHomeEnabled = false
    val tree = homeTree()
    enter(tree)
    advance(20_000)
    assertTrue(preferences.youtubeHomeObserved)
    assertNoClick(tree)
  }

  @Test fun `changing settings before the deadline cancels even if enabled again`() {
    val tree = homeTree()
    enter(tree)
    advance(5_000)
    preferences.youtubeHomeEnabled = false
    preferences.youtubeHomeEnabled = true
    shadowOf(Looper.getMainLooper()).idle()
    advance(10_000)
    assertNoClick(tree)
    contentEvent()
    advance(10_000)
    assertEquals(listOf(ACTION_CLICK), shadowOf(tree.subscriptions).performedActions)
  }

  @Test fun `observation and paused protection cancel an armed deadline`() {
    for (observe in listOf(true, false)) {
      preferences.observationMode = false
      preferences.protectionEnabled = true
      val tree = homeTree()
      enter(tree)
      if (observe) preferences.observationMode = true else preferences.protectionEnabled = false
      advance(10_000)
      assertNoClick(tree)
    }
  }

  @Test fun `screen off cancels the visit`() {
    val tree = homeTree()
    enter(tree)
    val power = shadowOf(context.getSystemService(PowerManager::class.java))
    power.setIsInteractive(false)
    context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
    shadowOf(Looper.getMainLooper()).idle()
    power.setIsInteractive(true)
    advance(10_000)
    assertNoClick(tree)
  }

  @Test fun `interrupt reconnect and destroy cancel pending Home actions`() {
    val tree = homeTree()
    enter(tree)
    controller.get().onInterrupt()
    advance(10_000)
    assertNoClick(tree)
    contentEvent()
    connect()
    advance(10_000)
    assertNoClick(tree)
    contentEvent()
    controller.destroy()
    destroyed = true
    advance(10_000)
    assertNoClick(tree)
  }

  @Test fun `a settings window cancels the deadline before its accessibility event`() {
    val tree = homeTree()
    enter(tree)
    val safety = AppRuleSafety.fromPackages(context.packageName, setOf("com.android.settings"))
    controller.get().javaClass.getDeclaredField("appRuleSafety").apply { isAccessible = true }.set(controller.get(), safety)
    val settingsRoot = node().apply { packageName = "com.android.settings" }
    val settings = AccessibilityWindowInfo().also {
      shadowOf(it).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
      shadowOf(it).setRoot(settingsRoot)
    }
    shadowOf(controller.get()).setWindows(listOf(settings))
    advance(10_000)
    assertNoClick(tree)
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())
  }

  private data class HomeTree(
    val root: AccessibilityNodeInfo,
    val bar: AccessibilityNodeInfo,
    val home: AccessibilityNodeInfo,
    val subscriptions: AccessibilityNodeInfo,
    val content: AccessibilityNodeInfo,
  )

  // Synthetic semantic layout. The repository has no captured positive Home tree.
  private fun homeTree(subscriptionsFirst: Boolean = false): HomeTree {
    val root = node()
    val bar = node().apply { viewIdResourceName = "$YOUTUBE:id/pivot_bar" }
    val home = tab("Home").apply { isSelected = true }
    val subscriptions = tab("Subscriptions")
    val content = node().apply {
      contentDescription = "Home"
      isScrollable = true
    }
    val tabs = if (subscriptionsFirst) listOf(subscriptions, home) else listOf(home, subscriptions)
    tabs.forEach { shadowOf(bar).addChild(node().also { wrapper -> shadowOf(wrapper).addChild(it) }) }
    shadowOf(root).addChild(content)
    shadowOf(root).addChild(bar)
    return HomeTree(root, bar, home, subscriptions, content)
  }

  private fun tab(label: String): AccessibilityNodeInfo = node().apply {
    contentDescription = label
    isClickable = true
    assertTrue(shadowOf(this).performedActions.isEmpty())
    shadowOf(this).setOnPerformActionListener { action, _ -> action == ACTION_CLICK }
  }

  private fun node(): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
    packageName = YOUTUBE
    isVisibleToUser = true
    isEnabled = true
  }

  private fun enter(tree: HomeTree) {
    showRoot(tree.root)
    contentEvent()
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

  private fun contentEvent() {
    controller.get().onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
      packageName = YOUTUBE
    })
  }

  private fun assertNoClick(tree: HomeTree) {
    assertTrue(shadowOf(tree.subscriptions).performedActions.isEmpty())
  }

  private fun advance(milliseconds: Long) {
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
  }

  private fun connect() {
    ZenGuardAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply {
      isAccessible = true
    }.invoke(controller.get())
  }

  private companion object {
    const val YOUTUBE = "com.google.android.youtube"
  }
}
