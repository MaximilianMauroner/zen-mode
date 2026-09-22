package com.maxmauroner.zenguard

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
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
      val adultSites = AdultSiteRuleStore(context)
      val homeFeedStatus = HomeFeedStatusStore(context)
      val packageManager = context.packageManager
      val nowElapsedMs = SystemClock.elapsedRealtime()
      val instagramHome = homeFeedStatus.instagram(nowElapsedMs)
      val xHome = homeFeedStatus.x(nowElapsedMs)
      val instagramBreakRemainingMs = remainingLockoutMs(instagramHome, nowElapsedMs)
      val xBreakRemainingMs = remainingLockoutMs(xHome, nowElapsedMs)
      val instagramUsageState = visibleUsageState(instagramHome)
      val xUsageState = visibleUsageState(xHome)
      val instagramUsedMs = if (instagramUsageState == HomeFeedUsageState.UNKNOWN) 0L else instagramHome.usedMs
      val xUsedMs = if (xUsageState == HomeFeedUsageState.UNKNOWN) 0L else xHome.usedMs
      mapOf(
        "available" to true,
        "serviceEnabled" to isServiceEnabled(context),
        "currentConsent" to preferences.hasCurrentConsent,
        "protectionEnabled" to (preferences.protectionEnabled && preferences.hasCurrentConsent),
        "observationMode" to preferences.observationMode,
        "shortsEnabled" to preferences.shortsEnabled,
        "youtubeHomeEnabled" to preferences.youtubeHomeEnabled,
        "youtubeHomeObserved" to preferences.youtubeHomeObserved,
        // Turn on only with captured fixtures and a verified Android enforcement action.
        "youtubeHomeDetectionSupported" to false,
        "xHomeEnabled" to preferences.xHomeEnabled,
        "xVideosEnabled" to preferences.xVideosEnabled,
        "xHomeMinutes" to preferences.xHomeMinutes,
        "xHomeUsedMs" to xUsedMs.toDouble(),
        "xHomeBreakRemainingMs" to xBreakRemainingMs.toDouble(),
        "xHomeUsageState" to xUsageState.name.lowercase(),
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
        "instagramHomeUsedMs" to instagramUsedMs.toDouble(),
        "instagramHomeBreakRemainingMs" to instagramBreakRemainingMs.toDouble(),
        "instagramHomeUsageState" to instagramUsageState.name.lowercase(),
        "instagramExploreBlocked" to preferences.instagramExploreBlocked,
        "instagramLastDetectionAt" to preferences.instagramLastDetectionAt.toDouble(),
        "instagramDetectionCount" to preferences.instagramDetectionCount,
        "instagramSignalMask" to preferences.instagramSignalMask,
        "instagramLastDetectionReason" to preferences.instagramLastDetectionReason,
        "adultSiteEnabled" to adultSites.enabled,
        "adultSiteCustomCount" to adultSites.customHosts().size,
        "browserSignalMask" to adultSites.browserSignalMask,
        "appAvailability" to mapOf(
          "youtube" to packageAvailability(packageManager, YOUTUBE_PACKAGE),
          "instagram" to packageAvailability(packageManager, INSTAGRAM_PACKAGE),
          "x" to packageAvailability(packageManager, X_PACKAGE),
        ),
        "browserAvailability" to mapOf(
          "chrome" to packageAvailability(packageManager, CHROME_PACKAGE),
          "samsungInternet" to packageAvailability(packageManager, SAMSUNG_INTERNET_PACKAGE),
          "opera" to packageAvailability(packageManager, OPERA_PACKAGE),
          "firefox" to packageAvailability(packageManager, FIREFOX_PACKAGE),
        ),
      )
    }

    AsyncFunction("getEnforcementStats") {
      val snapshot = EnforcementStatsStore(requireNotNull(appContext.reactContext)).snapshot()
      mapOf(
        "total" to snapshot.total.toDouble(),
        "counts" to snapshot.counts.mapValues { (_, count) -> count.toDouble() },
        "lastEventAt" to snapshot.lastEventAt.toDouble(),
      )
    }

    AsyncFunction("getAdultSiteSettings") {
      val store = AdultSiteRuleStore(requireNotNull(appContext.reactContext))
      mapOf(
        "available" to true,
        "enabled" to store.enabled,
        "customHosts" to store.customHosts().sorted(),
        "browserSignalMask" to store.browserSignalMask,
      )
    }

    AsyncFunction("setAdultSiteBlockingEnabled") { enabled: Boolean ->
      AdultSiteRuleStore(requireNotNull(appContext.reactContext)).enabled = enabled
    }

    AsyncFunction("addBlockedDomain") { input: String ->
      AdultSiteRuleStore(requireNotNull(appContext.reactContext)).add(input)
    }

    AsyncFunction("removeBlockedDomain") { host: String ->
      AdultSiteRuleStore(requireNotNull(appContext.reactContext)).remove(host)
    }

    AsyncFunction("openBrowserCheck") {
      val context = requireNotNull(appContext.reactContext)
      val intent = Intent(Intent.ACTION_VIEW, Uri.parse(BROWSER_CHECK_URL)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
    }

    /** Launchable apps, excluding Zen Mode and the system Settings escape path. */
    AsyncFunction("getInstalledApps") {
      val context = requireNotNull(appContext.reactContext)
      val packageManager = context.packageManager
      val appRuleSafety = AppRuleSafety.resolve(context)
      val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
      packageManager.queryIntentActivities(launcher, 0)
        .asSequence()
        .map { it.activityInfo.packageName }
        .distinct()
        .filterNot(appRuleSafety::isExempt)
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
      require(!AppRuleSafety.resolve(context).isExempt(packageName)) { RULE_EXEMPT_MESSAGE }
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
      require(!AppRuleSafety.resolve(context).isExempt(packageName)) { RULE_EXEMPT_MESSAGE }
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
      require(!AppRuleSafety.resolve(context).isExempt(packageName)) { RULE_EXEMPT_MESSAGE }
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
      val preferences = ZenGuardPreferences(context)
      check(!enabled || preferences.hasCurrentConsent) {
        "Accept the current accessibility disclosure before enabling protection"
      }
      preferences.protectionEnabled = enabled
    }

    AsyncFunction("hasCurrentConsent") {
      ZenGuardPreferences(requireNotNull(appContext.reactContext)).hasCurrentConsent
    }

    AsyncFunction("acceptCurrentConsent") {
      check(ZenGuardPreferences(requireNotNull(appContext.reactContext)).acceptCurrentConsent()) {
        "Could not save accessibility consent"
      }
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
      val intent = context.packageManager.getLaunchIntentForPackage(X_PACKAGE)
        ?: throw IllegalStateException("The X app is not installed")
      context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    AsyncFunction("setYouTubeSettings") { shortsEnabled: Boolean, homeEnabled: Boolean ->
      ZenGuardPreferences(requireNotNull(appContext.reactContext)).apply {
        this.shortsEnabled = shortsEnabled
        youtubeHomeEnabled = homeEnabled
      }
      Unit
    }

    /**
     * Enabling a feed never re-enters observation. Each X feed waits for its own
     * observed signal, so turning Videos on cannot pause an already-running Home
     * rule. An unobserved feed simply does not enforce until its signal appears.
     */
    AsyncFunction("setXSettings") { homeEnabled: Boolean, videosEnabled: Boolean, homeMinutes: Int ->
      require(homeMinutes in 1..30) { "Home time must be between 1 and 30 minutes" }
      ZenGuardPreferences(requireNotNull(appContext.reactContext)).apply {
        xHomeEnabled = homeEnabled
        xVideosEnabled = videosEnabled
        xHomeMinutes = homeMinutes
      }
      Unit
    }

    AsyncFunction("setXObservationMode") { enabled: Boolean ->
      val preferences = ZenGuardPreferences(requireNotNull(appContext.reactContext))
      val required = preferences.requiredXSignals()
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

  private fun remainingLockoutMs(status: HomeFeedStatus, nowElapsedMs: Long): Long =
    if (status.lockoutState == HomeFeedLockoutState.ACTIVE) {
      status.blockedUntilElapsedMs?.let { deadline -> if (deadline > nowElapsedMs) deadline - nowElapsedMs else 0L } ?: 0L
    } else {
      0L
    }

  private fun visibleUsageState(status: HomeFeedStatus): HomeFeedUsageState = when {
    status.storageState == HomeFeedStorageState.UNAVAILABLE -> HomeFeedUsageState.UNKNOWN
    status.lockoutState == HomeFeedLockoutState.UNKNOWN -> HomeFeedUsageState.UNKNOWN
    else -> status.usageState
  }

  /** Human-readable app name, falling back to the package when it cannot be resolved. */
  private fun labelFor(packageManager: PackageManager, packageName: String): String = try {
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
  } catch (_: PackageManager.NameNotFoundException) {
    packageName
  }

  /** Package visibility is explicitly limited by the module manifest queries. */
  private fun packageAvailability(packageManager: PackageManager, packageName: String): String = try {
    val application = packageManager.getApplicationInfo(packageName, 0)
    if (application.enabled) "installed" else "disabled"
  } catch (_: PackageManager.NameNotFoundException) {
    "absent"
  } catch (_: SecurityException) {
    "unknown"
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
    private const val RULE_EXEMPT_MESSAGE = "Zen Mode and Android Settings cannot have app rules"
    private const val X_PACKAGE = "com.twitter.android"
    private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    private const val INSTAGRAM_PACKAGE = "com.instagram.android"
    private const val CHROME_PACKAGE = "com.android.chrome"
    private const val SAMSUNG_INTERNET_PACKAGE = "com.sec.android.app.sbrowser"
    private const val OPERA_PACKAGE = "com.opera.browser"
    private const val FIREFOX_PACKAGE = "org.mozilla.firefox"
    private const val REQUIRED_INSTAGRAM_SIGNALS = 3
    private const val BROWSER_CHECK_URL = "https://example.com"
  }
}
