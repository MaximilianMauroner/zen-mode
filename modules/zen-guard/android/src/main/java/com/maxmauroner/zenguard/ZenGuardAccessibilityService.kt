package com.maxmauroner.zenguard

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

class ZenGuardAccessibilityService : AccessibilityService() {
  private lateinit var preferences: ZenGuardPreferences
  private val stats = InterventionStats(null)
  private val youtube = YouTubeOwner(this, stats)
  private val x = XOwner(this, stats, canEnforce = ::canRunEnforcementAction)
  private val instagram = InstagramOwner(this, stats, canEnforce = ::canRunEnforcementAction)
  private val wholeApp = WholeAppOwner(
    service = this,
    stats = stats,
    canEnforce = ::canRunEnforcementAction,
    onForegroundChanged = { packageName ->
      if (packageName != YOUTUBE_PACKAGE && packageName != this.packageName) youtube.reset()
    },
    onCardAttached = { instagram.pause() },
    onRelease = { packageName, root ->
      when {
        BrowserUrlDetector.supports(packageName) -> browserIntervention?.observe(packageName, root)
        packageName == INSTAGRAM_PACKAGE -> instagram.observe(null, root)
        packageName == YOUTUBE_PACKAGE -> youtube.observe(null, root)
      }
    },
  )
  private lateinit var appRuleSafety: AppRuleSafety
  private var browserIntervention: BrowserIntervention? = null
  private var screenReceiverRegistered = false
  private val screenStateReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action != Intent.ACTION_SCREEN_OFF || !hasActiveProtection()) return
      val nowMs = SystemClock.elapsedRealtime()
      x.screenOff(nowMs)
      instagram.screenOff(nowMs)
      x.publishHomeStatus()
      instagram.publishHomeStatus()
    }
  }

  override fun onServiceConnected() {
    super.onServiceConnected()
    preferences = ZenGuardPreferences(this)
    stats.replaceStore(
      try {
        EnforcementStatsStore(this)
      } catch (_: RuntimeException) {
        null
      },
    )
    wholeApp.createAppLimitStore()
    instagram.createTally()
    wholeApp.createIntentAndRollingStores()
    appRuleSafety = AppRuleSafety.resolve(this)
    wholeApp.onServiceConnected(appRuleSafety)
    browserIntervention?.end()
    browserIntervention = BrowserIntervention(
      service = this,
      stats = stats,
      canEnforce = ::canRunEnforcementAction,
    )
    youtube.onServiceConnected(preferences)
    val homeFeedStatusStore = HomeFeedStatusStore(this)
    val nowElapsedMs = SystemClock.elapsedRealtime()
    instagram.restore(preferences, homeFeedStatusStore, nowElapsedMs)
    x.restore(preferences, homeFeedStatusStore, nowElapsedMs)
    instagram.publishHomeStatus()
    val screenFilter = IntentFilter(Intent.ACTION_SCREEN_OFF)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      registerReceiver(screenStateReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED)
    } else {
      @Suppress("DEPRECATION")
      registerReceiver(screenStateReceiver, screenFilter)
    }
    screenReceiverRegistered = true
    wholeApp.start()
    x.start()
    instagram.start()
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (!::preferences.isInitialized || event == null) return
    if (!hasActiveProtection()) {
      clearInactiveProtection()
      return
    }

    val eventPackage = event.packageName?.toString()
    if (::appRuleSafety.isInitialized && appRuleSafety.hasSystemSettingsWindow(
        activePackage = rootInActiveWindow?.packageName?.toString(),
        windowPackages = windows.map { it.root?.packageName?.toString() } + eventPackage,
      )
    ) {
      if (appRuleSafety.isSystemSettings(eventPackage) &&
        event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
      ) {
        wholeApp.bankSettingsForeground(eventPackage!!)
      }
      enterSystemSettings()
      return
    }

    wholeApp.trackForegroundApp(event)
    if (wholeApp.hasRequestedCard) return
    // The blocker is an accessibility overlay owned by this package. Ignore its own focus and
    // content events; treating them as an external app would immediately remove the blocker.
    if (eventPackage == packageName) {
      if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
        if (windows.none { it.root?.packageName?.toString() == YOUTUBE_PACKAGE }) {
          youtube.reset()
        }
        if (windows.none { it.root?.packageName?.toString() == X_PACKAGE }) {
          x.pause()
        }
        if (windows.none { it.root?.packageName?.toString() == INSTAGRAM_PACKAGE }) {
          instagram.pause()
        }
      }
      if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
        windows.none { BrowserUrlDetector.supports(it.root?.packageName?.toString()) }
      ) browserIntervention?.end()
      return
    }
    if (BrowserUrlDetector.supports(eventPackage)) {
      browserIntervention?.observe(eventPackage!!)
    } else if ((event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
        event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) &&
      windows.none { BrowserUrlDetector.supports(it.root?.packageName?.toString()) }
    ) {
      browserIntervention?.end()
    }
    if (eventPackage != X_PACKAGE && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
      windows.none { it.root?.packageName?.toString() == X_PACKAGE }) x.pause()

    when (eventPackage) {
      YOUTUBE_PACKAGE -> {
        instagram.pause(InstagramPause.DEPARTED)
        youtube.observe(event)
      }
      INSTAGRAM_PACKAGE -> instagram.observe(event)
      X_PACKAGE -> {
        instagram.pause()
        x.observe(event)
      }
      else -> instagram.observeOtherApp(event)
    }
  }

  override fun onInterrupt() {
    wholeApp.closeAppRuleOverlay()
    browserIntervention?.end()
    x.pause()
    youtube.reset()
    stats.clear()
    instagram.pause()
  }

  override fun onDestroy() {
    wholeApp.cancelVisitDeadline()
    browserIntervention?.end()
    youtube.reset()
    x.destroy(hasActiveProtection())
    instagram.cancelNavigation()
    wholeApp.stopUsageAndCloseCard()
    instagram.destroy(hasActiveProtection())
    stats.clear()
    if (screenReceiverRegistered) {
      unregisterReceiver(screenStateReceiver)
      screenReceiverRegistered = false
    }
    super.onDestroy()
  }

  private fun hasActiveProtection(): Boolean =
    ::preferences.isInitialized && preferences.hasCurrentConsent && preferences.protectionEnabled

  /** Clear every in-memory action path without inspecting another app's screen. */
  private fun clearInactiveProtection() {
    browserIntervention?.end()
    youtube.reset()
    stats.clear()
    x.disable()
    wholeApp.disable()
    instagram.disable()
  }

  /**
   * Android Settings is the user's unconditional escape path from this service. Clear overlays,
   * delayed navigation, and tracked app-rule state without changing the in-app settings lock.
   */
  private fun enterSystemSettings() {
    browserIntervention?.end()
    youtube.reset()
    stats.clear()
    x.pause()
    wholeApp.enterSettings()
    instagram.enterSettings()
  }

  /** Re-check the live windows at execution time so queued callbacks cannot eject Settings. */
  private fun canRunEnforcementAction(): Boolean {
    if (!hasActiveProtection()) {
      clearInactiveProtection()
      return false
    }
    if (::appRuleSafety.isInitialized && appRuleSafety.hasSystemSettingsWindow(
        activePackage = rootInActiveWindow?.packageName?.toString(),
        windowPackages = windows.map { it.root?.packageName?.toString() },
      )
    ) {
      enterSystemSettings()
      return false
    }
    return true
  }

  companion object {
    private const val X_PACKAGE = "com.twitter.android"
    private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    private const val INSTAGRAM_PACKAGE = "com.instagram.android"
  }
}
