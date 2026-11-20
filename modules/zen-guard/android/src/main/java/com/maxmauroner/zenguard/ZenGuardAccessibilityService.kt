package com.maxmauroner.zenguard

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject

class ZenGuardAccessibilityService : AccessibilityService() {
  private lateinit var preferences: ZenGuardPreferences
  private val heartbeatHandler = Handler(Looper.getMainLooper())
  private val heartbeat = object : Runnable {
    override fun run() {
      AccessibilityAccess.heartbeat(this@ZenGuardAccessibilityService)
      heartbeatHandler.postDelayed(this, AccessibilityAccess.HEARTBEAT_INTERVAL_MS)
    }
  }
  private val stats = InterventionStats(null) { reason, outcome ->
    if (Diagnostics.isRecording(this)) Diagnostics.record(this, "intervention", JSONObject()
      .put("reason", reason.key).put("outcome", outcome.name))
  }
  private val youtube: YouTubeOwner = YouTubeOwner(this, stats, canEnforce = {
    !wholeApp.hasRequestedCard && canRunEnforcementAction()
  })
  private val x = XOwner(this, stats, canEnforce = ::canRunEnforcementAction)
  private val instagram = InstagramOwner(this, stats, canEnforce = ::canRunEnforcementAction)
  private val wholeApp = WholeAppOwner(
    service = this,
    stats = stats,
    canEnforce = ::canRunEnforcementAction,
    onForegroundChanged = { packageName ->
      if (packageName != YOUTUBE_PACKAGE && packageName != this.packageName) youtube.reset()
    },
    onCardAttached = {
      youtube.cancelHomeVisit()
      instagram.pause()
    },
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
  private var systemReceiverRegistered = false
  private val systemReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == Intent.ACTION_SHUTDOWN) {
        AccessibilityAccess.recordCleanShutdown(this@ZenGuardAccessibilityService)
        return
      }
      if (intent?.action != Intent.ACTION_SCREEN_OFF || !hasActiveProtection()) return
      youtube.cancelHomeVisit()
      val nowMs = SystemClock.elapsedRealtime()
      x.screenOff(nowMs)
      instagram.screenOff(nowMs)
      x.publishHomeStatus()
      instagram.publishHomeStatus()
    }
  }

  override fun onServiceConnected() {
    super.onServiceConnected()
    AccessibilityAccess.connected(this)
    heartbeatHandler.removeCallbacks(heartbeat)
    heartbeatHandler.postDelayed(heartbeat, AccessibilityAccess.HEARTBEAT_INTERVAL_MS)
    runCatching { Diagnostics.service(this, true, "connected") }
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
    wholeApp.createAppRuleStores()
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
    if (!systemReceiverRegistered) {
      val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SHUTDOWN) }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        registerReceiver(systemReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
      } else {
        @Suppress("DEPRECATION")
        registerReceiver(systemReceiver, filter)
      }
      systemReceiverRegistered = true
    }
    wholeApp.start()
    x.start()
    instagram.start()
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (!::preferences.isInitialized || event == null) return
    if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
      val foreground = when (val name = event.packageName?.toString()) {
        X_PACKAGE -> "x"
        YOUTUBE_PACKAGE -> "youtube"
        INSTAGRAM_PACKAGE -> "instagram"
        packageName -> "zen"
        else -> if (BrowserUrlDetector.supports(name)) "browser" else "other"
      }
      runCatching { Diagnostics.record(this, "foreground", JSONObject().put("app", foreground)) }
    }
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

    val appRuleEnforced = wholeApp.trackForegroundApp(event)
    if (rootInActiveWindow?.packageName?.toString() != YOUTUBE_PACKAGE) youtube.cancelHomeVisit()
    if (appRuleEnforced || wholeApp.hasRequestedCard || wholeApp.isClosingPackage(eventPackage)) return
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
        if (!wholeApp.observeYouTubeVisit()) youtube.observe(event)
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
    runCatching { Diagnostics.service(this, true, "interrupt") }
    wholeApp.closeAppRuleOverlay()
    browserIntervention?.end()
    x.pause()
    youtube.reset()
    stats.clear()
    instagram.pause()
  }

  override fun onUnbind(intent: Intent?): Boolean {
    heartbeatHandler.removeCallbacks(heartbeat)
    unregisterSystemReceiver()
    AccessibilityAccess.disconnected(this)
    return super.onUnbind(intent)
  }

  override fun onDestroy() {
    heartbeatHandler.removeCallbacks(heartbeat)
    if (::preferences.isInitialized) AccessibilityAccess.disconnected(this)
    runCatching { Diagnostics.service(this, false, "destroy") }
    wholeApp.cancelVisitDeadline()
    browserIntervention?.end()
    youtube.destroy()
    x.destroy(hasActiveProtection())
    instagram.cancelNavigation()
    wholeApp.stopUsageAndCloseCard()
    instagram.destroy(hasActiveProtection())
    stats.clear()
    unregisterSystemReceiver()
    super.onDestroy()
  }

  private fun unregisterSystemReceiver() {
    if (!systemReceiverRegistered) return
    unregisterReceiver(systemReceiver)
    systemReceiverRegistered = false
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
