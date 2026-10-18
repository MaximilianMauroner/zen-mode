package com.maxmauroner.zenguard

import android.os.Handler
import android.os.SystemClock

internal sealed interface DeadlineDecision {
  data object Complete : DeadlineDecision
  data object Cancel : DeadlineDecision
  data class RetryAfter(val delayMs: Long) : DeadlineDecision
}

internal class DeadlineTask(
  private val handler: Handler,
  private val elapsedNow: () -> Long = SystemClock::elapsedRealtime,
  private val decide: () -> DeadlineDecision,
) {
  private var current: Post? = null

  val isPending: Boolean
    get() = current != null

  fun scheduleAt(elapsedMs: Long) {
    cancel()
    val post = Post(elapsedMs)
    current = post
    handler.postDelayed(post, elapsedMs - elapsedNow())
  }

  fun cancel() {
    current?.let { handler.removeCallbacks(it) }
    current = null
  }

  private inner class Post(private val dueElapsedMs: Long) : Runnable {
    override fun run() {
      if (current !== this) return
      // Handler delays count uptime, which can reach the delay before elapsed time reaches the due time.
      if (elapsedNow() < dueElapsedMs) {
        scheduleAt(dueElapsedMs)
        return
      }
      val decision = decide()
      if (current !== this) return
      when (decision) {
        DeadlineDecision.Complete, DeadlineDecision.Cancel -> current = null
        is DeadlineDecision.RetryAfter -> scheduleAt(elapsedNow() + decision.delayMs)
      }
    }
  }
}
