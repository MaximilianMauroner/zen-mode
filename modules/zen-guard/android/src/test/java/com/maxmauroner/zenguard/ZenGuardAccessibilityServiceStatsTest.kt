package com.maxmauroner.zenguard

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZenGuardAccessibilityServiceStatsTest {
  @Test
  fun `failed actions and detector observations never reach the ledger`() {
    val fixture = fixture()
    val service = fixture.service

    assertFalse(service.recordYouTubeShortsStats(1, EnforcementStatsOutcome.OBSERVED))
    assertFalse(service.recordYouTubeShortsStats(1, EnforcementStatsOutcome.FAILED))
    assertFalse(service.recordXVideoStats("failed-node-action", EnforcementStatsOutcome.FAILED))
    assertFalse(service.recordXHomeStats(false, false, EnforcementStatsOutcome.SUCCESS))
    assertFalse(service.recordInstagramBlockStats(InstagramBlockReason.REELS_ENTRY, false, false, EnforcementStatsOutcome.SUCCESS))
    assertFalse(service.recordBlockedSiteStats("browser", null, false, EnforcementStatsOutcome.SUCCESS))
    assertFalse(service.recordAppLimitStats("app.one", EnforcementStatsOutcome.FAILED))
    assertFalse(service.recordRollingLimitStats("app.one", EnforcementStatsOutcome.FAILED))
    assertFalse(service.recordTimedVisitStats("app.one", EnforcementStatsOutcome.FAILED))

    val snapshot = fixture.store.snapshot()
    assertEquals(0L, snapshot.total)
    assertTrue(snapshot.counts.values.all { it == 0L })
  }

  @Test
  fun `a real service hook deduplicates retries but accepts a later intervention`() {
    val fixture = fixture()
    val service = fixture.service

    assertTrue(service.recordXVideoStats("scroll-callback-1", EnforcementStatsOutcome.SUCCESS))
    assertFalse(service.recordXVideoStats("scroll-callback-1", EnforcementStatsOutcome.SUCCESS))
    assertTrue(service.recordXVideoStats("scroll-callback-2", EnforcementStatsOutcome.SUCCESS))

    val snapshot = fixture.store.snapshot()
    assertEquals(2L, snapshot.total)
    assertEquals(2L, snapshot.counts[EnforcementReason.X_VIDEOS.key])
  }

  @Test
  fun `production path hooks cover YouTube X Instagram browser and app limits`() {
    val fixture = fixture()
    val service = fixture.service

    assertTrue(service.recordYouTubeShortsStats(1, EnforcementStatsOutcome.SUCCESS))
    assertFalse(service.recordYouTubeShortsStats(1, EnforcementStatsOutcome.SUCCESS))
    assertTrue(service.recordYouTubeShortsStats(2, EnforcementStatsOutcome.SUCCESS))
    assertTrue(
      service.recordXHomeStats(
        wasShowing = false,
        overlayAttached = true,
        outcome = EnforcementStatsOutcome.SUCCESS,
      ),
    )
    assertFalse(
      service.recordXHomeStats(
        wasShowing = true,
        overlayAttached = true,
        outcome = EnforcementStatsOutcome.SUCCESS,
      ),
    )
    assertTrue(
      service.recordInstagramBlockStats(
        reason = InstagramBlockReason.REELS_ENTRY,
        wasShowing = false,
        overlayAttached = true,
        outcome = EnforcementStatsOutcome.SUCCESS,
      ),
    )
    assertFalse(
      service.recordInstagramBlockStats(
        reason = InstagramBlockReason.REELS_ENTRY,
        wasShowing = true,
        overlayAttached = true,
        outcome = EnforcementStatsOutcome.SUCCESS,
      ),
    )
    assertTrue(
      service.recordInstagramBlockStats(
        reason = InstagramBlockReason.HOME_LIMIT,
        wasShowing = false,
        overlayAttached = true,
        outcome = EnforcementStatsOutcome.SUCCESS,
      ),
    )
    assertFalse(
      service.recordInstagramBlockStats(
        reason = InstagramBlockReason.HOME_LIMIT,
        wasShowing = false,
        overlayAttached = true,
        outcome = EnforcementStatsOutcome.SUCCESS,
      ),
    )
    assertTrue(
      service.recordInstagramBlockStats(
        reason = InstagramBlockReason.EXPLORE,
        wasShowing = false,
        overlayAttached = true,
        outcome = EnforcementStatsOutcome.SUCCESS,
      ),
    )
    assertTrue(service.recordXVideoStats("video-callback-1", EnforcementStatsOutcome.SUCCESS))
    assertFalse(service.recordXVideoStats("video-callback-1", EnforcementStatsOutcome.SUCCESS))
    assertTrue(
      service.recordBlockedSiteStats(
        browserPackage = "com.android.chrome",
        previousPackage = null,
        overlayAttached = true,
        outcome = EnforcementStatsOutcome.SUCCESS,
      ),
    )
    assertFalse(
      service.recordBlockedSiteStats(
        browserPackage = "com.android.chrome",
        previousPackage = "com.android.chrome",
        overlayAttached = true,
        outcome = EnforcementStatsOutcome.SUCCESS,
      ),
    )
    assertTrue(service.recordAppLimitStats("app.one", EnforcementStatsOutcome.SUCCESS))
    assertFalse(service.recordAppLimitStats("app.one", EnforcementStatsOutcome.SUCCESS))
    assertTrue(service.recordAppLimitStats("app.two", EnforcementStatsOutcome.SUCCESS))

    val snapshot = fixture.store.snapshot()
    assertEquals(10L, snapshot.total)
    assertEquals(2L, snapshot.counts[EnforcementReason.YOUTUBE_SHORTS.key])
    assertEquals(1L, snapshot.counts[EnforcementReason.X_HOME.key])
    assertEquals(1L, snapshot.counts[EnforcementReason.INSTAGRAM_REELS.key])
    assertEquals(1L, snapshot.counts[EnforcementReason.INSTAGRAM_HOME.key])
    assertEquals(1L, snapshot.counts[EnforcementReason.INSTAGRAM_EXPLORE.key])
    assertEquals(1L, snapshot.counts[EnforcementReason.X_VIDEOS.key])
    assertEquals(1L, snapshot.counts[EnforcementReason.BLOCKED_SITE.key])
    assertEquals(2L, snapshot.counts[EnforcementReason.APP_LIMIT.key])
  }

  @Test
  fun `rolling limits and timed visits count distinct successful interventions`() {
    val fixture = fixture()
    val service = fixture.service

    assertTrue(service.recordRollingLimitStats("app.one", EnforcementStatsOutcome.SUCCESS))
    assertFalse(service.recordRollingLimitStats("app.one", EnforcementStatsOutcome.SUCCESS))
    assertTrue(service.recordRollingLimitStats("app.two", EnforcementStatsOutcome.SUCCESS))
    assertTrue(service.recordTimedVisitStats("app.one", EnforcementStatsOutcome.SUCCESS))
    assertFalse(service.recordTimedVisitStats("app.one", EnforcementStatsOutcome.SUCCESS))
    assertTrue(service.recordTimedVisitStats("app.two", EnforcementStatsOutcome.SUCCESS))

    val snapshot = fixture.store.snapshot()
    assertEquals(4L, snapshot.total)
    assertEquals(2L, snapshot.counts[EnforcementReason.ROLLING_LIMIT.key])
    assertEquals(2L, snapshot.counts[EnforcementReason.TIMED_VISIT.key])
  }

  @Test
  fun `an observed Shorts callback does not poison the later successful action`() {
    val fixture = fixture()
    val service = fixture.service

    assertFalse(service.recordYouTubeShortsStats(7, EnforcementStatsOutcome.OBSERVED))
    assertTrue(service.recordYouTubeShortsStats(7, EnforcementStatsOutcome.SUCCESS))
    assertFalse(service.recordYouTubeShortsStats(7, EnforcementStatsOutcome.SUCCESS))

    val snapshot = fixture.store.snapshot()
    assertEquals(1L, snapshot.total)
    assertEquals(1L, snapshot.counts[EnforcementReason.YOUTUBE_SHORTS.key])
  }

  @Test
  fun `Instagram dismissal resets the matching dedupe identity`() {
    val fixture = fixture()
    val service = fixture.service

    assertTrue(service.recordInstagramBlockStats(InstagramBlockReason.EXPLORE, false, true, EnforcementStatsOutcome.SUCCESS))
    assertFalse(service.recordInstagramBlockStats(InstagramBlockReason.EXPLORE, false, true, EnforcementStatsOutcome.SUCCESS))
    service.resetInstagramStatsDedupe(InstagramBlockReason.EXPLORE)
    assertTrue(service.recordInstagramBlockStats(InstagramBlockReason.EXPLORE, false, true, EnforcementStatsOutcome.SUCCESS))

    assertEquals(2L, fixture.store.snapshot().counts[EnforcementReason.INSTAGRAM_EXPLORE.key])
  }

  @Test
  fun `X Home lockout identity survives service reconnection and unrelated stats`() {
    val preferences = TestPreferences()
    val firstStore = EnforcementStatsStore(preferences)
    val firstService = ZenGuardAccessibilityService(firstStore)
    assertTrue(firstService.recordXHomeStats(false, true, EnforcementStatsOutcome.SUCCESS, 9_000L))
    assertTrue(firstService.recordXVideoStats("video:1", EnforcementStatsOutcome.SUCCESS))

    val secondStore = EnforcementStatsStore(preferences)
    val reconnectedService = ZenGuardAccessibilityService(secondStore)
    assertFalse(reconnectedService.recordXHomeStats(false, true, EnforcementStatsOutcome.SUCCESS, 9_000L))

    val snapshot = secondStore.snapshot()
    assertEquals(2L, snapshot.total)
    assertEquals(1L, snapshot.counts[EnforcementReason.X_HOME.key])
  }

  private data class Fixture(
    val service: ZenGuardAccessibilityService,
    val store: EnforcementStatsStore,
  )

  private fun fixture(): Fixture {
    val store = EnforcementStatsStore(TestPreferences())
    return Fixture(ZenGuardAccessibilityService(store), store)
  }

  private class TestPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = values[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class Editor : SharedPreferences.Editor {
      private val changes = mutableMapOf<String, Any?>()
      private var clearRequested = false

      override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)
      override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = put(key, values)
      override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)
      override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)
      override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)
      override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = put(key, value)

      override fun remove(key: String?): SharedPreferences.Editor {
        changes[key.orEmpty()] = null
        return this
      }

      override fun clear(): SharedPreferences.Editor {
        clearRequested = true
        return this
      }

      override fun commit(): Boolean {
        if (clearRequested) values.clear()
        changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        return true
      }

      override fun apply() {
        commit()
      }

      private fun put(key: String?, value: Any?): SharedPreferences.Editor {
        changes[key.orEmpty()] = value
        return this
      }
    }
  }
}
