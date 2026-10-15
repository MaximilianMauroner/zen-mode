package com.maxmauroner.zenguard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/** The real service counts a boundary only after Android attaches it, not when addView returns. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [OverlayAttachmentTest.SiteWindowManager::class])
@LooperMode(LooperMode.Mode.PAUSED)
class OverlayAttachmentTest {
  private lateinit var context: Context
  private lateinit var controller: ServiceController<ZenGuardAccessibilityService>
  private val service get() = controller.get()

  @Before fun setUp() {
    SiteWindowManager.failNextAdd = false
    SiteWindowManager.addRequests = 0
    context = RuntimeEnvironment.getApplication()
    HomeFeedStatusFailureRegistry.clearForTests()
    Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 7)
    val preferences = ZenGuardPreferences(context)
    preferences.acceptCurrentConsent()
    preferences.protectionEnabled = true
    preferences.instagramObservationMode = false
    AdultSiteRuleStore(context).apply {
      enabled = true
      add(BLOCKED_HOST)
    }
    shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
    controller = Robolectric.buildService(ZenGuardAccessibilityService::class.java).create()
    ZenGuardAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply {
      isAccessible = true
    }.invoke(service)
  }

  @After fun tearDown() {
    controller.destroy()
    HomeFeedStatusFailureRegistry.clearForTests()
  }

  @Test fun `a site boundary counts once after the delayed attachment`() {
    visitSite(BLOCKED_HOST)
    assertEquals(0L, count(EnforcementReason.BLOCKED_SITE))

    runLayoutPass()
    assertEquals(1L, count(EnforcementReason.BLOCKED_SITE))

    visitSite(BLOCKED_HOST)
    runLayoutPass()
    assertEquals(1L, count(EnforcementReason.BLOCKED_SITE))
    assertEquals(1L, stats().total)
  }

  @Test fun `a site boundary hidden before attachment never counts`() {
    visitSite(BLOCKED_HOST)
    leaveBrowser()
    runLayoutPass()
    assertEquals(0L, count(EnforcementReason.BLOCKED_SITE))
  }

  @Test fun `a later separate site boundary counts again`() {
    visitSite(BLOCKED_HOST)
    runLayoutPass()
    visitSite(ALLOWED_HOST)
    visitSite(BLOCKED_HOST)
    runLayoutPass()
    assertEquals(2L, count(EnforcementReason.BLOCKED_SITE))
  }

  @Test fun `a later site boundary counts after Home dismisses the first`() {
    visitSite(BLOCKED_HOST)
    runLayoutPass()
    pressSiteHome()
    visitSite(BLOCKED_HOST)
    runLayoutPass()
    assertEquals(2L, count(EnforcementReason.BLOCKED_SITE))
  }

  @Test fun `Home before attachment rejects the old boundary and counts its replacement`() {
    visitSite(BLOCKED_HOST)
    val lateAttach = pendingAttachment()
    pressSiteHome()
    lateAttach()
    runLayoutPass()
    assertEquals(0L, count(EnforcementReason.BLOCKED_SITE))

    visitSite(BLOCKED_HOST)
    runLayoutPass()
    assertEquals(1L, count(EnforcementReason.BLOCKED_SITE))
  }

  @Test fun `replacement before attachment counts only the current boundary`() {
    visitSite(BLOCKED_HOST)
    val lateAttach = pendingAttachment()
    visitSite(ALLOWED_HOST)
    visitSite(BLOCKED_HOST)
    lateAttach()
    assertEquals(0L, count(EnforcementReason.BLOCKED_SITE))
    runLayoutPass()
    assertEquals(1L, count(EnforcementReason.BLOCKED_SITE))
  }

  @Test fun `interrupt before attachment rejects the old boundary`() {
    visitSite(BLOCKED_HOST)
    service.onInterrupt()
    runLayoutPass()
    assertEquals(0L, count(EnforcementReason.BLOCKED_SITE))

    visitSite(BLOCKED_HOST)
    runLayoutPass()
    assertEquals(1L, count(EnforcementReason.BLOCKED_SITE))
  }

  @Test fun `repeat observations before attachment request one boundary`() {
    visitSite(BLOCKED_HOST)
    visitSite(BLOCKED_HOST)
    assertEquals(1, SiteWindowManager.addRequests)
    assertEquals(0L, stats().total)
    runLayoutPass()
    assertEquals(1L, stats().total)
  }

  @Test fun `a failed attachment counts zero and a later observation can retry`() {
    SiteWindowManager.failNextAdd = true
    visitSite(BLOCKED_HOST)
    runLayoutPass()
    assertEquals(0L, stats().total)
    assertTrue(siteViews().isEmpty())

    visitSite(BLOCKED_HOST)
    runLayoutPass()
    assertEquals(1L, count(EnforcementReason.BLOCKED_SITE))
  }

  @Test fun `protection off before attachment ends the boundary`() {
    visitSite(BLOCKED_HOST)
    ZenGuardPreferences(context).protectionEnabled = false
    runLayoutPass()
    assertEquals(0L, stats().total)
    assertTrue(siteViews().isEmpty())
  }

  @Test fun `Settings before attachment ends the boundary without a Settings event`() {
    visitSite(BLOCKED_HOST)
    shadowOf(service).setRootInActiveWindow(node("com.android.settings"))
    runLayoutPass()
    assertEquals(0L, stats().total)
    assertTrue(siteViews().isEmpty())
  }

  @Test fun `foreground loss cancels a queued Back recheck`() {
    visitSite(BLOCKED_HOST)
    runLayoutPass()
    pressSiteButton(R.string.zen_guard_site_go_back)
    leaveBrowser()
    shadowOf(service).setRootInActiveWindow(addressBar(BLOCKED_HOST))
    runRecheck()
    assertTrue(siteViews().isEmpty())
    assertEquals(1L, stats().total)
  }

  @Test fun `Back keeps a blocked boundary and clears it after an allowed observation`() {
    visitSite(BLOCKED_HOST)
    runLayoutPass()
    pressSiteButton(R.string.zen_guard_site_go_back)
    runRecheck()
    assertEquals(1L, count(EnforcementReason.BLOCKED_SITE))
    assertEquals(1, siteViews().size)
    assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_BACK), shadowOf(service).globalActionsPerformed)

    pressSiteButton(R.string.zen_guard_site_go_back)
    shadowOf(service).setRootInActiveWindow(addressBar(ALLOWED_HOST))
    runRecheck()
    assertTrue(siteViews().isEmpty())
    visitSite(BLOCKED_HOST)
    runLayoutPass()
    assertEquals(2L, count(EnforcementReason.BLOCKED_SITE))
  }

  @Test fun `a queued Back recheck reads live Settings safety without a Settings event`() {
    visitSite(BLOCKED_HOST)
    runLayoutPass()
    pressSiteButton(R.string.zen_guard_site_go_back)
    shadowOf(service).setRootInActiveWindow(node("com.android.settings"))
    runRecheck()
    assertTrue(siteViews().isEmpty())
    assertEquals(1L, stats().total)
    assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_BACK), shadowOf(service).globalActionsPerformed)
  }

  @Test fun `Home cancels the old Back recheck before a replacement in the same browser`() {
    visitSite(BLOCKED_HOST)
    runLayoutPass()
    pressSiteButton(R.string.zen_guard_site_go_back)
    pressSiteHome()
    visitSite(BLOCKED_HOST)
    runLayoutPass()
    shadowOf(service).setRootInActiveWindow(addressBar(ALLOWED_HOST))
    runRecheck()
    assertEquals(1, siteViews().size)
    assertEquals(2L, count(EnforcementReason.BLOCKED_SITE))
  }

  @Test fun `destroy and reconnect reject the old attachment and accept a fresh boundary`() {
    visitSite(BLOCKED_HOST)
    val lateAttach = pendingAttachment()
    controller.destroy()
    controller = Robolectric.buildService(ZenGuardAccessibilityService::class.java).create()
    ZenGuardAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply {
      isAccessible = true
    }.invoke(service)
    visitSite(BLOCKED_HOST)
    lateAttach()
    assertEquals(0L, stats().total)
    runLayoutPass()
    assertEquals(1L, count(EnforcementReason.BLOCKED_SITE))
  }

  @Test fun `an Instagram boundary counts once after the delayed attachment`() {
    deliver(INSTAGRAM, node(INSTAGRAM, "$INSTAGRAM:id/explore_grid"))
    assertEquals(0L, count(EnforcementReason.INSTAGRAM_EXPLORE))

    runLayoutPass()
    assertEquals(1L, count(EnforcementReason.INSTAGRAM_EXPLORE))
  }

  private fun visitSite(host: String) =
    deliver(CHROME, addressBar(host))

  private fun addressBar(host: String) = node(CHROME, "$CHROME:id/url_bar").apply { text = host }

  /** Leaving for an app with no browser window clears the site boundary unconditionally. */
  private fun leaveBrowser() =
    deliver(NOTES, node(NOTES), AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)

  private fun siteViews(): List<View> =
    Shadow.extract<ShadowWindowManagerImpl>(service.getSystemService(WindowManager::class.java)).views

  private fun pressSiteHome() = pressSiteButton(R.string.zen_guard_go_home)

  private fun pressSiteButton(label: Int) {
    val matches = ArrayList<View>()
    siteViews().forEach {
      it.findViewsWithText(matches, service.getString(label), View.FIND_VIEWS_WITH_TEXT)
    }
    matches.filterIsInstance<Button>().single().performClick()
  }

  private fun pendingAttachment(): () -> Unit {
    val view = siteViews().single()
    val listenerInfo = ReflectionHelpers.getField<Any>(view, "mListenerInfo")
    val listeners = ReflectionHelpers.getField<List<View.OnAttachStateChangeListener>>(
      listenerInfo, "mOnAttachStateChangeListeners",
    ).toList()
    return { listeners.forEach { it.onViewAttachedToWindow(view) } }
  }

  private fun runRecheck() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))

  @Implements(className = "android.view.WindowManagerImpl", isInAndroidSdk = false)
  class SiteWindowManager : ShadowWindowManagerImpl() {
    @Implementation
    override fun addView(view: View, params: ViewGroup.LayoutParams) {
      addRequests++
      if (failNextAdd) {
        failNextAdd = false
        throw WindowManager.BadTokenException("Rejected test boundary")
      }
      super.addView(view, params)
    }

    companion object {
      var failNextAdd = false
      var addRequests = 0
    }
  }

  private fun deliver(
    packageName: String,
    root: AccessibilityNodeInfo,
    type: Int = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
  ) {
    shadowOf(service).setRootInActiveWindow(root)
    service.onAccessibilityEvent(AccessibilityEvent.obtain(type).apply { this.packageName = packageName })
  }

  private fun node(packageName: String, viewId: String? = null) = AccessibilityNodeInfo.obtain().apply {
    this.packageName = packageName
    viewIdResourceName = viewId
    isVisibleToUser = true
  }

  private fun runLayoutPass() = shadowOf(Looper.getMainLooper()).idle()

  private fun stats() = EnforcementStatsStore(context).snapshot()

  private fun count(reason: EnforcementReason) = stats().counts[reason.key]

  private companion object {
    const val CHROME = "com.android.chrome"
    const val INSTAGRAM = "com.instagram.android"
    const val NOTES = "com.example.notes"
    const val BLOCKED_HOST = "blocked.example"
    const val ALLOWED_HOST = "allowed.example"
  }
}
