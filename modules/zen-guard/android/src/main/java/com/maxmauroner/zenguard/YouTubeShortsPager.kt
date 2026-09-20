package com.maxmauroner.zenguard

/**
 * Accepts only a scroll owned by YouTube's full-screen Shorts pager.
 *
 * The current YouTube tree does not expose collection metadata on the visible page child. The
 * pager still owns the accessibility scroll event and reports its visible item range. A transition
 * is verified only when a partial multi-item range is followed by one settled visible item. An
 * initial absolute position, unknown source, or incomplete sequence therefore fails open.
 */
internal class YouTubeShortsPager {
  private val reelPagerId = "reel_recycler"
  private var transitionInProgress = false

  fun stablePageIndex(
    isViewScrolled: Boolean,
    sourceViewId: String?,
    fromIndex: Int,
    toIndex: Int,
    scrollY: Int,
  ): Int? {
    if (!isViewScrolled || fromIndex < 0 || toIndex < 0 || scrollY < 0) return null
    val isPager = sourceViewId
      ?.substringAfterLast('/')
      ?.equals(reelPagerId, ignoreCase = true) == true
    if (!isPager) return null
    if (fromIndex != toIndex) {
      transitionInProgress = true
      return null
    }
    if (!transitionInProgress) return null
    transitionInProgress = false
    return fromIndex
  }

  fun reset() {
    transitionInProgress = false
  }
}
