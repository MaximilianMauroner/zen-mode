package com.maxmauroner.zenguard

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HomeFeedStatusStoreTest {
  @Before
  fun clearFailureRegistry() {
    HomeFeedStatusFailureRegistry.clearForTests()
  }

  @After
  fun clearFailureRegistryAfterTest() {
    HomeFeedStatusFailureRegistry.clearForTests()
  }

  @Test fun `wrong preference types become unavailable`() {
    val persistence = FakePersistence(
      mapOf(
        key(HomeFeedStatusKeys.FORMAT_VERSION) to HomeFeedStatusCodec.CURRENT_FORMAT_VERSION,
        key(HomeFeedStatusKeys.BOOT_COUNT) to 7L,
        key(HomeFeedStatusKeys.USED_MS) to "not a duration",
        key(HomeFeedStatusKeys.USAGE_STATE) to HomeFeedStatusCodec.STATE_PAUSED,
        key(HomeFeedStatusKeys.LOCKOUT_STATE) to HomeFeedStatusCodec.LOCKOUT_NONE,
        key(HomeFeedStatusKeys.RESUME_AT_ELAPSED) to 0L,
        key(HomeFeedStatusKeys.BLOCKED_UNTIL_ELAPSED) to 0L,
      ),
    )

    val status = store(persistence, boot = 7L).x(100L)

    assertUnavailable(status)
  }

  @Test fun `negative and overflowing persisted numbers become unavailable`() {
    val negative = validCurrentValues().toMutableMap().apply {
      this[key(HomeFeedStatusKeys.USED_MS)] = -1L
    }
    assertUnavailable(store(FakePersistence(negative), boot = 7L).x(100L))

    val overflowing = validCurrentValues().toMutableMap().apply {
      this[key(HomeFeedStatusKeys.BLOCKED_UNTIL_ELAPSED)] = Long.MAX_VALUE
      this[key(HomeFeedStatusKeys.LOCKOUT_STATE)] = HomeFeedStatusCodec.LOCKOUT_ACTIVE
    }
    assertUnavailable(store(FakePersistence(overflowing), boot = 7L).x(100L))
  }

  @Test fun `failed commit latches unavailable state instead of continuing`() {
    val persistence = FakePersistence(commitResult = false)
    val store = store(persistence, boot = 7L)
    val runtime = HomeFeedRuntimeState(
      usedMs = 12_000L,
      blockedUntilElapsedMs = null,
      usageState = HomeFeedUsageState.ACTIVE,
      capturedAtElapsedMs = 100L,
    )

    assertFalse(store.recordX(runtime))
    assertUnavailable(store.x(200L))
    assertUnavailable(store(FakePersistence(persistence.values), boot = 7L).x(200L))
    assertFalse(store.recordX(runtime))
    assertEquals(1, persistence.commitCalls)
  }

  @Test fun `read failure is explicit and fail closed`() {
    val persistence = FakePersistence(throwOnRead = true)

    val status = store(persistence, boot = 7L).x(100L)

    assertUnavailable(status)
  }

  @Test fun `legacy scalar state migrates without restoring active usage`() {
    val persistence = FakePersistence(
      mapOf(
        key(HomeFeedStatusKeys.BOOT_COUNT) to 7,
        key(HomeFeedStatusKeys.USED_MS) to 12_000L,
        key(HomeFeedStatusKeys.USAGE_STATE) to HomeFeedStatusCodec.STATE_ACTIVE,
        key(HomeFeedStatusKeys.RESUME_AT_ELAPSED) to 100L,
        key(HomeFeedStatusKeys.BLOCKED_UNTIL_ELAPSED) to 0L,
        key(HomeFeedStatusKeys.LEGACY_BLOCKED_UNTIL_WALL) to 9_999_999L,
      ),
    )

    val status = store(persistence, boot = 7L).x(200L)

    assertEquals(HomeFeedUsageState.UNKNOWN, status.usageState)
    assertEquals(HomeFeedStorageState.AVAILABLE, status.storageState)
    assertEquals(HomeFeedStatusCodec.CURRENT_FORMAT_VERSION, persistence.values[key(HomeFeedStatusKeys.FORMAT_VERSION)])
    assertFalse(persistence.values.containsKey(key(HomeFeedStatusKeys.LEGACY_BLOCKED_UNTIL_WALL)))
  }

  @Test fun `missing boot count is unavailable and changed boot is unknown`() {
    assertUnavailable(store(FakePersistence(), boot = null).x(100L))

    val persistence = FakePersistence()
    val writer = store(persistence, boot = 7L)
    assertTrue(writer.recordX(
      HomeFeedRuntimeState(
        usedMs = 20_000L,
        blockedUntilElapsedMs = null,
        usageState = HomeFeedUsageState.ACTIVE,
        capturedAtElapsedMs = 100L,
      ),
    ))

    val sameBootReconnect = store(persistence, boot = 7L).x(600_000L)
    assertEquals(HomeFeedUsageState.UNKNOWN, sameBootReconnect.usageState)
    assertEquals(20_000L, sameBootReconnect.usedMs)

    val afterReboot = store(persistence, boot = 8L).x(100L)
    assertEquals(HomeFeedUsageState.UNKNOWN, afterReboot.usageState)
    assertEquals(HomeFeedLockoutState.NONE, afterReboot.lockoutState)
    assertEquals(HomeFeedStorageState.AVAILABLE, afterReboot.storageState)
  }

  @Test fun `reboot lockout is unknown for both forward and backward wall clock values`() {
    fun decodeWithLegacyWall(wallMs: Long): HomeFeedStatus {
      val values = validLegacyValues().toMutableMap().apply {
        this[key(HomeFeedStatusKeys.BLOCKED_UNTIL_ELAPSED)] = 1_000L
        this[key(HomeFeedStatusKeys.LEGACY_BLOCKED_UNTIL_WALL)] = wallMs
      }
      return store(FakePersistence(values), boot = 8L).x(500L)
    }

    val forward = decodeWithLegacyWall(Long.MAX_VALUE / 2L)
    val backward = decodeWithLegacyWall(1L)

    assertEquals(HomeFeedLockoutState.UNKNOWN, forward.lockoutState)
    assertEquals(HomeFeedUsageState.UNKNOWN, forward.usageState)
    assertEquals(forward.lockoutState, backward.lockoutState)
    assertEquals(forward.usageState, backward.usageState)
    assertEquals(null, forward.blockedUntilElapsedMs)
  }

  @Test fun `same boot lockout uses monotonic deadline and expiry persists clean state`() {
    val persistence = FakePersistence()
    val writer = store(persistence, boot = 7L)
    assertTrue(writer.recordX(
      HomeFeedRuntimeState(
        usedMs = 60_000L,
        blockedUntilElapsedMs = 3_660_000L,
        usageState = HomeFeedUsageState.PAUSED,
        lockoutState = HomeFeedLockoutState.ACTIVE,
        capturedAtElapsedMs = 60_000L,
      ),
    ))

    val afterExpiry = store(persistence, boot = 7L).x(3_660_000L)
    assertEquals(0L, afterExpiry.usedMs)
    assertEquals(null, afterExpiry.blockedUntilElapsedMs)
    assertEquals(HomeFeedLockoutState.NONE, afterExpiry.lockoutState)

    val restored = XGuardStateMachine()
    restored.restore(
      HomeFeedRuntimeState(
        usedMs = afterExpiry.usedMs,
        blockedUntilElapsedMs = afterExpiry.blockedUntilElapsedMs,
        usageState = afterExpiry.usageState,
        lockoutState = afterExpiry.lockoutState,
        storageState = afterExpiry.storageState,
        capturedAtElapsedMs = 3_660_000L,
      ),
      3_660_000L,
    )
    assertEquals(XAction.NONE, restored.next(XSurface.HOME, 3_660_001L, XSettings(homeAllowanceMs = 60_000L)))
  }

  private fun store(persistence: FakePersistence, boot: Long?, now: Long = 100L) =
    HomeFeedStatusStore(persistence, { boot }, { now })

  private fun validCurrentValues(): Map<String, Any?> =
    HomeFeedStatusCodec.encode(
      "x",
      HomeFeedRuntimeState(
        usedMs = 10_000L,
        blockedUntilElapsedMs = null,
        usageState = HomeFeedUsageState.PAUSED,
        capturedAtElapsedMs = 100L,
      ),
      7L,
      100L,
    )!!.filterValues { it != null }

  private fun validLegacyValues(): Map<String, Any?> = mapOf(
    key(HomeFeedStatusKeys.BOOT_COUNT) to 7,
    key(HomeFeedStatusKeys.USED_MS) to 60_000L,
    key(HomeFeedStatusKeys.USAGE_STATE) to HomeFeedStatusCodec.STATE_PAUSED,
    key(HomeFeedStatusKeys.RESUME_AT_ELAPSED) to 0L,
    key(HomeFeedStatusKeys.BLOCKED_UNTIL_ELAPSED) to 0L,
  )

  private fun key(suffix: String): String = HomeFeedStatusKeys.key("x", suffix)

  private fun assertUnavailable(status: HomeFeedStatus) {
    assertEquals(HomeFeedStorageState.UNAVAILABLE, status.storageState)
    assertEquals(HomeFeedUsageState.UNKNOWN, status.usageState)
    assertEquals(HomeFeedLockoutState.UNKNOWN, status.lockoutState)
  }

  private class FakePersistence(
    initial: Map<String, Any?> = emptyMap(),
    var commitResult: Boolean = true,
    private val throwOnRead: Boolean = false,
  ) : HomeFeedStatusPersistence {
    val values = initial.toMutableMap()
    var commitCalls = 0

    override fun readAll(): Map<String, Any?> {
      if (throwOnRead) throw IllegalStateException("read failed")
      return values.toMap()
    }

    override fun commit(values: Map<String, Any?>): Boolean {
      commitCalls += 1
      if (!commitResult) return false
      values.forEach { (key, value) ->
        if (value == null) this.values.remove(key) else this.values[key] = value
      }
      return true
    }
  }
}
