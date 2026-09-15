package com.maxmauroner.zenguard

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings

/** Packages that must remain outside every whole-app rule. */
internal class AppRuleSafety private constructor(
  private val appPackage: String,
  private val systemSettingsPackages: Set<String>,
) {
  fun isExempt(packageName: String?): Boolean =
    packageName == null || packageName == appPackage || isSystemSettings(packageName)

  fun isSystemSettings(packageName: String?): Boolean = packageName in systemSettingsPackages

  fun allowsDailyEnforcement(packageName: String?): Boolean = !isExempt(packageName)

  fun allowsTimedVisitEnforcement(packageName: String?): Boolean = !isExempt(packageName)

  fun allowsRollingEnforcement(packageName: String?): Boolean = !isExempt(packageName)

  /** Settings can sit underneath Zen Mode's own accessibility overlay before its event arrives. */
  fun hasSystemSettingsWindow(activePackage: String?, windowPackages: Iterable<String?>): Boolean =
    isSystemSettings(activePackage) || windowPackages.any(::isSystemSettings)

  companion object {
    fun resolve(context: Context): AppRuleSafety = AppRuleSafety(
      appPackage = context.packageName,
      systemSettingsPackages = settingsHandlers(context.packageManager),
    )

    internal fun fromPackages(appPackage: String, systemSettingsPackages: Set<String>): AppRuleSafety =
      AppRuleSafety(appPackage, systemSettingsPackages)

    /**
     * Discover Settings from the platform intents Zen Mode actually opens. Requiring a system or
     * updated-system handler avoids exempting a third-party app that merely registers the action.
     */
    private fun settingsHandlers(packageManager: PackageManager): Set<String> = systemHandlerPackages(SETTINGS_ACTIONS
      .asSequence()
      .flatMap { action ->
        val intent = Intent(action)
        sequence {
          yieldAll(packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY))
          val resolved = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
          if (resolved != null) yield(resolved)
        }
      }
      .mapNotNull { resolution ->
        val activity = resolution.activityInfo ?: return@mapNotNull null
        activity.packageName to activity.applicationInfo.flags
      }
      .asIterable())

    internal fun systemHandlerPackages(candidates: Iterable<Pair<String, Int>>): Set<String> = candidates
      .filter { (_, flags) ->
        flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
      }
      .mapTo(mutableSetOf()) { (packageName, _) -> packageName }

    private val SETTINGS_ACTIONS = listOf(
      Settings.ACTION_SETTINGS,
      Settings.ACTION_ACCESSIBILITY_SETTINGS,
      ACTION_ACCESSIBILITY_DETAILS_SETTINGS,
    )

    private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
      "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
  }
}
