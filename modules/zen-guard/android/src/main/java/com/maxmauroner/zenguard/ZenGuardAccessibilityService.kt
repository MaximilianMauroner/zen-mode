package com.maxmauroner.zenguard

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

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
        BrowserUrlDetector.supports(packageName) -> handleBrowserEvent(packageName, root)
        packageName == INSTAGRAM_PACKAGE -> instagram.observe(null, root)
        packageName == YOUTUBE_PACKAGE -> youtube.observe(null, root)
      }
    },
  )
  private lateinit var appRuleSafety: AppRuleSafety
  private lateinit var adultSiteStore: AdultSiteRuleStore
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
    adultSiteStore = AdultSiteRuleStore(this)
    browserIntervention?.end()
    browserIntervention = BrowserIntervention(
      service = this,
      canEnforce = ::canRunEnforcementAction,
      isEnabled = { adultSiteStore.enabled },
      recordAttachment = { stats.record(EnforcementReason.BLOCKED_SITE, EnforcementStatsOutcome.SUCCESS) },
      recheck = { handleBrowserEvent(it) },
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
      handleBrowserEvent(eventPackage!!)
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

  /** Reads text and descriptions only from exact address-bar nodes; page text is never copied. */
  private fun handleBrowserEvent(browserPackage: String, observedRoot: AccessibilityNodeInfo? = null) {
    val intervention = browserIntervention ?: return
    if (!canRunEnforcementAction()) return
    if (!adultSiteStore.enabled) {
      intervention.end()
      return
    }
    val root = observedRoot ?: browserRoot(browserPackage) ?: return
    if (root.packageName?.toString() != browserPackage) return
    val detection = BrowserUrlDetector.detect(browserPackage, browserAddressBars(root, browserPackage)) ?: return
    adultSiteStore.recordBrowserSignal(detection.browserMask)
    intervention.observe(
      browserPackage,
      blocked = AdultSitePolicy.isBlocked(detection.host, adultSiteStore.customHosts()),
    )
  }

  private fun browserRoot(browserPackage: String): AccessibilityNodeInfo? =
    windows.firstNotNullOfOrNull { window ->
      window.root?.takeIf { it.packageName?.toString() == browserPackage }
    } ?: rootInActiveWindow?.takeIf { it.packageName?.toString() == browserPackage }

  private fun browserAddressBars(root: AccessibilityNodeInfo, browserPackage: String): List<BrowserNodeSignal> {
    data class Pending(val node: AccessibilityNodeInfo, val depth: Int)
    val pending = ArrayDeque<Pending>()
    val result = ArrayList<BrowserNodeSignal>(2)
    pending.add(Pending(root, 0))
    var visited = 0
    while (pending.isNotEmpty() && visited++ < MAX_BROWSER_NODES) {
      val (node, depth) = pending.removeFirst()
      if (!node.isVisibleToUser) continue
      val viewId = node.viewIdResourceName.orEmpty()
      if (BrowserUrlDetector.isAddressBar(browserPackage, viewId)) {
        result.add(
          BrowserNodeSignal(viewId, node.text?.toString().orEmpty(), true, node.contentDescription?.toString().orEmpty()),
        )
      }
      if (depth >= MAX_BROWSER_DEPTH) continue
      for (index in 0 until node.childCount) node.getChild(index)?.let { pending.add(Pending(it, depth + 1)) }
    }
    return result
  }

  companion object {
    private const val X_PACKAGE = "com.twitter.android"
    private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    private const val INSTAGRAM_PACKAGE = "com.instagram.android"
    private const val MAX_BROWSER_NODES = 180
    private const val MAX_BROWSER_DEPTH = 18
  }
}
