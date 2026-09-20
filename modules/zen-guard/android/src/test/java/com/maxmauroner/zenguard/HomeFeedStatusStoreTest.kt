package com.maxmauroner.zenguard

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

  @Test fun `paused state with a resume timestamp becomes unavailable`() {
    val values = validCurrentValues().toMutableMap().apply {
      this[key(HomeFeedStatusKeys.RESUME_AT_ELAPSED)] = 100L
      this[key(HomeFeedStatusKeys.CHECKSUM)] = Long.MIN_VALUE
    }

    assertUnavailable(store(FakePersistence(values), boot = 7L).x(200L))
  }

  @Test fun `unsupported intermediate format becomes unavailable`() {
    val values = validCurrentValues().toMutableMap().apply {
      this[key(HomeFeedStatusKeys.FORMAT_VERSION)] = 2
    }

    assertUnavailable(store(FakePersistence(values), boot = 7L).x(200L))
  }

  @Test fun `truncated legacy usage snapshot becomes unavailable`() {
    val values = validLegacyValues().toMutableMap().apply {
      remove(key(HomeFeedStatusKeys.USED_MS))
    }

    assertUnavailable(store(FakePersistence(values), boot = 7L).x(200L))
  }

  @Test fun `unchanged paused snapshot skips journal commits`() {
    val persistence = FakePersistence()
    val statusStore = store(persistence, boot = 7L)
    val runtime = HomeFeedRuntimeState(
      usedMs = 12_000L,
      blockedUntilElapsedMs = null,
      usageState = HomeFeedUsageState.PAUSED,
      capturedAtElapsedMs = 100L,
    )
    assertTrue(statusStore.recordX(runtime))
    val snapshotCommits = persistence.commitCalls
    val journalCommits = persistence.integrityCommitCalls

    assertTrue(statusStore.recordX(runtime.copy(capturedAtElapsedMs = 200L)))

    assertEquals(snapshotCommits, persistence.commitCalls)
    assertEquals(journalCommits, persistence.integrityCommitCalls)
  }

  @Test fun `reader waits for another store journal transaction`() {
    val persistence = FakePersistence()
    val writer = store(persistence, boot = 7L)
    val reader = store(persistence, boot = 7L)
    assertTrue(writer.recordX(HomeFeedRuntimeState(12_000L, null, capturedAtElapsedMs = 100L)))
    val pendingWritten = CountDownLatch(1)
    val allowWriter = CountDownLatch(1)
    persistence.pauseNextIntegrityCommit = pendingWritten to allowWriter
    val writeThread = Thread {
      writer.recordX(HomeFeedRuntimeState(24_000L, null, capturedAtElapsedMs = 200L))
    }
    val readResult = AtomicReference<HomeFeedStatus?>()
    writeThread.start()
    assertTrue(pendingWritten.await(2, TimeUnit.SECONDS))
    val readThread = Thread { readResult.set(reader.x(300L)) }
    readThread.start()
    Thread.sleep(50L)
    assertTrue("reader must remain serialized behind pending journal write", readThread.isAlive)

    allowWriter.countDown()
    writeThread.join(2_000L)
    readThread.join(2_000L)

    assertEquals(HomeFeedStorageState.AVAILABLE, readResult.get()?.storageState)
    assertEquals(24_000L, readResult.get()?.usedMs)
  }

  @Test fun `failed status write remains unavailable after process death`() {
    val persistence = FakePersistence()
    val writer = store(persistence, boot = 7L)
    val runtime = HomeFeedRuntimeState(
      usedMs = 12_000L,
      blockedUntilElapsedMs = null,
      usageState = HomeFeedUsageState.ACTIVE,
      capturedAtElapsedMs = 100L,
    )
    assertTrue(writer.recordX(runtime))

    persistence.commitResult = false
    assertFalse(writer.recordX(runtime.copy(usedMs = 24_000L, capturedAtElapsedMs = 200L)))
    assertUnavailable(store(persistence.copyForProcessDeath(), boot = 7L).x(300L))
  }

  @Test fun `failed integrity write invalidates stale snapshot after process death`() {
    val persistence = FakePersistence()
    val writer = store(persistence, boot = 7L)
    assertTrue(writer.recordX(
      HomeFeedRuntimeState(
        usedMs = 12_000L,
        blockedUntilElapsedMs = null,
        usageState = HomeFeedUsageState.PAUSED,
        capturedAtElapsedMs = 100L,
      ),
    ))

    persistence.integrityCommitResult = false
    assertFalse(writer.recordX(
      HomeFeedRuntimeState(
        usedMs = 24_000L,
        blockedUntilElapsedMs = null,
        usageState = HomeFeedUsageState.PAUSED,
        capturedAtElapsedMs = 200L,
      ),
    ))

    assertUnavailable(store(persistence.copyForProcessDeath(), boot = 7L).x(300L))
  }

  @Test fun `missing initialized status is unavailable instead of fresh allowance`() {
    val persistence = FakePersistence()
    assertTrue(store(persistence, boot = 7L).recordX(
      HomeFeedRuntimeState(
        usedMs = 20_000L,
        blockedUntilElapsedMs = null,
        usageState = HomeFeedUsageState.PAUSED,
        capturedAtElapsedMs = 100L,
      ),
    ))
    persistence.values.clear()

    assertUnavailable(store(persistence.copyForProcessDeath(), boot = 7L).x(200L))
  }

  @Test fun `a fresh trusted record can recover an initialized missing status`() {
    val persistence = FakePersistence()
    assertTrue(store(persistence, boot = 7L).recordX(
      HomeFeedRuntimeState(
        usedMs = 20_000L,
        blockedUntilElapsedMs = null,
        usageState = HomeFeedUsageState.PAUSED,
        capturedAtElapsedMs = 100L,
      ),
    ))
    persistence.values.clear()

    assertUnavailable(store(persistence.copyForProcessDeath(), boot = 7L).x(200L))
    assertTrue(store(persistence, boot = 7L).recoverX(200L))
    val restored = store(persistence.copyForProcessDeath(), boot = 7L).x(300L)
    assertEquals(HomeFeedStorageState.AVAILABLE, restored.storageState)
    assertEquals(HomeFeedUsageState.PAUSED, restored.usageState)
    assertEquals(0L, restored.usedMs)
  }

  @Test fun `read failure persists unavailable state across process death`() {
    val healthy = FakePersistence()
    assertTrue(store(healthy, boot = 7L).recordX(
      HomeFeedRuntimeState(
        usedMs = 12_000L,
        blockedUntilElapsedMs = null,
        usageState = HomeFeedUsageState.PAUSED,
        capturedAtElapsedMs = 100L,
      ),
    ))
    val broken = FakePersistence(
      initial = healthy.values,
      initialIntegrity = healthy.integrityValues,
      throwOnRead = true,
    )

    assertUnavailable(store(broken, boot = 7L).x(200L))
    assertUnavailable(store(broken.copyForProcessDeath(), boot = 7L).x(300L))
  }

  @Test fun `failed Instagram record is durable and unavailable after process death`() {
    val persistence = FakePersistence(commitResult = false)
    assertFalse(store(persistence, boot = 7L).recordInstagram(
      HomeFeedRuntimeState(
        usedMs = 12_000L,
        blockedUntilElapsedMs = null,
        usageState = HomeFeedUsageState.ACTIVE,
        capturedAtElapsedMs = 100L,
      ),
    ))

    assertUnavailable(store(persistence.copyForProcessDeath(), boot = 7L).instagram(200L))
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
    HomeFeedStatusFailureRegistry.clearForTests()

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
    private val initialIntegrity: Map<String, Any?> = emptyMap(),
    var integrityCommitResult: Boolean = true,
    private val throwOnRead: Boolean = false,
    private val throwOnIntegrityRead: Boolean = false,
  ) : HomeFeedStatusPersistence {
    val values = initial.toMutableMap()
    val integrityValues = initialIntegrity.toMutableMap()
    var commitCalls = 0
    var integrityCommitCalls = 0
    var pauseNextIntegrityCommit: Pair<CountDownLatch, CountDownLatch>? = null

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

    override fun readIntegrity(): Map<String, Any?> {
      if (throwOnIntegrityRead) throw IllegalStateException("integrity read failed")
      return integrityValues.toMap()
    }

    override fun commitIntegrity(values: Map<String, Any?>): Boolean {
      integrityCommitCalls += 1
      pauseNextIntegrityCommit?.also { (entered, proceed) ->
        pauseNextIntegrityCommit = null
        entered.countDown()
        proceed.await(2, TimeUnit.SECONDS)
      }
      if (!integrityCommitResult) return false
      values.forEach { (key, value) ->
        if (value == null) integrityValues.remove(key) else integrityValues[key] = value
      }
      return true
    }

    fun copyForProcessDeath() = FakePersistence(
      initial = values,
      initialIntegrity = integrityValues,
      commitResult = commitResult,
      integrityCommitResult = integrityCommitResult,
    )
  }
}
