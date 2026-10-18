package com.maxmauroner.zenguard

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED
import android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
import android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
import android.view.accessibility.AccessibilityWindowInfo
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowAccessibilityNodeInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [YouTubeShortsServiceTest.ShortsNodeShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class YouTubeShortsServiceTest {
  private lateinit var context: Context
  private lateinit var controller: ServiceController<ZenGuardAccessibilityService>

  @Implements(AccessibilityNodeInfo::class)
  class ShortsNodeShadow : ShadowAccessibilityNodeInfo() {
    @RealObject private lateinit var node: AccessibilityNodeInfo

    @Implementation
    protected fun findAccessibilityNodeInfosByViewId(viewId: String): MutableList<AccessibilityNodeInfo> {
      val matches = mutableListOf<AccessibilityNodeInfo>()
      fun visit(current: AccessibilityNodeInfo) {
        if (current.viewIdResourceName == viewId) matches.add(current)
        for (index in 0 until current.childCount) current.getChild(index)?.let(::visit)
      }
      visit(node)
      return matches
    }
  }

  @Before fun setUp() {
    context = RuntimeEnvironment.getApplication()
    HomeFeedStatusFailureRegistry.clearForTests()
    Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 7)
    ZenGuardPreferences(context).apply {
      acceptCurrentConsent()
      protectionEnabled = true
      observationMode = false
      shortsEnabled = true
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

  @Test fun `metadata-free pager advance requests Home only after settlement without counting an exit`() {
    val tree = shortsTree()
    showRoot(tree.root)
    val service = controller.get()
    assertNull(tree.page.collectionItemInfo)

    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 6, 0))
    assertTrue(shadowOf(tree.home).performedActions.isEmpty())
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 7, 1))
    assertTrue(shadowOf(tree.home).performedActions.isEmpty())
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 7, 7, 1))

    assertNull(tree.page.collectionItemInfo)
    assertEquals(listOf(ACTION_CLICK), shadowOf(tree.home).performedActions)
    assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    assertEquals(0L, EnforcementStatsStore(context).snapshot().counts[EnforcementReason.YOUTUBE_SHORTS.key] ?: 0L)
  }

  @Test fun `reconnecting the same service preserves a partial pager advance without counting an exit`() {
    val tree = shortsTree()
    showRoot(tree.root)
    val service = controller.get()
    assertNull(tree.page.collectionItemInfo)

    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 6, 0))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 7, 1))
    assertTrue(shadowOf(tree.home).performedActions.isEmpty())

    ZenGuardAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply {
      isAccessible = true
    }.invoke(service)
    assertTrue(shadowOf(tree.home).performedActions.isEmpty())
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 7, 7, 1))

    assertNull(tree.page.collectionItemInfo)
    assertEquals(listOf(ACTION_CLICK), shadowOf(tree.home).performedActions)
    assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    val snapshot = EnforcementStatsStore(context).snapshot()
    assertEquals(0L, snapshot.total)
    assertEquals(0L, snapshot.counts[EnforcementReason.YOUTUBE_SHORTS.key] ?: 0L)
  }

  @Test fun `content changes cannot prime a pager advance`() {
    val tree = shortsTree()
    showRoot(tree.root)
    val service = controller.get()

    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 6, 0))
    service.onAccessibilityEvent(event(TYPE_WINDOW_CONTENT_CHANGED, tree.pager, 6, 7, 1))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 7, 7, 1))

    assertTrue(shadowOf(tree.home).performedActions.isEmpty())
    assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    assertEquals(0L, EnforcementStatsStore(context).snapshot().counts[EnforcementReason.YOUTUBE_SHORTS.key] ?: 0L)
  }

  @Test fun `progress scrolls cannot prime a pager advance`() {
    val tree = shortsTree()
    showRoot(tree.root)
    val service = controller.get()

    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 6, 0))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.progress, 6, 7, 1))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 7, 7, 1))

    assertTrue(shadowOf(tree.home).performedActions.isEmpty())
    assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    assertEquals(0L, EnforcementStatsStore(context).snapshot().counts[EnforcementReason.YOUTUBE_SHORTS.key] ?: 0L)
  }

  @Test fun `scrolls without a source cannot prime a pager advance`() {
    val tree = shortsTree()
    showRoot(tree.root)
    val service = controller.get()

    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 6, 0))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, null, 6, 7, 1))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 7, 7, 1))

    assertTrue(shadowOf(tree.home).performedActions.isEmpty())
    assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    assertEquals(0L, EnforcementStatsStore(context).snapshot().counts[EnforcementReason.YOUTUBE_SHORTS.key] ?: 0L)
  }

  @Test fun `a negative from index cannot prime a pager advance`() {
    val tree = shortsTree()
    showRoot(tree.root)
    val service = controller.get()

    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 6, 0))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, -1, 7, 1))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 7, 7, 1))

    assertTrue(shadowOf(tree.home).performedActions.isEmpty())
    assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    assertEquals(0L, EnforcementStatsStore(context).snapshot().counts[EnforcementReason.YOUTUBE_SHORTS.key] ?: 0L)
  }

  @Test fun `a negative to index cannot prime a pager advance`() {
    val tree = shortsTree()
    showRoot(tree.root)
    val service = controller.get()

    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 6, 0))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, -1, 1))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 7, 7, 1))

    assertTrue(shadowOf(tree.home).performedActions.isEmpty())
    assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    assertEquals(0L, EnforcementStatsStore(context).snapshot().counts[EnforcementReason.YOUTUBE_SHORTS.key] ?: 0L)
  }

  @Test fun `a negative scroll position cannot prime a pager advance`() {
    val tree = shortsTree()
    showRoot(tree.root)
    val service = controller.get()

    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 6, 0))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 7, -1))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 7, 7, 1))

    assertTrue(shadowOf(tree.home).performedActions.isEmpty())
    assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    assertEquals(0L, EnforcementStatsStore(context).snapshot().counts[EnforcementReason.YOUTUBE_SHORTS.key] ?: 0L)
  }

  @Test fun `a partial gesture without a baseline does not request Home`() {
    val tree = shortsTree()
    showRoot(tree.root)
    val service = controller.get()

    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 7, 1))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 7, 7, 1))

    assertTrue(shadowOf(tree.home).performedActions.isEmpty())
    assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    assertEquals(0L, EnforcementStatsStore(context).snapshot().counts[EnforcementReason.YOUTUBE_SHORTS.key] ?: 0L)
  }

  @Test fun `repeated partial callbacks wait for settlement before requesting Home`() {
    val tree = shortsTree()
    showRoot(tree.root)
    val service = controller.get()

    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 6, 0))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 7, 1))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 7, 1))
    assertTrue(shadowOf(tree.home).performedActions.isEmpty())
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 7, 7, 1))

    assertEquals(listOf(ACTION_CLICK), shadowOf(tree.home).performedActions)
    assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    assertEquals(0L, EnforcementStatsStore(context).snapshot().counts[EnforcementReason.YOUTUBE_SHORTS.key] ?: 0L)
  }

  @Test fun `a gesture that snaps back to its baseline does not request Home`() {
    val tree = shortsTree()
    showRoot(tree.root)
    val service = controller.get()

    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 6, 0))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 7, 1))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 6, 0))

    assertTrue(shadowOf(tree.home).performedActions.isEmpty())
    assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    assertEquals(0L, EnforcementStatsStore(context).snapshot().counts[EnforcementReason.YOUTUBE_SHORTS.key] ?: 0L)
  }

  @Test fun `leaving Shorts discards a partial pager gesture`() {
    val tree = shortsTree()
    showRoot(tree.root)
    val service = controller.get()

    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 6, 0))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 7, 1))
    showRoot(AccessibilityNodeInfo.obtain().apply {
      packageName = YOUTUBE
      isVisibleToUser = true
    })
    service.onAccessibilityEvent(event(TYPE_WINDOW_CONTENT_CHANGED, null))
    showRoot(tree.root)
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 7, 7, 1))

    assertTrue(shadowOf(tree.home).performedActions.isEmpty())
    assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    assertEquals(0L, EnforcementStatsStore(context).snapshot().counts[EnforcementReason.YOUTUBE_SHORTS.key] ?: 0L)
  }

  @Test fun `switching apps discards a partial pager gesture`() {
    val tree = shortsTree()
    showRoot(tree.root)
    val service = controller.get()

    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 6, 0))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 7, 1))
    showRoot(AccessibilityNodeInfo.obtain().apply {
      packageName = "other.app"
      isVisibleToUser = true
    })
    service.onAccessibilityEvent(event(TYPE_WINDOW_STATE_CHANGED, null, packageName = "other.app"))
    showRoot(tree.root)
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 7, 7, 1))

    assertTrue(shadowOf(tree.home).performedActions.isEmpty())
    assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    assertEquals(0L, EnforcementStatsStore(context).snapshot().counts[EnforcementReason.YOUTUBE_SHORTS.key] ?: 0L)
  }

  @Test fun `a different visible collection row requests Home without scroll evidence`() {
    val tree = shortsTree(rowIndex = 6)
    showRoot(tree.root)
    val service = controller.get()

    service.onAccessibilityEvent(event(TYPE_WINDOW_CONTENT_CHANGED, null))
    assertTrue(shadowOf(tree.home).performedActions.isEmpty())
    service.onAccessibilityEvent(event(TYPE_WINDOW_CONTENT_CHANGED, null))
    assertTrue(shadowOf(tree.home).performedActions.isEmpty())
    tree.page.collectionItemInfo = AccessibilityNodeInfo.CollectionItemInfo.obtain(7, 1, 0, 1, false)
    showRoot(tree.root)
    service.onAccessibilityEvent(event(TYPE_WINDOW_CONTENT_CHANGED, null))

    assertEquals(listOf(ACTION_CLICK), shadowOf(tree.home).performedActions)
    assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    assertEquals(0L, EnforcementStatsStore(context).snapshot().counts[EnforcementReason.YOUTUBE_SHORTS.key] ?: 0L)
  }

  @Test fun `observation mode does not request Home for a valid pager advance`() {
    ZenGuardPreferences(context).observationMode = true
    val tree = shortsTree()
    showRoot(tree.root)
    val service = controller.get()

    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 6, 0))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 7, 1))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 7, 7, 1))

    assertTrue(shadowOf(tree.home).performedActions.isEmpty())
    assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    assertEquals(0L, EnforcementStatsStore(context).snapshot().counts[EnforcementReason.YOUTUBE_SHORTS.key] ?: 0L)
  }

  @Test fun `disabled Shorts protection does not request Home for a valid pager advance`() {
    ZenGuardPreferences(context).shortsEnabled = false
    val tree = shortsTree()
    showRoot(tree.root)
    val service = controller.get()

    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 6, 0))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 7, 1))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 7, 7, 1))

    assertTrue(shadowOf(tree.home).performedActions.isEmpty())
    assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    assertEquals(0L, EnforcementStatsStore(context).snapshot().counts[EnforcementReason.YOUTUBE_SHORTS.key] ?: 0L)
  }

  @Test fun `disabled global protection does not request Home for a valid pager advance`() {
    ZenGuardPreferences(context).protectionEnabled = false
    val tree = shortsTree()
    showRoot(tree.root)
    val service = controller.get()

    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 6, 0))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 7, 1))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 7, 7, 1))

    assertTrue(shadowOf(tree.home).performedActions.isEmpty())
    assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    assertEquals(0L, EnforcementStatsStore(context).snapshot().counts[EnforcementReason.YOUTUBE_SHORTS.key] ?: 0L)
  }

  @Test fun `an unsuccessful Home click has no global fallback and does not count an exit`() {
    val tree = shortsTree()
    shadowOf(tree.home).setOnPerformActionListener { _, _ -> false }
    showRoot(tree.root)
    val service = controller.get()

    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 6, 0))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 7, 1))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 7, 7, 1))

    assertEquals(listOf(ACTION_CLICK), shadowOf(tree.home).performedActions)
    assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    assertEquals(0L, EnforcementStatsStore(context).snapshot().counts[EnforcementReason.YOUTUBE_SHORTS.key] ?: 0L)
  }

  @Test fun `an unavailable Home click has no global fallback and does not count an exit`() {
    val tree = shortsTree()
    tree.home.isClickable = false
    showRoot(tree.root)
    val service = controller.get()

    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 6, 0))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 7, 1))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 7, 7, 1))

    assertTrue(shadowOf(tree.home).performedActions.isEmpty())
    assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    assertEquals(0L, EnforcementStatsStore(context).snapshot().counts[EnforcementReason.YOUTUBE_SHORTS.key] ?: 0L)
  }

  @Test fun `selected Home in an unknown tree does not confirm a requested exit`() {
    val tree = shortsTree()
    showRoot(tree.root)
    val service = controller.get()

    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 6, 0))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 7, 1))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 7, 7, 1))
    assertEquals(listOf(ACTION_CLICK), shadowOf(tree.home).performedActions)
    assertEquals(0L, EnforcementStatsStore(context).snapshot().counts[EnforcementReason.YOUTUBE_SHORTS.key] ?: 0L)

    tree.pager.viewIdResourceName = ""
    tree.page.viewIdResourceName = ""
    tree.progress.viewIdResourceName = ""
    tree.home.isSelected = true
    showRoot(tree.root)
    service.onAccessibilityEvent(event(TYPE_WINDOW_CONTENT_CHANGED, null))

    assertEquals(listOf(ACTION_CLICK), shadowOf(tree.home).performedActions)
    assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    assertEquals(0L, EnforcementStatsStore(context).snapshot().counts[EnforcementReason.YOUTUBE_SHORTS.key] ?: 0L)
  }

  @Test fun `an ordinary watch observation does not confirm a requested exit`() {
    val tree = shortsTree()
    showRoot(tree.root)
    val service = controller.get()

    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 6, 0))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 6, 7, 1))
    service.onAccessibilityEvent(event(TYPE_VIEW_SCROLLED, tree.pager, 7, 7, 1))
    assertEquals(listOf(ACTION_CLICK), shadowOf(tree.home).performedActions)
    assertEquals(0L, EnforcementStatsStore(context).snapshot().counts[EnforcementReason.YOUTUBE_SHORTS.key] ?: 0L)

    tree.pager.viewIdResourceName = ""
    tree.page.viewIdResourceName = "$YOUTUBE:id/watch_player"
    tree.progress.viewIdResourceName = ""
    tree.home.isSelected = true
    showRoot(tree.root)
    service.onAccessibilityEvent(event(TYPE_WINDOW_CONTENT_CHANGED, null))

    assertEquals(listOf(ACTION_CLICK), shadowOf(tree.home).performedActions)
    assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    assertEquals(0L, EnforcementStatsStore(context).snapshot().counts[EnforcementReason.YOUTUBE_SHORTS.key] ?: 0L)
  }

  private data class ShortsTree(
    val root: AccessibilityNodeInfo,
    val pager: AccessibilityNodeInfo,
    val page: AccessibilityNodeInfo,
    val progress: AccessibilityNodeInfo,
    val home: AccessibilityNodeInfo,
  )

  private fun shortsTree(rowIndex: Int? = null): ShortsTree {
    fun node() = AccessibilityNodeInfo.obtain().apply {
      packageName = YOUTUBE
      isVisibleToUser = true
    }
    val root = node()
    val pager = node().apply { viewIdResourceName = "$YOUTUBE:id/reel_recycler" }
    val page = node().apply {
      viewIdResourceName = "$YOUTUBE:id/reel_player_page_container"
      if (rowIndex != null) collectionItemInfo = AccessibilityNodeInfo.CollectionItemInfo.obtain(rowIndex, 1, 0, 1, false)
    }
    val progress = node().apply { viewIdResourceName = "$YOUTUBE:id/reel_progress_bar" }
    val home = node().apply { isClickable = true }
    // Cloned nodes share the action log only if it exists before the first clone.
    assertTrue(shadowOf(home).performedActions.isEmpty())
    shadowOf(home).setOnPerformActionListener { action, _ -> action == ACTION_CLICK }
    val item = node().also { shadowOf(it).addChild(home) }
    val tabs = node().apply { viewIdResourceName = "$YOUTUBE:id/pivot_bar" }
    shadowOf(tabs).addChild(item)
    shadowOf(page).addChild(progress)
    shadowOf(pager).addChild(page)
    shadowOf(root).addChild(pager)
    shadowOf(root).addChild(tabs)
    return ShortsTree(root, pager, page, progress, home)
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

  private fun event(
    type: Int,
    source: AccessibilityNodeInfo?,
    fromIndex: Int = -1,
    toIndex: Int = -1,
    scrollY: Int = -1,
    packageName: String = YOUTUBE,
  ): AccessibilityEvent = AccessibilityEvent.obtain(type).apply {
    this.packageName = packageName
    this.fromIndex = fromIndex
    this.toIndex = toIndex
    this.scrollY = scrollY
    shadowOf(this).setSourceNode(source)
  }

  private companion object {
    const val YOUTUBE = "com.google.android.youtube"
  }
}
