package com.maxmauroner.zenguard

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

internal class YouTubeOwner(
  private val service: AccessibilityService,
  private val stats: InterventionStats,
) {
  private lateinit var preferences: ZenGuardPreferences
  private val policy = EnforcementStateMachine()
  private val pager = YouTubeShortsPager()
  private val exitResult = YouTubeShortsExitResult()
  private lateinit var resultOverlay: YouTubeShortsResultOverlay

  fun onServiceConnected(preferences: ZenGuardPreferences) {
    this.preferences = preferences
    resultOverlay = YouTubeShortsResultOverlay(service)
  }

  fun observe(event: AccessibilityEvent?, observedRoot: AccessibilityNodeInfo? = service.rootInActiveWindow) {
    val root = observedRoot ?: return
    if (root.packageName?.toString() != YOUTUBE_PACKAGE) return
    val nowMs = System.currentTimeMillis()
    preferences.recordEvent(nowMs)
    val nodes = snapshotNodeSignals(root)
    val result = ShortsDetector.detect(nodes)
    if (!result.isShortsViewer) {
      val surface = YouTubeSurfaceDetector.detect(nodes)
      val homeTab = root.findAccessibilityNodeInfosByViewId("$YOUTUBE_PACKAGE:id/pivot_bar")
        .firstOrNull()?.getChild(0)?.getChild(0)
      val homeSelected = homeTab?.isSelected == true || homeTab?.getChild(0)?.isSelected == true
      exitResult.confirmed(surface, homeSelected, SystemClock.elapsedRealtime())?.let { exit ->
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
    policy.reset()
    pager.reset()
    exitResult.clear()
    if (::resultOverlay.isInitialized) resultOverlay.hide()
  }

  private fun recordYouTubeShortsStats(pageIndex: Int?, outcome: EnforcementStatsOutcome): Boolean {
    if (pageIndex == null) return false
    return stats.record(EnforcementReason.YOUTUBE_SHORTS, outcome, "page:$pageIndex")
  }

  private companion object {
    const val YOUTUBE_PACKAGE = "com.google.android.youtube"
  }
}
