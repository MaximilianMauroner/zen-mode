package com.maxmauroner.zenguard

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class ZenGuardModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("ZenGuard")

    AsyncFunction("getStatus") {
      val context = requireNotNull(appContext.reactContext)
      val preferences = ZenGuardPreferences(context)
      mapOf(
        "available" to true,
        "serviceEnabled" to isServiceEnabled(context),
        "protectionEnabled" to preferences.protectionEnabled,
        "observationMode" to preferences.observationMode,
        "lastEventAt" to preferences.lastEventAt.toDouble(),
        "lastDetectionAt" to preferences.lastDetectionAt.toDouble(),
        "detectionCount" to preferences.detectionCount,
        "lastDetectionReason" to preferences.lastDetectionReason,
        "instagramObservationMode" to preferences.instagramObservationMode,
        "instagramWaitSeconds" to preferences.instagramWaitSeconds,
        "instagramReelsMinutes" to preferences.instagramReelsMinutes,
        "instagramHomeMinutes" to preferences.instagramHomeMinutes,
        "instagramExploreBlocked" to preferences.instagramExploreBlocked,
        "instagramLastDetectionAt" to preferences.instagramLastDetectionAt.toDouble(),
        "instagramDetectionCount" to preferences.instagramDetectionCount,
        "instagramSignalMask" to preferences.instagramSignalMask,
        "instagramLastDetectionReason" to preferences.instagramLastDetectionReason,
      )
    }

    AsyncFunction("openAccessibilitySettings") {
      val context = requireNotNull(appContext.reactContext)
      val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
    }

    AsyncFunction("openYouTube") {
      val context = requireNotNull(appContext.reactContext)
      val intent = context.packageManager.getLaunchIntentForPackage(YOUTUBE_PACKAGE)
        ?: throw IllegalStateException("The YouTube app is not installed")
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
    }

    AsyncFunction("openInstagram") {
      val context = requireNotNull(appContext.reactContext)
      val intent = context.packageManager.getLaunchIntentForPackage(INSTAGRAM_PACKAGE)
        ?: throw IllegalStateException("The Instagram app is not installed")
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
    }

    AsyncFunction("setProtectionEnabled") { enabled: Boolean ->
      val context = requireNotNull(appContext.reactContext)
      ZenGuardPreferences(context).protectionEnabled = enabled
    }

    AsyncFunction("setObservationMode") { enabled: Boolean ->
      val context = requireNotNull(appContext.reactContext)
      val preferences = ZenGuardPreferences(context)
      if (!enabled && preferences.lastDetectionAt == 0L) {
        throw IllegalStateException("A Shorts signal must be observed before enforcement can be enabled")
      }
      preferences.observationMode = enabled
    }

    AsyncFunction("setInstagramObservationMode") { enabled: Boolean ->
      val context = requireNotNull(appContext.reactContext)
      val preferences = ZenGuardPreferences(context)
      if (!enabled && preferences.instagramSignalMask and REQUIRED_INSTAGRAM_SIGNALS != REQUIRED_INSTAGRAM_SIGNALS) {
        throw IllegalStateException("Direct Messages and Reels signals must be observed before enforcement can be enabled")
      }
      preferences.instagramObservationMode = enabled
    }

    AsyncFunction("setInstagramSettings") {
        waitSeconds: Int,
        reelsMinutes: Int,
        homeMinutes: Int,
        exploreBlocked: Boolean,
      ->
      require(waitSeconds in 15..300) { "Wait must be between 15 and 300 seconds" }
      require(reelsMinutes in 1..15) { "Reels time must be between 1 and 15 minutes" }
      require(homeMinutes in 1..30) { "Home time must be between 1 and 30 minutes" }
      val context = requireNotNull(appContext.reactContext)
      ZenGuardPreferences(context).apply {
        instagramWaitSeconds = waitSeconds
        instagramReelsMinutes = reelsMinutes
        instagramHomeMinutes = homeMinutes
        instagramExploreBlocked = exploreBlocked
      }
    }
  }

  private fun isServiceEnabled(context: android.content.Context): Boolean {
    val expected = ComponentName(context, ZenGuardAccessibilityService::class.java)
    val enabledServices = Settings.Secure.getString(
      context.contentResolver,
      Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return enabledServices.split(':').any { ComponentName.unflattenFromString(it) == expected }
  }

  companion object {
    private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    private const val INSTAGRAM_PACKAGE = "com.instagram.android"
    private const val REQUIRED_INSTAGRAM_SIGNALS = 3
  }
}
