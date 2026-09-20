package com.maxmauroner.zenguard

/**
 * Accepts only a scroll owned by YouTube's full-screen Shorts pager.
 *
 * The current YouTube tree does not expose collection metadata on the visible page child. The
 * pager still owns the accessibility scroll event and reports its visible item range and position.
 * A completed forward transition has one visible item plus a positive scroll position; entry,
 * partial, unknown, and incomplete events fail open. These fields are available on every supported
 * Android version.
 */
internal object YouTubeShortsPager {
  private const val REEL_PAGER_ID = "reel_recycler"

  fun stablePageIndex(
    isViewScrolled: Boolean,
    sourceViewId: String?,
    fromIndex: Int,
    toIndex: Int,
    scrollY: Int,
  ): Int? {
    if (!isViewScrolled || fromIndex < 0 || fromIndex != toIndex || scrollY <= 0) return null
    val isPager = sourceViewId
      ?.substringAfterLast('/')
      ?.equals(REEL_PAGER_ID, ignoreCase = true) == true
    return if (isPager) fromIndex else null
  }
}
