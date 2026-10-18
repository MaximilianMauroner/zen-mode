package com.maxmauroner.zenguard

internal class InterventionStats(private var store: EnforcementStatsStore?) {
  private var actionSequence = 0L
  private val dedupeKeys = mutableMapOf<EnforcementReason, String>()

  fun replaceStore(store: EnforcementStatsStore?) {
    this.store = store
  }

  fun record(
    reason: EnforcementReason,
    outcome: EnforcementStatsOutcome,
    dedupeKey: String? = null,
    durableActionKey: String? = null,
  ): Boolean {
    if (outcome != EnforcementStatsOutcome.SUCCESS) return false
    if (dedupeKey != null && dedupeKeys[reason] == dedupeKey) return false
    if (dedupeKey != null) dedupeKeys[reason] = dedupeKey
    val store = store ?: return false
    actionSequence = if (actionSequence == Long.MAX_VALUE) 1L else actionSequence + 1L
    val nowMs = System.currentTimeMillis()
    return store.record(reason, durableActionKey ?: "${reason.key}:$nowMs:$actionSequence", nowMs)
  }

  fun reset(reason: EnforcementReason) {
    dedupeKeys.remove(reason)
  }

  fun clearKeyIfDifferent(reason: EnforcementReason, key: String) {
    if (dedupeKeys[reason] != key) reset(reason)
  }

  fun clear() {
    dedupeKeys.clear()
  }
}
