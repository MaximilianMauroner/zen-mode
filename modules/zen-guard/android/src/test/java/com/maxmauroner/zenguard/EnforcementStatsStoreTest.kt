package com.maxmauroner.zenguard

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnforcementStatsStoreTest {
  @Test
  fun `records successful categories and ignores the same action key`() {
    val preferences = MemoryPreferences()
    val store = EnforcementStatsStore(preferences)

    store.record(EnforcementReason.YOUTUBE_SHORTS, "shorts:1", nowMs = 1_000L)
    store.record(EnforcementReason.YOUTUBE_SHORTS, "shorts:1", nowMs = 2_000L)
    store.record(EnforcementReason.YOUTUBE_SHORTS, "shorts:2", nowMs = 3_000L)
    store.record(EnforcementReason.X_HOME, "x-home:1", nowMs = 4_000L)

    val snapshot = store.snapshot()
    assertEquals(3L, snapshot.total)
    assertEquals(2L, snapshot.counts[EnforcementReason.YOUTUBE_SHORTS.key])
    assertEquals(1L, snapshot.counts[EnforcementReason.X_HOME.key])
    assertEquals(4_000L, snapshot.lastEventAt)
  }

  @Test
  fun `missing schema migrates in place without clearing legacy aggregate keys`() {
    val preferences = MemoryPreferences()
    preferences.edit()
      .putLong("total", 4L)
      .putLong(EnforcementReason.INSTAGRAM_REELS.key, 4L)
      .commit()

    val snapshot = EnforcementStatsStore(preferences).snapshot()

    assertEquals(4L, snapshot.total)
    assertEquals(4L, snapshot.counts[EnforcementReason.INSTAGRAM_REELS.key])
    assertEquals(EnforcementStatsStore.CURRENT_SCHEMA_VERSION, preferences.getInt("schema_version", 0))
  }

  @Test
  fun `a new store sees persisted counts and app data clear resets them`() {
    val preferences = MemoryPreferences()
    EnforcementStatsStore(preferences).record(EnforcementReason.BLOCKED_SITE, "site:1", nowMs = 7_000L)

    assertEquals(1L, EnforcementStatsStore(preferences).snapshot().total)

    preferences.edit().clear().commit()

    assertEquals(0L, EnforcementStatsStore(preferences).snapshot().total)
  }

  @Test
  fun `future schema is not reinterpreted or overwritten`() {
    val preferences = MemoryPreferences()
    preferences.edit().putInt("schema_version", EnforcementStatsStore.CURRENT_SCHEMA_VERSION + 1).putLong("total", 9L).commit()

    val store = EnforcementStatsStore(preferences)
    assertEquals(0L, store.snapshot().total)
    store.record(EnforcementReason.X_VIDEOS, "x-video:1", nowMs = 9_000L)

    assertEquals(9L, preferences.getLong("total", 0L))
    assertTrue(store.snapshot().counts.values.all { it == 0L })
  }

  @Test
  fun `a failed preference write is ignored without throwing`() {
    val preferences = MemoryPreferences(commitSucceeds = false)

    EnforcementStatsStore(preferences).record(EnforcementReason.X_HOME, "x-home:1", nowMs = 10_000L)

    assertEquals(0L, EnforcementStatsStore(preferences).snapshot().total)
  }

  @Test
  fun `snapshot uses one coherent preferences image across store instances`() {
    val preferences = MemoryPreferences()
    EnforcementStatsStore(preferences).record(EnforcementReason.X_HOME, "lockout:1", nowMs = 10_000L)
    preferences.getAllCalls = 0
    preferences.getLongCalls = 0

    val snapshot = EnforcementStatsStore(preferences).snapshot()

    assertEquals(1, preferences.getAllCalls)
    assertEquals(0, preferences.getLongCalls)
    assertEquals(snapshot.total, snapshot.counts.values.sum())
    assertEquals(10_000L, snapshot.lastEventAt)
  }

  private class MemoryPreferences(private val commitSucceeds: Boolean = true) : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    var getAllCalls = 0
    var getLongCalls = 0

    override fun getAll(): MutableMap<String, *> {
      getAllCalls += 1
      return values.toMutableMap()
    }
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = values[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long {
      getLongCalls += 1
      return values[key] as? Long ?: defValue
    }
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = MemoryEditor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class MemoryEditor : SharedPreferences.Editor {
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
        if (!commitSucceeds) return false
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
