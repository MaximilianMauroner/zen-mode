package com.maxmauroner.zenguard

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
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
      if (!isGranted(context)) return null
      val sessions = try {
        context.getSystemService(MediaSessionManager::class.java)?.getActiveSessions(component(context)) ?: return null
      } catch (_: SecurityException) {
        return null
      }
      return sessions.asSequence()
        .filter { it.packageName == YOUTUBE_PACKAGE }
        .mapNotNull { YouTubeVideoIdentity.fromPlayback(it.metadata, it.playbackState) }
        .toList()
        .singleOrNull()
    }

    private fun component(context: Context) = ComponentName(context, YouTubeNotificationAccess::class.java)

    private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
  }
}

internal sealed interface YouTubeVideoIdentity {
  data class MediaId(val value: String) : YouTubeVideoIdentity
  data class Metadata(val title: String, val creator: String?, val durationMs: Long) : YouTubeVideoIdentity {
    fun differsFrom(previous: Metadata): Boolean =
      title != previous.title ||
        (creator != null && previous.creator != null && creator != previous.creator) ||
        (durationMs > 0 && previous.durationMs > 0 && durationMs != previous.durationMs)
  }

  companion object {
    fun fromPlayback(metadata: MediaMetadata?, state: PlaybackState?): YouTubeVideoIdentity? {
      // YouTube reports the selected video's metadata during preroll. Its ad state cannot seek.
      if (state?.state != PlaybackState.STATE_PLAYING || (state.actions and PlaybackState.ACTION_SEEK_TO) == 0L) return null
      if (metadata == null) return null
      metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.takeIf(String::isNotBlank)?.let { return MediaId(it) }
      val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf(String::isNotBlank) ?: return null
      val creator = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf(String::isNotBlank)
      val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
      return Metadata(title, creator, duration)
    }
  }
}
