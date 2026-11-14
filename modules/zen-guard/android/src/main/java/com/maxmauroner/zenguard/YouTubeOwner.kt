package com.maxmauroner.zenguard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

internal class YouTubeOwner(
  private val service: AccessibilityService,
  private val stats: InterventionStats,
  private val canEnforce: () -> Boolean = { true },
) {
  private lateinit var preferences: ZenGuardPreferences
  private var storedPreferences: SharedPreferences? = null
  private val homeSettingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
    if (key == null || key in ZenGuardPreferences.YOUTUBE_HOME_RESET_KEYS) cancelHomeVisit()
  }
  private val homePolicy = YouTubeHomePolicy()
  private var homeAttemptedVisit: YouTubeHomeVisit? = null
  private val homeDeadline = DeadlineTask(Handler(Looper.getMainLooper()), decide = ::onHomeDeadline)
  private val policy = EnforcementStateMachine()
  private val pager = YouTubeShortsPager()
  private val exitResult = YouTubeShortsExitResult()
  private lateinit var resultOverlay: YouTubeShortsResultOverlay

  fun onServiceConnected(preferences: ZenGuardPreferences) {
    cancelHomeVisit()
    this.preferences = preferences
    storedPreferences?.unregisterOnSharedPreferenceChangeListener(homeSettingsListener)
    storedPreferences = service.getSharedPreferences(ZenGuardPreferences.FILE_NAME, Context.MODE_PRIVATE).also {
      it.registerOnSharedPreferenceChangeListener(homeSettingsListener)
    }
    resultOverlay = YouTubeShortsResultOverlay(service)
  }

  fun observe(event: AccessibilityEvent?, observedRoot: AccessibilityNodeInfo? = service.rootInActiveWindow) {
    val root = observedRoot
    if (root == null || root.packageName?.toString() != YOUTUBE_PACKAGE) {
      cancelHomeVisit()
      return
    }
    val nowMs = System.currentTimeMillis()
    preferences.recordEvent(nowMs)
    val screen = YouTubeSurfaceDetector.inspect(root)
    observeHome(screen)
    val nodes = snapshotNodeSignals(root)
    val result = ShortsDetector.detect(nodes)
    if (!result.isShortsViewer) {
      exitResult.confirmed(screen.surface, SystemClock.elapsedRealtime())?.let { exit ->
        recordYouTubeShortsStats(exit.pageIndex, EnforcementStatsOutcome.SUCCESS)
        resultOverlay.show()
      }
      policy.reset()
      pager.reset()
      stats.reset(EnforcementReason.YOUTUBE_SHORTS)
      return
    }
    preferences.recordDetection(nowMs, result.reason)
    if (preferences.observationMode || !preferences.shortsEnabled) {
      reset()
      stats.reset(EnforcementReason.YOUTUBE_SHORTS)
      return
    }
    // The pager's collection row stays constant during playback and changes with the video.
    val page = root.findAccessibilityNodeInfosByViewId("$YOUTUBE_PACKAGE:id/reel_player_page_container")
      .firstOrNull { it.isVisibleToUser }
    val pagerTransitionIndex = pager.stablePageIndex(
      isViewScrolled = event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED,
      sourceViewId = event?.source?.viewIdResourceName,
      fromIndex = event?.fromIndex ?: -1,
      toIndex = event?.toIndex ?: -1,
      scrollY = event?.scrollY ?: -1,
    )
    val pageIndex = page?.collectionItemInfo?.rowIndex ?: pagerTransitionIndex
    if (policy.next(
        isShorts = true,
        pageIndex = pageIndex,
        nowMs = SystemClock.elapsedRealtime(),
        pagerTransitionIndex = pagerTransitionIndex,
      ) == EnforcementAction.LEAVE_SHORTS
    ) {
      // Back/Home can put Premium Shorts into PiP. Use YouTube's own Home tab instead.
      val tabs = root.findAccessibilityNodeInfosByViewId("$YOUTUBE_PACKAGE:id/pivot_bar").firstOrNull()
      val homeTab = tabs?.getChild(0)?.getChild(0)
      if (homeTab?.isClickable == true && homeTab.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
        exitResult.requested(policy.pendingExitPageIndex(), SystemClock.elapsedRealtime())
      }
    }
  }

  fun reset() {
    cancelHomeVisit()
    policy.reset()
    pager.reset()
    exitResult.clear()
    if (::resultOverlay.isInitialized) resultOverlay.hide()
  }

  fun destroy() {
    storedPreferences?.unregisterOnSharedPreferenceChangeListener(homeSettingsListener)
    storedPreferences = null
    reset()
  }

  private fun homeEnabled(): Boolean =
    preferences.hasCurrentConsent && preferences.protectionEnabled && preferences.youtubeHomeEnabled &&
      !preferences.observationMode && service.getSystemService(PowerManager::class.java).isInteractive

  private fun observeHome(screen: YouTubeScreen) {
    if (screen.surface == YouTubeSurface.HOME) preferences.recordYouTubeHomeObserved()
    val visit = homePolicy.observe(
      screen.surface, homeEnabled(), preferences.youtubeHomeObserved, SystemClock.elapsedRealtime(),
    )
    if (visit == null) {
      cancelHomeVisit()
    } else if (!homeDeadline.isPending && homeAttemptedVisit !== visit) {
      homeDeadline.scheduleAt(visit.deadlineElapsedMs)
    }
  }

  private fun onHomeDeadline(): DeadlineDecision {
    if (!homeEnabled() || !canEnforce()) {
      cancelHomeVisit()
      return DeadlineDecision.Cancel
    }
    val root = service.rootInActiveWindow
    if (root == null) {
      cancelHomeVisit()
      return DeadlineDecision.Cancel
    }
    val screen = YouTubeSurfaceDetector.inspect(root)
    val visit = homePolicy.observe(
      screen.surface, homeEnabled(), preferences.youtubeHomeObserved, SystemClock.elapsedRealtime(),
    )
    if (visit == null) {
      cancelHomeVisit()
      return DeadlineDecision.Cancel
    }
    homeAttemptedVisit = visit
    screen.subscriptions?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    return DeadlineDecision.Complete
  }

  fun cancelHomeVisit() {
    homeDeadline.cancel()
    homePolicy.reset()
    homeAttemptedVisit = null
  }

  private fun recordYouTubeShortsStats(pageIndex: Int?, outcome: EnforcementStatsOutcome): Boolean {
    if (pageIndex == null) return false
    return stats.record(EnforcementReason.YOUTUBE_SHORTS, outcome, "page:$pageIndex")
  }

  private companion object {
    const val YOUTUBE_PACKAGE = "com.google.android.youtube"
  }
}
