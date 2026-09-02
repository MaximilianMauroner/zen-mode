package com.maxmauroner.zenguard

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Builds only intents that Android can resolve for the installed Instagram package.
 *
 * Accessibility services and modules both need to start Instagram from a non-activity context,
 * so every returned intent carries [Intent.FLAG_ACTIVITY_NEW_TASK].
 */
internal object InstagramIntentHelper {
  const val PACKAGE_NAME = "com.instagram.android"

  private const val APP_URI = "instagram://user"
  private const val ACTIVITY_FLAGS =
    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP

  /**
   * Returns a verified intent for the app's main entry point.
   *
   * Instagram has changed its deep-link handling across releases. Prefer the app route when it
   * is registered, then fall back to the package launch activity when it is not.
  */
  fun launchIntent(packageManager: PackageManager): Intent? {
    return launchCandidates(packageManager).firstOrNull()
  }

  /**
   * Starts Instagram, retrying the verified package entry point if the deep link becomes stale
   * between resolution and launch (for example while Instagram is being updated).
   */
  fun openInstagram(context: Context): Boolean {
    val packageManager = context.packageManager
    return launchCandidates(packageManager).any { startActivity(context, it) }
  }

  /** Starts Instagram's package entry point without relying on a version-specific deep link. */
  fun openMain(context: Context): Boolean {
    val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME)
      ?.apply {
        setPackage(PACKAGE_NAME)
        addFlags(ACTIVITY_FLAGS or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      }
    return startActivity(context, intent)
  }

  private fun launchCandidates(packageManager: PackageManager): List<Intent> {
    val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse(APP_URI)).apply {
      setPackage(PACKAGE_NAME)
    }
    val packageLaunch = packageManager.getLaunchIntentForPackage(PACKAGE_NAME)?.apply {
      setPackage(PACKAGE_NAME)
    }
    // The package launcher is the only entry point guaranteed to resume the installed Instagram
    // task. A generic `instagram://user` deep link can resolve to a browser/URL handler and report
    // success without changing the visible Instagram screen, so use it only as a fallback.
    return listOf(packageLaunch, deepLink)
      .mapNotNull { verified(it, packageManager) }
  }

  private fun verified(intent: Intent?, packageManager: PackageManager): Intent? {
    return intent
      ?.takeIf { it.resolveActivity(packageManager) != null }
      ?.apply { addFlags(ACTIVITY_FLAGS) }
  }

  private fun startActivity(context: Context, intent: Intent?): Boolean {
    if (intent == null) return false

    return try {
      context.startActivity(intent)
      true
    } catch (_: ActivityNotFoundException) {
      false
    } catch (_: SecurityException) {
      false
    } catch (_: IllegalArgumentException) {
      false
    }
  }
}
