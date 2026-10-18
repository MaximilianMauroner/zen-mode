package com.maxmauroner.zenguard

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class YouTubeOwnerStatsTest {
  private lateinit var controller: ServiceController<ZenGuardAccessibilityService>
  private lateinit var owner: YouTubeOwner
  private lateinit var store: EnforcementStatsStore

  @Before fun setUp() {
    val context: Context = RuntimeEnvironment.getApplication()
    controller = Robolectric.buildService(ZenGuardAccessibilityService::class.java).create()
    store = EnforcementStatsStore(context)
    owner = YouTubeOwner(controller.get(), InterventionStats(store))
    owner.onServiceConnected(ZenGuardPreferences(context))
  }

  @After fun tearDown() {
    owner.reset()
    controller.destroy()
  }

  @Test fun `failed actions and detector observations never reach the ledger`() {
    assertFalse(recordShortsStats(1, EnforcementStatsOutcome.OBSERVED))
    assertFalse(recordShortsStats(1, EnforcementStatsOutcome.FAILED))

    val snapshot = store.snapshot()
    assertEquals(0L, snapshot.total)
    assertTrue(snapshot.counts.values.all { it == 0L })
  }

  @Test fun `a successful exit without a page never reaches the ledger`() {
    assertFalse(recordShortsStats(null, EnforcementStatsOutcome.SUCCESS))
    assertEquals(0L, store.snapshot().total)
    assertTrue(recordShortsStats(1, EnforcementStatsOutcome.SUCCESS))
    assertEquals(1L, store.snapshot().counts[EnforcementReason.YOUTUBE_SHORTS.key])
  }

  @Test fun `successful exits deduplicate retries but accept a later page`() {
    assertTrue(recordShortsStats(1, EnforcementStatsOutcome.SUCCESS))
    assertFalse(recordShortsStats(1, EnforcementStatsOutcome.SUCCESS))
    assertTrue(recordShortsStats(2, EnforcementStatsOutcome.SUCCESS))

    val snapshot = store.snapshot()
    assertEquals(2L, snapshot.total)
    assertEquals(2L, snapshot.counts[EnforcementReason.YOUTUBE_SHORTS.key])
  }

  @Test fun `an observed Shorts callback does not poison the later successful action`() {
    assertFalse(recordShortsStats(7, EnforcementStatsOutcome.OBSERVED))
    assertTrue(recordShortsStats(7, EnforcementStatsOutcome.SUCCESS))
    assertFalse(recordShortsStats(7, EnforcementStatsOutcome.SUCCESS))

    val snapshot = store.snapshot()
    assertEquals(1L, snapshot.total)
    assertEquals(1L, snapshot.counts[EnforcementReason.YOUTUBE_SHORTS.key])
  }

  @Test fun `reset preserves the current stats key without erasing totals`() {
    assertTrue(recordShortsStats(7, EnforcementStatsOutcome.SUCCESS))

    owner.reset()

    assertFalse(recordShortsStats(7, EnforcementStatsOutcome.SUCCESS))
    assertTrue(recordShortsStats(8, EnforcementStatsOutcome.SUCCESS))
    val snapshot = store.snapshot()
    assertEquals(2L, snapshot.total)
    assertEquals(2L, snapshot.counts[EnforcementReason.YOUTUBE_SHORTS.key])
  }

  private fun recordShortsStats(pageIndex: Int?, outcome: EnforcementStatsOutcome): Boolean =
    YouTubeOwner::class.java.getDeclaredMethod(
      "recordYouTubeShortsStats",
      Int::class.javaObjectType,
      EnforcementStatsOutcome::class.java,
    ).apply { isAccessible = true }.invoke(owner, pageIndex, outcome) as Boolean
}
