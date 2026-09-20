package com.maxmauroner.zenguard

/**
 * Accepts only a scroll owned by YouTube's full-screen Shorts pager.
 *
 * The current YouTube tree does not expose collection metadata on the visible page child. The
 * pager still owns the accessibility scroll event and reports its visible item range and movement.
 * A completed transition has one non-negative visible index plus a non-zero scroll delta; initial
 * layout callbacks, partial transitions, unknown sources, and incomplete events fail open.
 */
internal object YouTubeShortsPager {
  private const val REEL_PAGER_ID = "reel_recycler"

  fun stablePageIndex(
    isViewScrolled: Boolean,
    sourceViewId: String?,
    fromIndex: Int,
    toIndex: Int,
    scrollDeltaY: Int,
  ): Int? {
    if (!isViewScrolled || fromIndex < 0 || fromIndex != toIndex || scrollDeltaY == 0) return null
    val isPager = sourceViewId
      ?.substringAfterLast('/')
      ?.equals(REEL_PAGER_ID, ignoreCase = true) == true
    return if (isPager) fromIndex else null
  }
}
