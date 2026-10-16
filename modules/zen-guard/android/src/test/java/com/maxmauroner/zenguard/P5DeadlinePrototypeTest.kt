package com.maxmauroner.zenguard

import org.junit.Test

class P5DeadlinePrototypeTest {
  private sealed interface Decision {
    data object Complete : Decision
    data class RetryAfter(val delayMs: Long) : Decision
  }

  private data class Post(val dueMs: Long, val callback: Runnable)

  private class Queue {
    var elapsedMs = 0L
    val pending = mutableListOf<Post>()

    fun dispatch(post: Post) {
      pending.remove(post)
      post.callback.run()
    }
  }

  private class Owner(
    private val queue: Queue,
    private val checkAfterDecision: Boolean,
    private val decide: () -> Decision,
  ) {
    private var generation = 0L
    private var pending: Post? = null
    var decideCount = 0
      private set
    val dueMs: Long? get() = pending?.dueMs

    fun cancel() {
      generation++
      pending?.let { queue.pending.remove(it) }
      pending = null
    }

    fun scheduleAt(dueMs: Long) {
      cancel()
      val capturedGeneration = generation
      val post = Post(dueMs, Runnable {
        if (capturedGeneration != generation) return@Runnable
        pending = null
        if (queue.elapsedMs < dueMs) {
          scheduleAt(dueMs)
          return@Runnable
        }
        decideCount++
        val decision = decide()
        if (checkAfterDecision && capturedGeneration != generation) return@Runnable
        when (decision) {
          Decision.Complete -> cancel()
          is Decision.RetryAfter -> scheduleAt(queue.elapsedMs + decision.delayMs)
        }
      })
      pending = post
      queue.pending.add(post)
    }
  }

  @Test fun `P5 observe returned decisions and captured callbacks`() {
    for (checked in listOf(false, true)) {
      for (scenario in listOf("schedule_then_complete", "cancel_then_retry", "old_callback_after_replacement", "dispatch_1ms_early")) {
        val queue = Queue()
        lateinit var owner: Owner
        owner = Owner(queue, checked) {
          when (scenario) {
            "schedule_then_complete" -> {
              owner.scheduleAt(queue.elapsedMs + 50L)
              Decision.Complete
            }
            "cancel_then_retry" -> {
              owner.cancel()
              Decision.RetryAfter(25L)
            }
            else -> Decision.Complete
          }
        }
        val log = { stage: String ->
          println(
            "P5_PROBE {\"decision\":\"scheduler_contract\",\"variant\":\"${if (checked) "generation_checked" else "naive_post_decision"}\"," +
              "\"scenario\":\"$scenario\",\"stage\":\"$stage\",\"clockProvenance\":\"explicit fake elapsed clock and captured Kotlin queue\"," +
              "\"elapsedMs\":${queue.elapsedMs},\"dueMs\":${owner.dueMs},\"decideCount\":${owner.decideCount}," +
              "\"pendingCount\":${queue.pending.size},\"pendingDueMs\":${queue.pending.map { it.dueMs }}}",
          )
        }
        owner.scheduleAt(100L)
        val captured = queue.pending.single()
        log("scheduled")
        when (scenario) {
          "old_callback_after_replacement" -> {
            owner.scheduleAt(200L)
            queue.elapsedMs = 100L
            log("replacement_before_old_dispatch")
            queue.dispatch(captured)
          }
          "dispatch_1ms_early" -> {
            queue.elapsedMs = 99L
            queue.dispatch(captured)
            log("early_dispatch")
            queue.elapsedMs = 100L
            queue.dispatch(queue.pending.single())
          }
          else -> {
            queue.elapsedMs = 100L
            queue.dispatch(captured)
          }
        }
        log("after_dispatch")
      }
    }
  }
}
