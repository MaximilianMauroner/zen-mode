package com.maxmauroner.zenguard

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import expo.modules.kotlin.functions.Queues
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
        "shortsEnabled" to preferences.shortsEnabled,
        "xHomeEnabled" to preferences.xHomeEnabled,
        "xVideosEnabled" to preferences.xVideosEnabled,
        "xHomeMinutes" to preferences.xHomeMinutes,
        "xObservationMode" to preferences.xObservationMode,
        "xSignalMask" to preferences.xSignalMask,
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

    /** Launchable apps, so the limiter can offer a picker. Excludes Zen Mode itself. */
    AsyncFunction("getInstalledApps") {
      val context = requireNotNull(appContext.reactContext)
      val packageManager = context.packageManager
      val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
      packageManager.queryIntentActivities(launcher, 0)
        .asSequence()
        .map { it.activityInfo.packageName }
        .distinct()
        .filter { it != context.packageName }
        .map { packageName ->
          mapOf(
            "packageName" to packageName,
            "label" to labelFor(packageManager, packageName),
          )
        }
        .sortedBy { (it["label"] as String).lowercase() }
        .toList()
    }

    /** Each limited app with its daily budget and how much of today it has used. */
    AsyncFunction("getAppLimits") {
      val context = requireNotNull(appContext.reactContext)
      val store = AppLimitStore(context)
      val usage = store.usageToday(System.currentTimeMillis())
      store.limits().map { (packageName, minutes) ->
        mapOf(
          "packageName" to packageName,
          "label" to labelFor(context.packageManager, packageName),
          "minutes" to minutes,
          "usedMs" to (usage[packageName] ?: 0L).toDouble(),
        )
      }.sortedBy { (it["label"] as String).lowercase() }
    }

    AsyncFunction("setAppLimit") { packageName: String, minutes: Int ->
      require(minutes in 1..480) { "A daily limit must be between 1 and 480 minutes" }
      val context = requireNotNull(appContext.reactContext)
      AppLimitStore(context).setLimit(packageName, minutes)
    }

    AsyncFunction("removeAppLimit") { packageName: String ->
      val context = requireNotNull(appContext.reactContext)
      AppLimitStore(context).removeLimit(packageName)
    }

    /** Each intent-gated app with its visit length and downtime. */
    AsyncFunction("getIntentApps") {
      val context = requireNotNull(appContext.reactContext)
      val store = IntentAppStore(context)
      store.intents().map { (packageName, rule) ->
        mapOf(
          "packageName" to packageName,
          "label" to labelFor(context.packageManager, packageName),
          "sessionMinutes" to rule.sessionMinutes,
          "cooldownMinutes" to rule.cooldownMinutes,
        )
      }.sortedBy { (it["label"] as String).lowercase() }
    }

    AsyncFunction("setIntentApp") { packageName: String, sessionMinutes: Int, cooldownMinutes: Int ->
      val context = requireNotNull(appContext.reactContext)
      IntentAppStore(context).setIntent(packageName, sessionMinutes, cooldownMinutes)
    }

    AsyncFunction("removeIntentApp") { packageName: String ->
      val context = requireNotNull(appContext.reactContext)
      IntentAppStore(context).removeIntent(packageName)
    }

    /** Each rolling-limited app with its allowance, window, and spent time. */
    AsyncFunction("getRollingLimits") {
      val context = requireNotNull(appContext.reactContext)
      val store = RollingLimitStore(context)
      val nowMs = System.currentTimeMillis()
      store.rules().map { (packageName, rule) ->
        mapOf(
          "packageName" to packageName,
          "label" to labelFor(context.packageManager, packageName),
          "allowanceMinutes" to rule.allowanceMinutes,
          "windowMinutes" to rule.windowMinutes,
          "usedMs" to store.usedMs(packageName, nowMs).toDouble(),
        )
      }.sortedBy { (it["label"] as String).lowercase() }
    }

    AsyncFunction("setRollingLimit") { packageName: String, allowanceMinutes: Int, windowMinutes: Int ->
      val context = requireNotNull(appContext.reactContext)
      RollingLimitStore(context).setRule(packageName, allowanceMinutes, windowMinutes)
    }

    AsyncFunction("removeRollingLimit") { packageName: String ->
      val context = requireNotNull(appContext.reactContext)
      RollingLimitStore(context).removeRule(packageName)
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
      if (!InstagramIntentHelper.openInstagram(context)) {
        throw IllegalStateException("The Instagram app is not installed or cannot be opened")
      }
    }.runOnQueue(Queues.MAIN)

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

    AsyncFunction("openX") {
      val context = requireNotNull(appContext.reactContext)
      val intent = context.packageManager.getLaunchIntentForPackage("com.twitter.android")
        ?: throw IllegalStateException("The X app is not installed")
      context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    AsyncFunction("setShortsEnabled") { enabled: Boolean ->
      ZenGuardPreferences(requireNotNull(appContext.reactContext)).shortsEnabled = enabled
    }

    AsyncFunction("setXSettings") { homeEnabled: Boolean, videosEnabled: Boolean, homeMinutes: Int ->
      require(homeMinutes in 1..30) { "Home time must be between 1 and 30 minutes" }
      ZenGuardPreferences(requireNotNull(appContext.reactContext)).apply {
        xHomeEnabled = homeEnabled
        xVideosEnabled = videosEnabled
        xHomeMinutes = homeMinutes
        val required = (if (homeEnabled) 1 else 0) or (if (videosEnabled) 2 else 0)
        if (xSignalMask and required != required) xObservationMode = true
      }
      Unit
    }

    AsyncFunction("setXObservationMode") { enabled: Boolean ->
      val preferences = ZenGuardPreferences(requireNotNull(appContext.reactContext))
      val required = (if (preferences.xHomeEnabled) 1 else 0) or (if (preferences.xVideosEnabled) 2 else 0)
      check(enabled || preferences.xSignalMask and required == required) { "Open X Home and one video before starting protection" }
      preferences.xObservationMode = enabled
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
      Unit
    }
  }

  /** Human-readable app name, falling back to the package when it cannot be resolved. */
  private fun labelFor(packageManager: PackageManager, packageName: String): String = try {
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
  } catch (_: PackageManager.NameNotFoundException) {
    packageName
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
    private const val REQUIRED_INSTAGRAM_SIGNALS = 3
  }
}
