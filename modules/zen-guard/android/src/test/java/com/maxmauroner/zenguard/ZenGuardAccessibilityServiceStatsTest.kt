package com.maxmauroner.zenguard

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZenGuardAccessibilityServiceStatsTest {
  @Test
  fun `failed app actions never reach the ledger`() {
    val fixture = fixture()
    val stats = fixture.stats

    assertFalse(stats.record(EnforcementReason.APP_LIMIT, EnforcementStatsOutcome.FAILED, "app.one"))
    assertFalse(stats.record(EnforcementReason.ROLLING_LIMIT, EnforcementStatsOutcome.FAILED, "app.one"))
    assertFalse(stats.record(EnforcementReason.TIMED_VISIT, EnforcementStatsOutcome.FAILED, "app.one"))

    val snapshot = fixture.store.snapshot()
    assertEquals(0L, snapshot.total)
    assertTrue(snapshot.counts.values.all { it == 0L })
  }

  @Test
  fun `rolling limits and timed visits count distinct successful interventions`() {
    val fixture = fixture()
    val stats = fixture.stats

    assertTrue(stats.record(EnforcementReason.ROLLING_LIMIT, EnforcementStatsOutcome.SUCCESS, "app.one"))
    assertFalse(stats.record(EnforcementReason.ROLLING_LIMIT, EnforcementStatsOutcome.SUCCESS, "app.one"))
    assertTrue(stats.record(EnforcementReason.ROLLING_LIMIT, EnforcementStatsOutcome.SUCCESS, "app.two"))
    assertTrue(stats.record(EnforcementReason.TIMED_VISIT, EnforcementStatsOutcome.SUCCESS, "app.one"))
    assertFalse(stats.record(EnforcementReason.TIMED_VISIT, EnforcementStatsOutcome.SUCCESS, "app.one"))
    assertTrue(stats.record(EnforcementReason.TIMED_VISIT, EnforcementStatsOutcome.SUCCESS, "app.two"))

    val snapshot = fixture.store.snapshot()
    assertEquals(4L, snapshot.total)
    assertEquals(2L, snapshot.counts[EnforcementReason.ROLLING_LIMIT.key])
    assertEquals(2L, snapshot.counts[EnforcementReason.TIMED_VISIT.key])
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
    val stats: InterventionStats,
    val store: EnforcementStatsStore,
  )

  private fun fixture(): Fixture {
    val store = EnforcementStatsStore(TestPreferences())
    return Fixture(InterventionStats(store), store)
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
