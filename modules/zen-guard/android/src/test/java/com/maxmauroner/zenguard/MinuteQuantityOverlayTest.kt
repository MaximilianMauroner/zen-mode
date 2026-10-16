package com.maxmauroner.zenguard

import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl

/** Check the displayed app question and unlocked Reels action, including their format arguments. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
@LooperMode(LooperMode.Mode.PAUSED)
class MinuteQuantityOverlayTest {
  private lateinit var controller: ServiceController<ZenGuardAccessibilityService>
  private lateinit var appOverlay: AppRuleOverlay
  private lateinit var instagramOverlay: InstagramBlockerOverlay

  @Before fun setUp() {
    controller = Robolectric.buildService(ZenGuardAccessibilityService::class.java).create()
    appOverlay = AppRuleOverlay(controller.get())
    instagramOverlay = InstagramBlockerOverlay(
      service = controller.get(),
      onLeave = {},
      onOpenMessages = {},
      onContinue = { false },
      readStats = { InstagramBlockerStats(HomeFeedRuntimeState(0L, null), 5, 0, 0, 60_000L) },
    )
  }

  @After fun tearDown() {
    appOverlay.hide()
    instagramOverlay.hide()
    controller.destroy()
  }

  @Test fun `one-minute app question uses singular copy and keeps its title line break`() {
    assertAppQuestion(1, "Take 1\nminute.", "Chrome stays open for 1 minute.")
  }

  @Test fun `five-minute app question uses plural copy and keeps its title line break`() {
    assertAppQuestion(5, "Take 5\nminutes.", "Chrome stays open for 5 minutes.")
  }

  @Test fun `unlocked one-minute Reels action uses singular copy`() {
    assertReelsAction(1, "Continue for 1 minute")
  }

  @Test fun `unlocked five-minute Reels action uses plural copy`() {
    assertReelsAction(5, "Continue for 5 minutes")
  }

  private fun assertAppQuestion(minutes: Int, expectedTitle: String, expectedDetail: String) {
    appOverlay.showAsk("com.android.chrome", "Chrome", minutes, {}, {}, {})
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
    assertTrue(appOverlay.isShowing())
    val texts = views().filterIsInstance<TextView>().map { it.text.toString() }.toList()
    assertTrue(texts.toString(), texts.contains(expectedTitle))
    assertTrue(texts.toString(), texts.any { it.startsWith(expectedDetail) })
  }

  private fun assertReelsAction(minutes: Int, expectedLabel: String) {
    instagramOverlay.show(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.REELS_SWIPE, SystemClock.elapsedRealtime() + 1_000L),
      minutes,
      debugInfo = null,
      onAttached = {},
    )
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_250))
    assertTrue(instagramOverlay.isShowing)
    val continueButton = views().filterIsInstance<Button>().single { it.text.startsWith("Continue for") }
    assertTrue(continueButton.isEnabled)
    assertEquals(expectedLabel, continueButton.text.toString())
  }

  private fun views(): Sequence<View> {
    val manager = controller.get().getSystemService(WindowManager::class.java)
    val windows = Shadow.extract<ShadowWindowManagerImpl>(manager)
    fun descendants(view: View): Sequence<View> = sequence {
      yield(view)
      if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    return windows.views.asSequence().flatMap(::descendants)
  }
}
