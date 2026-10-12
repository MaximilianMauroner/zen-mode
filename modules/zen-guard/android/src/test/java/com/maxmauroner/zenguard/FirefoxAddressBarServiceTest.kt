package com.maxmauroner.zenguard

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

/** Firefox 157 leaves its address box text empty and puts the address in the content description. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class FirefoxAddressBarServiceTest {
  private lateinit var context: Context
  private lateinit var controller: ServiceController<ZenGuardAccessibilityService>

  @Before fun setUp() {
    context = RuntimeEnvironment.getApplication()
    HomeFeedStatusFailureRegistry.clearForTests()
    Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 7)
    ZenGuardPreferences(context).apply {
      acceptCurrentConsent()
      protectionEnabled = true
    }
    AdultSiteRuleStore(context).apply {
      enabled = true
      add("example.com")
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

  @Test fun `described Firefox address box records the Firefox signal`() {
    val service = controller.get()
    val addressBox = AccessibilityNodeInfo.obtain().apply {
      packageName = FIREFOX_PACKAGE
      viewIdResourceName = "ADDRESSBAR_URL_BOX"
      text = ""
      contentDescription = " example.com. Search or enter address"
      isVisibleToUser = true
    }
    shadowOf(service).setRootInActiveWindow(addressBox)

    service.onAccessibilityEvent(
      AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
        packageName = FIREFOX_PACKAGE
      },
    )

    assertEquals(FIREFOX_SIGNAL, AdultSiteRuleStore(context).browserSignalMask)
  }

  private companion object {
    const val FIREFOX_PACKAGE = "org.mozilla.firefox"
    /** Firefox's bit in [AdultSiteRuleStore.browserSignalMask]. */
    const val FIREFOX_SIGNAL = 8
  }
}
