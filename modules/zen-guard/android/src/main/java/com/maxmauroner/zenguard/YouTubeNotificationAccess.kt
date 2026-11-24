package com.maxmauroner.zenguard

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

internal class YouTubeNotificationAccess : NotificationListenerService() {
  companion object {
    fun isGranted(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      context.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(component(context)) == true
    } else {
      context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }

    fun playingVideo(context: Context): YouTubeVideoIdentity? {
      return activeSessions(context).asSequence()
        .filter { it.packageName == YOUTUBE_PACKAGE }
        .mapNotNull { YouTubeVideoIdentity.fromPlayback(it.metadata, it.playbackState) }
        .toList()
        .singleOrNull()
    }

    fun pausePlayback(context: Context, packageName: String) {
      for (session in activeSessions(context)) {
        if (session.packageName != packageName) continue
        val state = session.playbackState ?: continue
        if (state.state != PlaybackState.STATE_PLAYING) continue
        if (state.actions and (PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE) != 0L) {
          session.transportControls.pause()
        } else if (state.actions and PlaybackState.ACTION_STOP != 0L) {
          session.transportControls.stop()
        }
      }
    }

    private fun activeSessions(context: Context): List<MediaController> {
      if (!isGranted(context)) return emptyList()
      return try {
        context.getSystemService(MediaSessionManager::class.java)?.getActiveSessions(component(context)).orEmpty()
      } catch (_: SecurityException) {
        emptyList()
      }
    }

    private fun component(context: Context) = ComponentName(context, YouTubeNotificationAccess::class.java)

    private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
  }
}

internal fun AccessibilityService.exitEnforcedApp(packageName: String?) {
  if (packageName != null) YouTubeNotificationAccess.pausePlayback(this, packageName)
  performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
}

internal data class YouTubeVideoIdentity(
  val mediaId: String?,
  val title: String?,
  val creator: String?,
  val durationMs: Long,
) {
  fun differsFrom(previous: YouTubeVideoIdentity): Boolean {
    if (mediaId != null && previous.mediaId != null) return mediaId != previous.mediaId
    if (title == null || previous.title == null) return false
    return title != previous.title ||
      (creator != null && previous.creator != null && creator != previous.creator) ||
      (durationMs > 0 && previous.durationMs > 0 && durationMs != previous.durationMs)
  }

  fun withDetailsFrom(observed: YouTubeVideoIdentity): YouTubeVideoIdentity {
    val matchesId = mediaId != null && mediaId == observed.mediaId
    val matchesTitle = title != null && title == observed.title
    if (!matchesId && !matchesTitle) return this
    return YouTubeVideoIdentity(
      mediaId ?: observed.mediaId,
      title ?: observed.title,
      creator ?: observed.creator,
      if (durationMs > 0) durationMs else observed.durationMs,
    )
  }

  companion object {
    fun fromPlayback(metadata: MediaMetadata?, state: PlaybackState?): YouTubeVideoIdentity? {
      // YouTube reports the selected video's metadata during preroll. Its ad state cannot seek.
      if (state?.state != PlaybackState.STATE_PLAYING || (state.actions and PlaybackState.ACTION_SEEK_TO) == 0L) return null
      if (metadata == null) return null
      val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.takeIf(String::isNotBlank)
      val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf(String::isNotBlank)
        ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf(String::isNotBlank)
      if (mediaId == null && title == null) return null
      val creator = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf(String::isNotBlank)
      val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
      return YouTubeVideoIdentity(mediaId, title, creator, duration)
    }
  }
}
