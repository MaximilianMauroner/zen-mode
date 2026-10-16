package com.maxmauroner.zenguard

import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class XBreakOverlayTimerTest {
  private lateinit var controller: ServiceController<ZenGuardAccessibilityService>
  private lateinit var overlay: XBreakOverlay
  private var attached = 0
  private var expired = 0

  @Before fun setUp() {
    controller = Robolectric.buildService(ZenGuardAccessibilityService::class.java).create()
    overlay = XBreakOverlay(controller.get(), {}, { expired++ })
  }

  @After fun tearDown() {
    overlay.hide()
    controller.destroy()
  }

  @Test fun `X break shows the remaining runtime deadline and expires without a new app event`() {
    val deadline = SystemClock.elapsedRealtime() + 3_000L
    overlay.show(5, deadline) { attached++ }
    assertEquals(0, attached)
    advance(Duration.ofMillis(20))
    assertTrue(texts().contains("Home available again in 0:03"))
    assertEquals(1, attached)
    advance(Duration.ofSeconds(1))
    assertTrue(texts().contains("Home available again in 0:02"))
    overlay.show(5, deadline) { attached++ }
    assertEquals(1, attached)
    advance(Duration.ofSeconds(3))
    assertFalse(overlay.isShowing)
    assertEquals(1, expired)
  }

  @Test fun `unverified accounting has no countdown or expiry callback`() {
    overlay.showUnavailable()
    advance(Duration.ofMillis(20))
    assertTrue(overlay.isShowing)
    assertFalse(texts().any { it.startsWith("Home available again in") })
    advance(Duration.ofSeconds(5))
    assertTrue(overlay.isShowing)
    assertEquals(0, expired)
  }

  @Test fun `a hidden pending X card neither attaches a boundary nor expires later`() {
    overlay.show(5, SystemClock.elapsedRealtime() + 3_000L) { attached++ }
    overlay.hide()
    advance(Duration.ofSeconds(5))
    assertEquals(0, attached)
    assertEquals(0, expired)
    assertFalse(overlay.isShowing)
  }

  private fun advance(duration: Duration) { shadowOf(Looper.getMainLooper()).idleFor(duration) }
  private fun texts(): List<String> {
    val root = overlay.javaClass.getDeclaredField("root").apply { isAccessible = true }.get(overlay) as View?
      ?: return emptyList()
    fun descendants(view: View): Sequence<View> = sequence {
      yield(view)
      if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    return descendants(root).filterIsInstance<TextView>().map { it.text.toString() }.toList()
  }
}
