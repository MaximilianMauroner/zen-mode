package com.maxmauroner.zenguard

/**
 * Accepts only a scroll owned by YouTube's full-screen Shorts pager.
 *
 * The current YouTube tree does not expose collection metadata on the visible page child. The
 * pager still owns the accessibility scroll event and reports a positive scroll position after a
 * forward swipe. Unknown sources and incomplete events deliberately fail open.
 */
internal object YouTubeShortsPager {
  private const val REEL_PAGER_ID = "reel_recycler"

  fun isVerifiedAdvance(isViewScrolled: Boolean, sourceViewId: String?, scrollY: Int): Boolean {
    if (!isViewScrolled || scrollY <= 0) return false
    return sourceViewId
      ?.substringAfterLast('/')
      ?.equals(REEL_PAGER_ID, ignoreCase = true) == true
  }
}
