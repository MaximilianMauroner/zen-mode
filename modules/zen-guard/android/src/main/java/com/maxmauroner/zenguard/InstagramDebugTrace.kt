package com.maxmauroner.zenguard

import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/** Privacy-safe runtime trace for mapping Instagram accessibility trees to guard decisions. */
internal data class InstagramDebugTraceMetadata(
  val sequence: Long,
  val eventAgeMs: Long?,
  val eventDeltaMs: Long?,
  val nodeCount: Int,
  val treeChanged: Boolean,
)

internal class InstagramDebugTrace {
  private var lastResourceFingerprint: Long? = null
  private var treeChangePending = false
  private var lastSurface: InstagramSurface? = null
  private var lastReason: String? = null
  private var lastAction = ""
  private var lastMode = ""
  private var lastExploreBlocked: Boolean? = null
  private var lastEventTimeMs: Long? = null
  private var lastTreeLoggedAt = 0L
  private var lastPolicyLoggedAt = 0L
  private var sequence = 0L

  fun record(
    event: AccessibilityEvent,
    nodes: List<NodeSignal>,
    detection: InstagramDetection,
    observationMode: Boolean,
    exploreBlocked: Boolean,
    action: InstagramGuardAction?,
    input: InstagramGuardInput? = null,
  ): InstagramDebugTraceMetadata {
    val currentSequence = ++sequence
    val nowMs = SystemClock.elapsedRealtime()
    val eventTimeMs = event.eventTime.takeIf { it > 0L && it <= nowMs && nowMs - it <= MAX_EVENT_AGE_MS }
    val eventAgeMs = eventTimeMs?.let { (nowMs - it).coerceAtLeast(0L) }
    val eventDeltaMs = eventTimeMs?.let { current ->
      lastEventTimeMs?.let { previous -> (current - previous).coerceAtLeast(0L) }
    }
    val actionName = action.traceName()
    val mode = if (observationMode) "OBSERVE" else "ENFORCE"
    val resourceFingerprint = calculateResourceFingerprint(nodes)
    val surfaceChanged = detection.surface != lastSurface
    val reasonChanged = detection.reason != lastReason
    val actionChanged = actionName != lastAction
    val modeChanged = mode != lastMode
    val explorePolicyChanged = lastExploreBlocked == null || exploreBlocked != lastExploreBlocked
    val policyEvent = event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
      event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
    val treeChanged = resourceFingerprint != lastResourceFingerprint
    treeChangePending = treeChangePending || treeChanged
    val stateChanged = surfaceChanged || reasonChanged || actionChanged || modeChanged || explorePolicyChanged
    val policyLogDue = policyEvent && (
      lastPolicyLoggedAt == 0L || nowMs - lastPolicyLoggedAt >= POLICY_LOG_INTERVAL_MS
      )
    val treeLogDue = treeChangePending && (
      stateChanged || policyLogDue || nowMs - lastTreeLoggedAt >= TREE_LOG_INTERVAL_MS
      )

    if (treeLogDue) {
      val resourceIds = nodes
        .map(NodeSignal::viewId)
        .filter(String::isNotBlank)
        .map(::shortResourceId)
        .distinct()
        .sorted()
      Log.i(TAG, buildString {
        appendTiming(currentSequence, eventAgeMs, eventDeltaMs)
        append(" tree resourceIds=")
        append(resourceIds.joinToString(","))
      })
      lastTreeLoggedAt = nowMs
      treeChangePending = false
    }

    if (stateChanged || policyLogDue) {
      Log.i(TAG, buildString {
        appendTiming(currentSequence, eventAgeMs, eventDeltaMs)
        append(" event=").append(AccessibilityEvent.eventTypeToString(event.eventType))
        append(" source=").append(shortResourceId(event.source?.viewIdResourceName.orEmpty()).ifBlank { "none" })
        append(" nodes=").append(nodes.size)
        append(" surface=").append(detection.surface)
        append(" reason=").append(detection.reason)
        append(" mode=").append(mode)
        append(" exploreBlocked=").append(exploreBlocked)
        append(" action=").append(actionName)
        append(" treeChanged=").append(treeChanged)
        input?.let {
          append(" dmThreadVisible=").append(it.dmThreadVisible)
          append(" dmThreadClicked=").append(it.dmThreadClicked)
          append(" reelPagerVisible=").append(it.reelPagerVisible)
          append(" reelPagerScrolled=").append(it.reelPagerScrolled)
        }
      })
    }

    if (policyLogDue) lastPolicyLoggedAt = nowMs
    lastResourceFingerprint = resourceFingerprint
    lastSurface = detection.surface
    lastReason = detection.reason
    lastAction = actionName
    lastMode = mode
    lastExploreBlocked = exploreBlocked
    if (eventTimeMs != null) lastEventTimeMs = eventTimeMs

    return InstagramDebugTraceMetadata(
      sequence = currentSequence,
      eventAgeMs = eventAgeMs,
      eventDeltaMs = eventDeltaMs,
      nodeCount = nodes.size,
      treeChanged = treeChanged,
    )
  }

  private fun InstagramGuardAction?.traceName(): String = when (this) {
    null -> "SKIP_UNKNOWN"
    InstagramGuardAction.None -> "NONE"
    is InstagramGuardAction.ShowBlocker -> "SHOW_BLOCKER_${reason.name}"
  }

  private fun shortResourceId(resourceId: String): String = resourceId.substringAfter(RESOURCE_ID_PREFIX, resourceId)

  private fun calculateResourceFingerprint(nodes: List<NodeSignal>): Long {
    var fingerprint = FNV_OFFSET_BASIS
    var idCount = 0L
    for (node in nodes) {
      val resourceId = node.viewId
      if (resourceId.isBlank()) continue
      idCount++
      fingerprint = (fingerprint xor resourceId.hashCode().toLong()) * FNV_PRIME
    }
    return fingerprint xor idCount
  }

  private fun StringBuilder.appendTiming(
    sequence: Long,
    eventAgeMs: Long?,
    eventDeltaMs: Long?,
  ) {
    append("seq=").append(sequence)
    eventAgeMs?.let { append(" eventAgeMs=").append(it) }
    eventDeltaMs?.let { append(" eventDeltaMs=").append(it) }
  }

  private companion object {
    const val TAG = "ZenGuardTrace"
    const val RESOURCE_ID_PREFIX = "com.instagram.android:id/"
    const val TREE_LOG_INTERVAL_MS = 1_000L
    const val POLICY_LOG_INTERVAL_MS = 250L
    const val MAX_EVENT_AGE_MS = 60_000L
    const val FNV_OFFSET_BASIS = -3_750_763_034_362_895_579L
    const val FNV_PRIME = 1_099_511_628_211L
  }
}
