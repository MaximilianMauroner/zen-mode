package com.maxmauroner.zenguard

import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.time.Duration
import java.time.Instant
import java.util.TimeZone
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
import org.robolectric.shadows.ShadowSystemClock

/** The actual overlay must distinguish a Home break from daily tally rollover. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class InstagramBlockerOverlayTest {
  private lateinit var controller: ServiceController<ZenGuardAccessibilityService>
  private lateinit var overlay: InstagramBlockerOverlay
  private lateinit var tally: DailyTally
  private val policy = InstagramGuardStateMachine()
  private val settings = InstagramGuardSettings(homeAllowanceMs = 60_000)
  private var statsReads = 0
  private val setupWallMs = Instant.parse("2026-10-12T23:59:30Z").toEpochMilli()
  private var setupElapsedMs = 0L
  private lateinit var originalTimeZone: TimeZone

  @Before fun setUp() {
    setupElapsedMs = SystemClock.elapsedRealtime()
    originalTimeZone = TimeZone.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    controller = Robolectric.buildService(ZenGuardAccessibilityService::class.java).create()
    tally = DailyTally(controller.get())
    overlay = InstagramBlockerOverlay(
      service = controller.get(),
      onLeave = {},
      onOpenMessages = {},
      onContinue = { false },
      readStats = {
        statsReads++
        val wallNow = wallNowMs()
        val counts = tally.counts(wallNow)
        InstagramBlockerStats(
          home = policy.homeRuntimeState(SystemClock.elapsedRealtime()),
          homeAllowanceMinutes = 1,
          stoppedToday = counts.stopped,
          continuedToday = counts.continued,
          dailyCountsResetInMs = millisUntilLocalMidnight(wallNow),
        )
      },
    )
  }

  @After fun tearDown() {
    overlay.hide()
    controller.destroy()
    TimeZone.setDefault(originalTimeZone)
  }

  @Test fun `Home break displays availability separately from daily counter reset`() {
    show(startHomeBreak())

    assertEquals("1h 0m", statValue("Home available again in"))
    assertTrue(texts().contains("Daily counts reset in"))
    assertFalse(texts().contains("Home visit used"))
    assertTrue(texts().contains("BREAK"))
  }

  @Test fun `leaving and returning during a break does not show an unused Home allowance`() {
    startHomeBreak()
    policy.leaveBlockedSurface()
    ShadowSystemClock.advanceBy(Duration.ofMinutes(15))
    assertEquals(0L, policy.homeRuntimeState(SystemClock.elapsedRealtime()).usedMs)

    show(homeEvent() as InstagramGuardAction.ShowBlocker)

    assertEquals("45m", statValue("Home available again in"))
    assertFalse(texts().contains("0 / 1m"))
    assertFalse(texts().contains("Home visit used"))
  }

  @Test fun `Home availability updates through expiry without dismissing the overlay`() {
    show(startHomeBreak())
    idle(Duration.ofMinutes(1))
    assertEquals("59m", statValue("Home available again in"))

    idle(Duration.ofMinutes(59))

    assertEquals("Now", statValue("Home available again"))
    assertTrue(texts().contains("READY"))
    assertTrue(texts().contains(controller.get().getString(R.string.zen_guard_instagram_home_ready_detail)))
    assertTrue(overlay.isShowing)
  }

  @Test fun `unknown lockout and failed storage give distinct recovery guidance without a countdown`() {
    val unknownStates = listOf(
      HomeFeedRuntimeState(0L, null, lockoutState = HomeFeedLockoutState.UNKNOWN) to
        R.string.zen_guard_instagram_home_unknown_break_detail,
      HomeFeedRuntimeState(0L, null, storageState = HomeFeedStorageState.UNAVAILABLE) to
        R.string.zen_guard_instagram_home_unavailable_detail,
    )
    unknownStates.forEach { (state, detail) ->
      policy.restore(state, SystemClock.elapsedRealtime())
      show(homeEvent() as InstagramGuardAction.ShowBlocker)
      idle(Duration.ofMinutes(1))

      assertEquals("Unavailable", statValue("Home availability"))
      assertTrue(texts().contains("CLOSED"))
      assertFalse(texts().contains("Home available again in"))
      assertFalse(texts().contains("Now"))
      assertTrue(texts().contains(controller.get().getString(detail)))
      val otherDetail = unknownStates.single { it.second != detail }.second
      assertFalse(texts().contains(controller.get().getString(otherDetail)))
      overlay.hide()
    }
  }

  @Test fun `other blockers label Home usage as visit usage`() {
    show(InstagramGuardAction.ShowBlocker(InstagramBlockReason.EXPLORE, null))

    assertEquals("0 / 1m", statValue("Home visit used"))
    assertTrue(texts().contains("Daily counts reset in"))
    assertFalse(texts().contains("Home available again in"))
  }

  @Test fun `storage failure replaces the old countdown on an open Home blocker`() {
    show(startHomeBreak())
    policy.markStorageUnavailable()

    idle(Duration.ofMinutes(1))

    assertEquals("Unavailable", statValue("Home availability"))
    assertFalse(texts().contains("Home available again in"))
    assertFalse(texts().contains("READY"))
  }

  @Test fun `daily tally and reset time refresh after midnight on an open overlay`() {
    tally.recordStop(wallNowMs())
    tally.recordContinue(wallNowMs())
    show(InstagramGuardAction.ShowBlocker(InstagramBlockReason.EXPLORE, null))
    assertEquals("1", statValue("Stopped today"))
    assertEquals("1", statValue("Continued today"))

    idle(Duration.ofSeconds(29))
    assertEquals("1", statValue("Stopped today"))
    assertEquals("1", statValue("Continued today"))

    idle(Duration.ofSeconds(1))

    assertEquals("0", statValue("Stopped today"))
    assertEquals("0", statValue("Continued today"))
    assertEquals("24h 0m", statValue("Daily counts reset in"))
  }

  @Test fun `hiding an overlay stops its stats refresh`() {
    show(startHomeBreak())
    overlay.hide()
    val readsAfterHide = statsReads

    idle(Duration.ofMinutes(2))

    assertEquals(readsAfterHide, statsReads)
  }

  private fun startHomeBreak(): InstagramGuardAction.ShowBlocker {
    homeEvent()
    ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
    return homeEvent() as InstagramGuardAction.ShowBlocker
  }

  private fun homeEvent(): InstagramGuardAction = policy.next(
    InstagramGuardInput(InstagramSurface.HOME_FEED, SystemClock.elapsedRealtime()),
    settings,
  )

  private fun show(action: InstagramGuardAction.ShowBlocker) {
    overlay.show(action, reelsMinutes = 1, debugInfo = null, onAttached = {})
  }

  private fun idle(duration: Duration) {
    shadowOf(Looper.getMainLooper()).idleFor(duration)
  }

  private fun wallNowMs(): Long = setupWallMs + (SystemClock.elapsedRealtime() - setupElapsedMs)

  private fun statValue(label: String): String {
    val labelView = views(root()).filterIsInstance<TextView>().single { it.text.toString() == label }
    val cell = labelView.parent as ViewGroup
    return (cell.getChildAt(0) as TextView).text.toString()
  }

  private fun texts(): List<String> = views(root()).filterIsInstance<TextView>().map { it.text.toString() }.toList()

  private fun root(): View = InstagramBlockerOverlay::class.java.getDeclaredField("root").apply {
    isAccessible = true
  }.get(overlay) as View

  private fun views(view: View): Sequence<View> = sequence {
    yield(view)
    if (view is ViewGroup) {
      for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
  }
}
