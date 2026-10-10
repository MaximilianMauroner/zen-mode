package com.maxmauroner.zenguard

import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Statistics count a site boundary only after Android attaches it, not when addView returns. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class OverlayAttachmentTest {
  private val controller = Robolectric.buildService(ZenGuardAccessibilityService::class.java).create()
  private val overlay = AdultSiteBlockerOverlay(controller.get())
  private var attached = 0

  @After fun tearDown() {
    overlay.hide()
    controller.destroy()
  }

  @Test fun `a boundary reports once after the delayed attachment`() {
    show()
    // The old hook read this immediately and skipped the count.
    assertFalse(overlay.isShowing)
    assertEquals(0, attached)

    runLayoutPass()
    assertEquals(1, attached)

    show()
    runLayoutPass()
    assertEquals(1, attached)
  }

  @Test fun `a boundary hidden before attachment never reports`() {
    show()
    overlay.hide()
    runLayoutPass()
    assertEquals(0, attached)
  }

  @Test fun `a later separate boundary reports again`() {
    show()
    runLayoutPass()
    overlay.hide()
    show()
    runLayoutPass()
    assertEquals(2, attached)
  }

  private fun show() = overlay.show("com.android.chrome", onBack = {}, onHome = {}, onAttached = { attached++ })

  private fun runLayoutPass() = shadowOf(Looper.getMainLooper()).idle()
}
