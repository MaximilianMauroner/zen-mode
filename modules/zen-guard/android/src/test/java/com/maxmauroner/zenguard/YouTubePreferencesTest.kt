package com.maxmauroner.zenguard

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class YouTubePreferencesTest {
  private lateinit var context: Context

  @Before fun clearPreferences() {
    context = RuntimeEnvironment.getApplication()
    context.getSharedPreferences("zen_guard_preferences", Context.MODE_PRIVATE).edit().clear().commit()
  }

  @Test fun homeDefaultsOffAndRemainsIndependentFromShorts() {
    val preferences = ZenGuardPreferences(context)
    assertTrue(preferences.shortsEnabled)
    assertFalse(preferences.youtubeHomeEnabled)
    assertFalse(preferences.youtubeHomeObserved)

    preferences.shortsEnabled = false
    preferences.youtubeHomeEnabled = true

    assertFalse(preferences.shortsEnabled)
    assertTrue(preferences.youtubeHomeEnabled)
    assertFalse(preferences.youtubeHomeObserved)
  }

  @Test fun observedHomeSignalIsDurableWithoutChangingShorts() {
    val preferences = ZenGuardPreferences(context)
    preferences.recordYouTubeHomeObserved()

    val restored = ZenGuardPreferences(context)
    assertTrue(restored.youtubeHomeObserved)
    assertTrue(restored.shortsEnabled)
    assertFalse(restored.youtubeHomeEnabled)
  }
}
