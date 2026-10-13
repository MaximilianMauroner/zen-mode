package com.maxmauroner.zenguard

/** Ceiling seconds prevent a positive remaining duration from displaying zero. */
internal fun formatAvailabilityCountdown(remainingMs: Long): String {
  val seconds = if (remainingMs <= 0L) 0L else (remainingMs - 1L) / 1_000L + 1L
  return if (seconds >= 3_600L) {
    "%d:%02d:%02d".format(seconds / 3_600L, seconds / 60L % 60L, seconds % 60L)
  } else {
    "%d:%02d".format(seconds / 60L, seconds % 60L)
  }
}
