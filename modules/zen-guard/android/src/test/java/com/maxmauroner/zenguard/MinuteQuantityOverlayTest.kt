package com.maxmauroner.zenguard

import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
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
  private lateinit var instagramIntervention: InstagramIntervention

  @Before fun setUp() {
    controller = Robolectric.buildService(ZenGuardAccessibilityService::class.java).create()
    appOverlay = AppRuleOverlay(controller.get())
    instagramIntervention = InstagramIntervention(
      service = controller.get(),
      readStats = { InstagramBlockerStats(HomeFeedRuntimeState(0L, null), 5, 0, 0, 60_000L) },
      canEnforce = { true },
      recordStop = {},
      recordAttachment = {},
      leave = {},
      openMessages = {},
      continueReels = { false },
    )
  }

  @After fun tearDown() {
    appOverlay.hide()
    instagramIntervention.end()
    controller.destroy()
  }

  @Test fun `app question offers one Start per visit length and reports the chosen length`() {
    var chosen: Int? = null
    appOverlay.showAsk("com.android.chrome", "Chrome", listOf(1, 5), 0L, { chosen = it }, {}, {})
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
    assertTrue(appOverlay.isShowing())
    val starts = views().filterIsInstance<Button>().filter { it.text.contains(" min · until ") }.toList()
    assertEquals(listOf("1 min", "5 min"), starts.map { it.text.toString().substringBefore(" · ") })

    starts.last().performClick()
    assertEquals(5, chosen)
  }

  @Test fun `an open app question moves each end time forward with the clock`() {
    val format = android.text.format.DateFormat.getTimeFormat(controller.get())
    val endsAt = { wallMs: Long -> "5 min · until ${format.format(java.util.Date(wallMs + 5 * 60_000L))}" }
    appOverlay.showAsk("com.android.chrome", "Chrome", listOf(5), 0L, {}, {}, {})
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
    val start = views().filterIsInstance<Button>().single { it.text.startsWith("5 min") }
    assertEquals(endsAt(0L), start.text.toString())

    appOverlay.showAsk("com.android.chrome", "Chrome", listOf(5), 10 * 60_000L, {}, {}, {})
    assertEquals(endsAt(10 * 60_000L), start.text.toString())
  }

  @Test fun `unlocked one-minute Reels action uses singular copy`() {
    assertReelsAction(1, "Continue for 1 minute")
  }

  @Test fun `unlocked five-minute Reels action uses plural copy`() {
    assertReelsAction(5, "Continue for 5 minutes")
  }

  private fun assertReelsAction(minutes: Int, expectedLabel: String) {
    instagramIntervention.observe(
      InstagramGuardAction.ShowBlocker(InstagramBlockReason.REELS_SWIPE, SystemClock.elapsedRealtime() + 1_000L),
      minutes,
      debugInfo = null,
    )
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_250))
    assertTrue(instagramIntervention.isShowing)
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
