package com.maxmauroner.zenguard

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class DeadlineTaskTest {
  private lateinit var handler: RecordingHandler
  private var start = 0L
  private val decisionTimes = mutableListOf<Long>()

  /** Deep sleep moves elapsed time ahead of Handler uptime. Separate rounding can leave it behind. */
  private var elapsedSkewMs = 0L

  @Before fun setUp() {
    handler = RecordingHandler()
    start = elapsedNow()
  }

  @Test fun `each retry counts from its decision in elapsed time until Cancel`() {
    val decisions = ArrayDeque(listOf(
      DeadlineDecision.RetryAfter(1_000),
      DeadlineDecision.RetryAfter(250),
      DeadlineDecision.Cancel,
    ))
    val task = deadlineTask { decisions.removeFirst() }
    task.scheduleAt(start + 500)
    elapsedSkewMs = 300

    advance(60_000)

    assertEquals(listOf(start + 800, start + 1_800, start + 2_050), decisionTimes)
    assertFalse(task.isPending)
    assertNull(nextPostUptimeMs())
  }

  @Test fun `a new schedule replaces the old post and the old captured dispatch never decides`() {
    val task = deadlineTask { DeadlineDecision.Complete }
    task.scheduleAt(start + 1_000)
    val oldDispatch = handler.posted.last()
    task.scheduleAt(start + 3_000)
    assertEquals(start + 3_000, nextPostUptimeMs())

    oldDispatch.run()
    advance(1_000)
    oldDispatch.run()
    assertEquals(emptyList<Long>(), decisionTimes)
    assertEquals(start + 3_000, nextPostUptimeMs())

    advance(60_000)
    assertEquals(listOf(start + 3_000), decisionTimes)
    assertFalse(task.isPending)
    assertNull(nextPostUptimeMs())
  }

  @Test fun `a schedule made inside the decision survives its Complete`() {
    lateinit var task: DeadlineTask
    task = deadlineTask {
      if (decisionTimes.size == 1) task.scheduleAt(start + 3_000)
      DeadlineDecision.Complete
    }
    task.scheduleAt(start + 1_000)

    advance(1_000)
    assertEquals(listOf(start + 1_000), decisionTimes)
    assertTrue(task.isPending)
    assertEquals(start + 3_000, nextPostUptimeMs())

    advance(60_000)
    assertEquals(listOf(start + 1_000, start + 3_000), decisionTimes)
    assertFalse(task.isPending)
    assertNull(nextPostUptimeMs())
  }

  @Test fun `a cancel made inside the decision overrides its RetryAfter`() {
    lateinit var task: DeadlineTask
    task = deadlineTask {
      task.cancel()
      DeadlineDecision.RetryAfter(1_000)
    }
    task.scheduleAt(start + 1_000)

    advance(60_000)

    assertEquals(listOf(start + 1_000), decisionTimes)
    assertFalse(task.isPending)
    assertNull(nextPostUptimeMs())
  }

  @Test fun `a dispatch one elapsed millisecond early waits for the due time before deciding`() {
    val task = deadlineTask { DeadlineDecision.Complete }
    task.scheduleAt(start + 1_000)
    elapsedSkewMs = -1

    advance(1_000)
    assertEquals(emptyList<Long>(), decisionTimes)
    assertTrue(task.isPending)
    assertEquals(start + 1_001, nextPostUptimeMs())

    advance(1)
    assertEquals(listOf(start + 1_000), decisionTimes)
    assertFalse(task.isPending)
    assertNull(nextPostUptimeMs())
  }

  @Test fun `cancelling one task keeps another task's post on the shared Handler`() {
    val cancelled = deadlineTask { DeadlineDecision.Complete }
    val kept = deadlineTask { DeadlineDecision.Complete }
    cancelled.scheduleAt(start + 1_000)
    kept.scheduleAt(start + 1_500)

    cancelled.cancel()
    assertFalse(cancelled.isPending)
    assertTrue(kept.isPending)
    assertEquals(start + 1_500, nextPostUptimeMs())

    advance(60_000)
    assertEquals(listOf(start + 1_500), decisionTimes)
    assertFalse(kept.isPending)
    assertNull(nextPostUptimeMs())
  }

  private fun deadlineTask(decide: () -> DeadlineDecision): DeadlineTask =
    DeadlineTask(handler, ::elapsedNow) {
      decisionTimes += elapsedNow()
      decide()
    }

  private fun elapsedNow(): Long = SystemClock.uptimeMillis() + elapsedSkewMs

  private fun advance(ms: Long) {
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
  }

  private fun nextPostUptimeMs(): Long? =
    shadowOf(Looper.getMainLooper()).nextScheduledTaskTime.takeUnless { it.isZero }?.toMillis()

  private class RecordingHandler : Handler(Looper.getMainLooper()) {
    val posted = mutableListOf<Runnable>()

    override fun sendMessageAtTime(msg: Message, uptimeMillis: Long): Boolean {
      msg.callback?.let { posted.add(it) }
      return super.sendMessageAtTime(msg, uptimeMillis)
    }
  }
}
