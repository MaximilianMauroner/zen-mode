package com.maxmauroner.zenguard

import android.content.Context
import android.provider.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the Context constructor and the real Settings.Global BOOT_COUNT read path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeFeedStatusStoreAndroidTest {
  private lateinit var context: Context

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    clearPreferences()
    HomeFeedStatusFailureRegistry.clearForTests()
  }

  @After
  fun tearDown() {
    clearPreferences()
    HomeFeedStatusFailureRegistry.clearForTests()
  }

  @Test
  fun `missing BOOT_COUNT through production constructor is unavailable`() {
    Settings.Global.putString(context.contentResolver, Settings.Global.BOOT_COUNT, null)

    val status = HomeFeedStatusStore(context).x(100L)

    assertEquals(HomeFeedStorageState.UNAVAILABLE, status.storageState)
    assertEquals(HomeFeedUsageState.UNKNOWN, status.usageState)
  }

  @Test
  fun `malformed BOOT_COUNT through production constructor is unavailable`() {
    Settings.Global.putString(context.contentResolver, Settings.Global.BOOT_COUNT, "not-a-number")

    val status = HomeFeedStatusStore(context).x(100L)

    assertEquals(HomeFeedStorageState.UNAVAILABLE, status.storageState)
    assertEquals(HomeFeedUsageState.UNKNOWN, status.usageState)
  }

  private fun clearPreferences() {
    context.getSharedPreferences("zen_guard_home_feed_status", Context.MODE_PRIVATE)
      .edit()
      .clear()
      .commit()
    context.getSharedPreferences("zen_guard_home_feed_integrity", Context.MODE_PRIVATE)
      .edit()
      .clear()
      .commit()
    Settings.Global.putString(context.contentResolver, Settings.Global.BOOT_COUNT, null)
  }
}
