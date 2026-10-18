package com.maxmauroner.zenguard

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZenGuardAccessibilityServiceStatsTest {
  @Test
  fun `failed actions and unattached overlays never reach the ledger`() {
    val fixture = fixture()
    val service = fixture.service

    assertFalse(service.recordXVideoStats("failed-node-action", EnforcementStatsOutcome.FAILED))
    assertFalse(service.recordXHomeStats(false, false, EnforcementStatsOutcome.SUCCESS))
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
  fun `production path hooks cover X and app limits`() {
    val fixture = fixture()
    val service = fixture.service

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
    assertTrue(service.recordXVideoStats("video-callback-1", EnforcementStatsOutcome.SUCCESS))
    assertFalse(service.recordXVideoStats("video-callback-1", EnforcementStatsOutcome.SUCCESS))
    assertTrue(service.recordAppLimitStats("app.one", EnforcementStatsOutcome.SUCCESS))
    assertFalse(service.recordAppLimitStats("app.one", EnforcementStatsOutcome.SUCCESS))
    assertTrue(service.recordAppLimitStats("app.two", EnforcementStatsOutcome.SUCCESS))

    val snapshot = fixture.store.snapshot()
    assertEquals(4L, snapshot.total)
    assertEquals(1L, snapshot.counts[EnforcementReason.X_HOME.key])
    assertEquals(1L, snapshot.counts[EnforcementReason.X_VIDEOS.key])
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

  @Test
  fun `conditional key clearing preserves a match and does not remember the new key`() {
    val store = EnforcementStatsStore(TestPreferences())
    val stats = InterventionStats(store)

    assertTrue(stats.record(EnforcementReason.APP_LIMIT, EnforcementStatsOutcome.SUCCESS, "app.one"))
    assertTrue(stats.record(EnforcementReason.TIMED_VISIT, EnforcementStatsOutcome.SUCCESS, "app.one"))

    stats.clearKeyIfDifferent(EnforcementReason.APP_LIMIT, "app.one")
    assertFalse(stats.record(EnforcementReason.APP_LIMIT, EnforcementStatsOutcome.SUCCESS, "app.one"))

    stats.clearKeyIfDifferent(EnforcementReason.APP_LIMIT, "app.two")
    assertTrue(stats.record(EnforcementReason.APP_LIMIT, EnforcementStatsOutcome.SUCCESS, "app.two"))
    assertFalse(stats.record(EnforcementReason.TIMED_VISIT, EnforcementStatsOutcome.SUCCESS, "app.one"))

    val snapshot = store.snapshot()
    assertEquals(3L, snapshot.total)
    assertEquals(2L, snapshot.counts[EnforcementReason.APP_LIMIT.key])
    assertEquals(1L, snapshot.counts[EnforcementReason.TIMED_VISIT.key])
  }

  @Test
  fun `reset clears one reason and clear permits later interventions without erasing totals`() {
    val store = EnforcementStatsStore(TestPreferences())
    val stats = InterventionStats(store)

    assertTrue(stats.record(EnforcementReason.APP_LIMIT, EnforcementStatsOutcome.SUCCESS, "app.one"))
    assertTrue(stats.record(EnforcementReason.TIMED_VISIT, EnforcementStatsOutcome.SUCCESS, "app.one"))

    stats.reset(EnforcementReason.APP_LIMIT)
    assertTrue(stats.record(EnforcementReason.APP_LIMIT, EnforcementStatsOutcome.SUCCESS, "app.one"))
    assertFalse(stats.record(EnforcementReason.TIMED_VISIT, EnforcementStatsOutcome.SUCCESS, "app.one"))
    assertEquals(3L, store.snapshot().total)

    stats.clear()
    assertTrue(stats.record(EnforcementReason.APP_LIMIT, EnforcementStatsOutcome.SUCCESS, "app.one"))
    assertTrue(stats.record(EnforcementReason.TIMED_VISIT, EnforcementStatsOutcome.SUCCESS, "app.one"))

    val snapshot = store.snapshot()
    assertEquals(5L, snapshot.total)
    assertEquals(3L, snapshot.counts[EnforcementReason.APP_LIMIT.key])
    assertEquals(2L, snapshot.counts[EnforcementReason.TIMED_VISIT.key])
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
