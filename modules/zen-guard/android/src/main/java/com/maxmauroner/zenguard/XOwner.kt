package com.maxmauroner.zenguard

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.ArrayDeque

internal class XOwner(
  private val service: AccessibilityService,
  private val stats: InterventionStats,
  private val canEnforce: () -> Boolean,
) {
  private lateinit var preferences: ZenGuardPreferences
  private lateinit var homeFeedStatusStore: HomeFeedStatusStore
  private val policy = XGuardStateMachine()
  private var homeUsageState = HomeFeedUsageState.PAUSED
  private var storageAvailable = true
  private lateinit var overlay: XBreakOverlay
  private val handler by lazy { Handler(Looper.getMainLooper()) }
  private val ticker = object : Runnable {
    override fun run() {
      observe(null)
      handler.postDelayed(this, 1_000L)
    }
  }

  fun restore(preferences: ZenGuardPreferences, homeFeedStatusStore: HomeFeedStatusStore, nowElapsedMs: Long) {
    this.preferences = preferences
    this.homeFeedStatusStore = homeFeedStatusStore
    val storedX = homeFeedStatusStore.x(nowElapsedMs)
    storageAvailable = storedX.storageState == HomeFeedStorageState.AVAILABLE
    homeUsageState = if (storageAvailable) storedX.usageState else HomeFeedUsageState.UNKNOWN
    policy.restore(
      HomeFeedRuntimeState(
        usedMs = storedX.usedMs,
        blockedUntilElapsedMs = storedX.blockedUntilElapsedMs,
        usageState = storedX.usageState,
        lockoutState = storedX.lockoutState,
        storageState = storedX.storageState,
        capturedAtElapsedMs = nowElapsedMs,
      ),
    )
    if (storageAvailable) publishHomeStatus()
  }

  fun start() {
    overlay = XBreakOverlay(service) {
      if (canEnforce()) {
        policy.leaveBlockedSurface()
        overlay.hide()
        stats.reset(EnforcementReason.X_HOME)
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
      }
    }
    handler.postDelayed(ticker, 1_000L)
  }

  fun pause() = clearEnforcement(preserveHomeLockout = true)

  fun disable() = clearEnforcement()

  fun screenOff(nowElapsedMs: Long) {
    policy.pause(nowElapsedMs, readSettings())
    if (homeUsageState != HomeFeedUsageState.UNKNOWN) homeUsageState = HomeFeedUsageState.PAUSED
  }

  fun destroy(protectionActive: Boolean) {
    handler.removeCallbacksAndMessages(null)
    if (protectionActive) {
      publishHomeStatus()
    } else {
      disable()
    }
  }

  private fun clearEnforcement(preserveHomeLockout: Boolean = false) {
    if (preserveHomeLockout && storageAvailable) {
      policy.pause(SystemClock.elapsedRealtime(), readSettings())
      homeUsageState = if (policy.requiresFreshHomeObservation()) {
        HomeFeedUsageState.UNKNOWN
      } else {
        HomeFeedUsageState.PAUSED
      }
    } else {
      policy.reset()
      homeUsageState = if (storageAvailable) HomeFeedUsageState.PAUSED else HomeFeedUsageState.UNKNOWN
    }
    stats.reset(EnforcementReason.X_HOME)
    stats.reset(EnforcementReason.X_VIDEOS)
    if (::overlay.isInitialized) overlay.hide()
    publishHomeStatus()
  }

  fun observe(event: AccessibilityEvent?) {
    if (!canEnforce()) return
    val nowMs = SystemClock.elapsedRealtime()
    var settings = readSettings()
    if (!isScreenInteractive) {
      policy.pause(nowMs, settings)
      homeUsageState = if (policy.requiresFreshHomeObservation()) {
        HomeFeedUsageState.UNKNOWN
      } else {
        HomeFeedUsageState.PAUSED
      }
      publishHomeStatus()
      return
    }
    val root = service.rootInActiveWindow ?: run {
      policy.markUnverifiableGap(nowMs, settings)
      homeUsageState = HomeFeedUsageState.UNKNOWN
      publishHomeStatus()
      return
    }
    if (root.packageName?.toString() != X_PACKAGE) {
      if (overlay.isShowing && service.windows.any { it.root?.packageName?.toString() == X_PACKAGE }) return
      policy.pause(nowMs, settings)
      homeUsageState = if (policy.requiresFreshHomeObservation()) {
        HomeFeedUsageState.UNKNOWN
      } else {
        HomeFeedUsageState.PAUSED
      }
      publishHomeStatus()
      return
    }
    val surface = XDetector.detect(snapshotNodeSignals(root))
    val pager = if (surface == XSurface.VIDEO) findXNode(root) { isXVideoPager(it) } else null
    if (surface == XSurface.HOME) preferences.recordXSignal(ZenGuardPreferences.X_HOME_SIGNAL)
    if (pager != null) preferences.recordXSignal(ZenGuardPreferences.X_VIDEO_SIGNAL)
    if (preferences.xObservationMode) { clearEnforcement(); return }
    settings = readSettings()
    if (!storageAvailable && surface == XSurface.HOME && settings.homeEnabled && event != null) {
      if (homeFeedStatusStore.recoverX(nowMs)) {
        storageAvailable = true
        policy.recoverStorage(nowMs)
      }
    }
    val source = event?.source
    val advanced = pager != null && event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
      source != null && XVideoAdvanceDetector.isAdvance(
        XVideoScrollSignal(
          sourceIsPager = isXVideoPager(source),
          sourceOwnedByPager = isXVideoPagerOrDescendant(source),
          scrollY = event.scrollY,
          fromIndex = event.fromIndex,
          toIndex = event.toIndex,
        ),
      )
    val action = policy.next(surface, nowMs, settings, advanced)
    homeUsageState = when {
      action == XAction.HOME_UNAVAILABLE || surface == XSurface.UNKNOWN -> HomeFeedUsageState.UNKNOWN
      surface == XSurface.HOME && settings.homeEnabled && !policy.requiresFreshHomeObservation() -> HomeFeedUsageState.ACTIVE
      policy.requiresFreshHomeObservation() -> HomeFeedUsageState.UNKNOWN
      else -> HomeFeedUsageState.PAUSED
    }
    when (action) {
      XAction.HOME_BREAK -> {
        val wasShowing = overlay.isShowing
        homeUsageState = HomeFeedUsageState.PAUSED
        overlay.show(preferences.xHomeMinutes)
        val lockoutDeadline = policy.homeRuntimeState(nowMs).blockedUntilElapsedMs
        recordXHomeStats(wasShowing, overlay.isShowing, EnforcementStatsOutcome.SUCCESS, lockoutDeadline)
      }
      XAction.HOME_UNAVAILABLE -> {
        homeUsageState = HomeFeedUsageState.UNKNOWN
        overlay.showUnavailable()
      }
      XAction.LEAVE_VIDEO -> {
        overlay.hide()
        // X's own Back control closes its viewer without docking playback into PiP.
        val back = findXNode(root) { it.contentDescription?.toString() == "Back" }
        var target = back
        var leftVideo = false
        var attempts = 0
        while (target != null && attempts++ < MAX_PARENT_CHAIN) {
          val candidate = target ?: break
          if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            leftVideo = true
            break
          }
          target = candidate.parent
        }
        if (leftVideo) {
          val callbackKey = event?.eventTime?.toString() ?: SystemClock.elapsedRealtime().toString()
          recordXVideoStats(callbackKey, EnforcementStatsOutcome.SUCCESS)
          Toast.makeText(service, R.string.zen_guard_x_video_blocked, Toast.LENGTH_SHORT).show()
        }
      }
      XAction.NONE -> if (surface != XSurface.UNKNOWN) {
        overlay.hide()
        stats.reset(EnforcementReason.X_HOME)
      }
    }
    publishHomeStatus(showUnavailableOnFailure = surface == XSurface.HOME && settings.homeEnabled)
  }

  /** X's observed pager is the full-screen scroll node two levels under VideoTab. */
  private fun isXVideoPager(node: AccessibilityNodeInfo): Boolean =
    node.isVisibleToUser && node.isScrollable && node.parent?.parent?.viewIdResourceName == "VideoTab"

  /** X can emit the pager's scroll event from one of its descendants. */
  private fun isXVideoPagerOrDescendant(node: AccessibilityNodeInfo): Boolean {
    var candidate: AccessibilityNodeInfo? = node
    var depth = 0
    while (candidate != null && depth++ <= MAX_PARENT_CHAIN) {
      if (isXVideoPager(candidate)) return true
      candidate = candidate.parent
    }
    return false
  }

  private fun findXNode(root: AccessibilityNodeInfo, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
    val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
    queue.add(root to 0)
    var visited = 0
    while (queue.isNotEmpty() && visited++ < MAX_NODES) {
      val (node, depth) = queue.removeFirst()
      if (!node.isVisibleToUser) continue
      if (matches(node)) return node
      if (depth < MAX_DEPTH) for (index in 0 until node.childCount) node.getChild(index)?.let { queue.add(it to depth + 1) }
    }
    return null
  }

  private val isScreenInteractive: Boolean
    get() = service.getSystemService(PowerManager::class.java)?.isInteractive == true

  fun publishHomeStatus(showUnavailableOnFailure: Boolean = false) {
    if (!::homeFeedStatusStore.isInitialized || !storageAvailable) return
    val nowElapsedMs = SystemClock.elapsedRealtime()
    val runtime = policy.homeRuntimeState(nowElapsedMs)
    if (homeFeedStatusStore.recordX(runtime.copy(usageState = homeUsageState))) return

    storageAvailable = false
    homeUsageState = HomeFeedUsageState.UNKNOWN
    policy.markStorageUnavailable()
    if (showUnavailableOnFailure && ::overlay.isInitialized) overlay.showUnavailable()
  }

  private fun readSettings() = XSettings(
    homeEnabled = preferences.xHomeEnabled && preferences.hasXSignal(ZenGuardPreferences.X_HOME_SIGNAL),
    videosEnabled = preferences.xVideosEnabled && preferences.hasXSignal(ZenGuardPreferences.X_VIDEO_SIGNAL),
    homeAllowanceMs = preferences.xHomeMinutes * 60_000L,
  )

  private fun recordXHomeStats(
    wasShowing: Boolean,
    overlayAttached: Boolean,
    outcome: EnforcementStatsOutcome,
    lockoutDeadlineElapsedMs: Long? = null,
  ): Boolean {
    if (wasShowing || !overlayAttached) return false
    val durableKey = lockoutDeadlineElapsedMs?.takeIf { it >= 0L }?.let { "x_home_lockout:$it" }
    return stats.record(EnforcementReason.X_HOME, outcome, "overlay", durableKey)
  }

  private fun recordXVideoStats(callbackKey: String, outcome: EnforcementStatsOutcome): Boolean =
    stats.record(EnforcementReason.X_VIDEOS, outcome, callbackKey)

  private companion object {
    const val X_PACKAGE = "com.twitter.android"
    const val MAX_NODES = 500
    const val MAX_DEPTH = 36
    const val MAX_PARENT_CHAIN = 8
  }
}
