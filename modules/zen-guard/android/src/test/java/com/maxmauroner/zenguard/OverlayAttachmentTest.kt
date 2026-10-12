package com.maxmauroner.zenguard

import android.content.Context
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import org.junit.After
import org.junit.Assert.assertEquals
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

/** The real service counts a boundary only after Android attaches it, not when addView returns. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class OverlayAttachmentTest {
  private lateinit var context: Context
  private lateinit var controller: ServiceController<ZenGuardAccessibilityService>
  private val service get() = controller.get()

  @Before fun setUp() {
    context = RuntimeEnvironment.getApplication()
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
  }

  @Test fun `a site boundary counts once after the delayed attachment`() {
    visitSite(BLOCKED_HOST)
    // The old hook read isAttachedToWindow here, before the layout pass, and skipped the count.
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
    pressSiteHome() // Robolectric sends no launcher event after the Home action.
    visitSite(ALLOWED_HOST)
    visitSite(BLOCKED_HOST)
    runLayoutPass()
    assertEquals(2L, count(EnforcementReason.BLOCKED_SITE))
  }

  @Test fun `an Instagram boundary counts once after the delayed attachment`() {
    deliver(INSTAGRAM, node(INSTAGRAM, "$INSTAGRAM:id/explore_grid"))
    assertEquals(0L, count(EnforcementReason.INSTAGRAM_EXPLORE))

    runLayoutPass()
    assertEquals(1L, count(EnforcementReason.INSTAGRAM_EXPLORE))
  }

  private fun visitSite(host: String) =
    deliver(CHROME, node(CHROME, "$CHROME:id/url_bar").apply { text = host })

  /** Leaving for an app with no browser window clears the site boundary unconditionally. */
  private fun leaveBrowser() =
    deliver(NOTES, node(NOTES), AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)

  /** The site detail copy also says "go home", so match only the button. */
  private fun pressSiteHome() {
    val windows = Shadow.extract<ShadowWindowManagerImpl>(service.getSystemService(WindowManager::class.java))
    val matches = ArrayList<View>()
    windows.views.forEach {
      it.findViewsWithText(matches, service.getString(R.string.zen_guard_go_home), View.FIND_VIEWS_WITH_TEXT)
    }
    matches.filterIsInstance<Button>().single().performClick()
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
