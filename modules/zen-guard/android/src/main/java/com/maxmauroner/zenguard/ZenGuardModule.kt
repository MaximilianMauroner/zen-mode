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
  }
}
