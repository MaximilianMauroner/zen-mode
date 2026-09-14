package com.maxmauroner.zenguard

/** One version shared by native persistence and the service enforcement gate. */
internal object ConsentPolicy {
  const val CURRENT_VERSION = 3

  fun isCurrent(storedValue: Any?): Boolean = storedValue == CURRENT_VERSION
}
