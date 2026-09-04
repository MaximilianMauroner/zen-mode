package com.maxmauroner.zenguard

/**
 * Attributes foreground time to whichever app is on screen.
 *
 * The accessibility service tells the tracker which package is in front and
 * when. The tracker holds no clock of its own so it can be unit tested: every
 * entry point takes the current times explicitly.
 *
 * `nowMs` is wall-clock time, used only to decide which day the usage belongs
 * to. `elapsedMs` is monotonic uptime, used for the durations themselves, so
 * that a clock change cannot invent or erase usage.
 */
internal class AppUsageTracker {
  private var currentPackage: String? = null
  private var sinceElapsedMs = 0L

  /**
   * Records that [packageName] is now in front. Returns the time the previous
   * app spent on screen, or null when nothing needs to be written.
   */
  fun onForeground(packageName: String?, elapsedMs: Long): Attribution? {
    if (packageName == currentPackage) return null

    val finished = flushTo(elapsedMs)
    currentPackage = packageName
    sinceElapsedMs = elapsedMs
    return finished
  }

  /**
   * Banks the time the current app has accumulated so far without ending its
   * turn in the foreground. Called on a timer so a long session is counted, and
   * blocked, before the user ever switches away.
   */
  fun tick(elapsedMs: Long): Attribution? {
    val pending = flushTo(elapsedMs) ?: return null
    sinceElapsedMs = elapsedMs
    return pending
  }

  /** Drops any unbanked time, for when the guard is switched off. */
  fun reset() {
    currentPackage = null
    sinceElapsedMs = 0L
  }

  private fun flushTo(elapsedMs: Long): Attribution? {
    val previous = currentPackage ?: return null
    // A monotonic clock should never run backwards, but a service restart can
    // reset the reference point. Treat that as zero rather than a huge jump.
    val duration = (elapsedMs - sinceElapsedMs).coerceAtLeast(0L)
    if (duration <= 0L) return null
    return Attribution(previous, duration)
  }

  data class Attribution(val packageName: String, val durationMs: Long)
}
