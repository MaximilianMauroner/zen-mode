package com.maxmauroner.zenguard

import org.junit.Assert.assertEquals
import org.junit.Test

class InstagramDetectorTest {
  @Test
  fun detectsStrongResourceIds() {
    assertEquals(
      InstagramSurface.DIRECT_MESSAGES,
      InstagramDetector.detect(listOf(NodeSignal(viewId = "com.instagram.android:id/direct_thread"))).surface,
    )
    assertEquals(
      InstagramSurface.REELS_VIEWER,
      InstagramDetector.detect(listOf(NodeSignal(viewId = "com.instagram.android:id/clips_viewer_view_pager"))).surface,
    )
    assertEquals(
      InstagramSurface.HOME_FEED,
      InstagramDetector.detect(listOf(NodeSignal(viewId = "com.instagram.android:id/feed_recycler_view"))).surface,
    )
    assertEquals(
      InstagramSurface.EXPLORE,
      InstagramDetector.detect(listOf(NodeSignal(viewId = "com.instagram.android:id/explore_grid"))).surface,
    )
  }

  @Test
  fun labelsAndUnknownTreesFailOpen() {
    assertEquals(
      InstagramSurface.UNKNOWN,
      InstagramDetector.detect(
        listOf(
          NodeSignal(text = "Reels", selected = true),
          NodeSignal(description = "Explore"),
          NodeSignal(text = "Messages"),
        ),
      ).surface,
    )
  }

  @Test
  fun conflictingStrongSignalsFailOpen() {
    assertEquals(
      InstagramSurface.UNKNOWN,
      InstagramDetector.detect(
        listOf(
          NodeSignal(viewId = "com.instagram.android:id/direct_thread"),
          NodeSignal(viewId = "com.instagram.android:id/clips_viewer_view_pager"),
        ),
      ).surface,
    )
  }

  @Test
  fun embeddedReelSignalsDoNotIdentifyAFullScreenViewer() {
    assertEquals(
      InstagramSurface.DIRECT_MESSAGES,
      InstagramDetector.detect(
        listOf(
          NodeSignal(viewId = "com.instagram.android:id/thread_fragment_container"),
          NodeSignal(viewId = "com.instagram.android:id/message_list"),
          NodeSignal(viewId = "com.instagram.android:id/message_content_portrait_xma_container"),
          NodeSignal(viewId = "com.instagram.android:id/reel_viewer_front_avatar"),
          NodeSignal(viewId = "com.instagram.android:id/clips_media_component"),
          NodeSignal(viewId = "com.instagram.android:id/header_title"),
        ),
      ).surface,
    )
  }

  @Test
  fun detectsInstagram445HomeFeedFromCorroboratingIds() {
    assertEquals(
      InstagramSurface.HOME_FEED,
      InstagramDetector.detect(
        listOf(
          NodeSignal(viewId = "com.instagram.android:id/feed_tab", selected = true),
          NodeSignal(viewId = "com.instagram.android:id/row_feed_profile_header"),
          NodeSignal(viewId = "com.instagram.android:id/row_feed_view_group_buttons"),
          NodeSignal(viewId = "android:id/list"),
        ),
      ).surface,
    )
  }

  @Test
  fun detectsInstagram445HomeWhenFeedContentChanges() {
    assertEquals(
      InstagramSurface.HOME_FEED,
      InstagramDetector.detect(
        listOf(
          NodeSignal(viewId = "com.instagram.android:id/feed_tab", selected = true),
          NodeSignal(viewId = "com.instagram.android:id/row_feed_profile_header"),
          NodeSignal(viewId = "android:id/list"),
          NodeSignal(viewId = "com.instagram.android:id/row_feed_photo_profile_imageview"),
        ),
      ).surface,
    )
  }

  @Test
  fun detectsInstagram445HomeWhenFeedTabIsNotMarkedSelected() {
    assertEquals(
      InstagramSurface.HOME_FEED,
      InstagramDetector.detect(
        listOf(
          NodeSignal(viewId = "com.instagram.android:id/feed_tab"),
          NodeSignal(viewId = "android:id/list"),
          NodeSignal(viewId = "com.instagram.android:id/row_feed_photo_imageview"),
          NodeSignal(viewId = "com.instagram.android:id/row_feed_profile_header"),
        ),
      ).surface,
    )
  }

  @Test
  fun detectsInstagram445ExploreFromCorroboratingIds() {
    assertEquals(
      InstagramSurface.EXPLORE,
      InstagramDetector.detect(
        listOf(
          NodeSignal(viewId = "com.instagram.android:id/search_tab", selected = true),
          NodeSignal(viewId = "com.instagram.android:id/explore_action_bar_right_button_stub"),
          NodeSignal(viewId = "com.instagram.android:id/grid_card_layout_container"),
          NodeSignal(viewId = "com.instagram.android:id/recycler_view"),
        ),
      ).surface,
    )
  }

  @Test
  fun detectsInstagram445Inbox() {
    assertEquals(
      InstagramSurface.DIRECT_MESSAGES,
      InstagramDetector.detect(
        listOf(NodeSignal(viewId = "com.instagram.android:id/inbox_refreshable_thread_list_recyclerview")),
      ).surface,
    )
  }

  @Test
  fun survivesRenamedReelsPagerAcrossReleases() {
    // Instagram renames the pager ID between releases. Any clips/reels pager still classifies.
    for (renamed in listOf("clips_viewer_pager_v3", "reels_viewer_media_pager", "clips_paged_recycler_pager")) {
      assertEquals(
        InstagramSurface.REELS_VIEWER,
        InstagramDetector.detect(listOf(NodeSignal(viewId = "com.instagram.android:id/$renamed"))).surface,
      )
    }
  }

  @Test
  fun survivesRenamedInboxRecyclerAcrossReleases() {
    assertEquals(
      InstagramSurface.DIRECT_MESSAGES,
      InstagramDetector.detect(
        listOf(NodeSignal(viewId = "com.instagram.android:id/inbox_v2_thread_list_recycler")),
      ).surface,
    )
  }

  @Test
  fun sharedReelInsideDmDoesNotBecomeReelsAcrossReleases() {
    // A story reel avatar plus a renamed embedded clip component must not read as the full-screen
    // viewer while the DM thread owns the tree.
    assertEquals(
      InstagramSurface.DIRECT_MESSAGES,
      InstagramDetector.detect(
        listOf(
          NodeSignal(viewId = "com.instagram.android:id/thread_fragment_container"),
          NodeSignal(viewId = "com.instagram.android:id/message_composer"),
          NodeSignal(viewId = "com.instagram.android:id/reel_viewer_front_avatar"),
          NodeSignal(viewId = "com.instagram.android:id/clips_media_component_v2"),
        ),
      ).surface,
    )
  }

  @Test
  fun genericListsDoNotIdentifyHomeOrExplore() {
    assertEquals(
      InstagramSurface.UNKNOWN,
      InstagramDetector.detect(
        listOf(
          NodeSignal(viewId = "android:id/list"),
          NodeSignal(viewId = "com.instagram.android:id/recycler_view"),
        ),
      ).surface,
    )
  }
}
