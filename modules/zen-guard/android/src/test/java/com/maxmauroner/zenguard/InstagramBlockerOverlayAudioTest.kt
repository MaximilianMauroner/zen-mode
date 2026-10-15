package com.maxmauroner.zenguard

import android.accessibilityservice.AccessibilityService
import android.media.AudioManager
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioManager

/**
 * Issue #74: closing a Reels blocker started the user's last media. The
 * blocker may stop the reel, but it must not wake any other player.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InstagramBlockerOverlayAudioTest {
  private lateinit var overlay: InstagramBlockerOverlay
  private lateinit var audio: ShadowAudioManager

  @Before fun setUp() {
    val service = Robolectric.setupService(BlankAccessibilityService::class.java)
    audio = shadowOf(service.getSystemService(AudioManager::class.java))
    overlay = InstagramBlockerOverlay(
      service = service,
      onLeave = {},
      onOpenMessages = {},
      onContinue = { true },
      readStats = {
        InstagramBlockerStats(
          home = HomeFeedRuntimeState(usedMs = 0L, blockedUntilElapsedMs = null),
          homeAllowanceMinutes = 30,
          stoppedToday = 0,
          continuedToday = 0,
          dailyCountsResetInMs = 0L,
        )
      },
    )
  }

  @After fun tearDown() {
    overlay.hide()
  }

  @Test fun `closing a Reels blocker does not tell the earlier player to resume`() {
    showReelsBlocker()
    val request = audio.lastAudioFocusRequest.audioFocusRequest

    // Android sends AUDIOFOCUS_GAIN back to the earlier focus holder when a
    // transient request is abandoned. Only a permanent request ends cleanly.
    assertEquals(AudioManager.AUDIOFOCUS_GAIN, request.focusGain)

    overlay.hide()
    assertSame(request, audio.lastAbandonedAudioFocusRequest)
  }

  @Test fun `a Reels blocker sends no media keys`() {
    showReelsBlocker()

    // Android routes media keys to the last app with a media session that
    // played audio, and restarts that session if it is inactive. That app
    // is often not Instagram.
    assertTrue(audio.dispatchedMediaKeyEvents.isEmpty())
  }

  private fun showReelsBlocker() {
    overlay.show(
      action = InstagramGuardAction.ShowBlocker(InstagramBlockReason.REELS_ENTRY, continueAvailableAt = null),
      reelsMinutes = 5,
      debugInfo = null,
    )
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(overlay.isShowing)
  }
}

private class BlankAccessibilityService : AccessibilityService() {
  override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

  override fun onInterrupt() = Unit
}
