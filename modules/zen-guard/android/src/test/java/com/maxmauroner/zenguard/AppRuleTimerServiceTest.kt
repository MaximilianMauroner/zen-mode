package com.maxmauroner.zenguard

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.TextView
import java.time.Duration
import java.time.Instant
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import org.robolectric.shadows.ShadowWindowManagerImpl

/** Exercise the actual service with its own overlay above the guarded app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [AppRuleTimerServiceTest.AppWindowNodeShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class AppRuleTimerServiceTest {
  private lateinit var context: Context
  private lateinit var controller: ServiceController<ZenGuardAccessibilityService>
  private lateinit var originalZone: TimeZone
  private var destroyed = false

  /** Window roots are cloned, so view-ID queries must work on the cloned shadow tree too. */
  @Implements(AccessibilityNodeInfo::class)
  class AppWindowNodeShadow : ShadowAccessibilityNodeInfo() {
    @RealObject private lateinit var node: AccessibilityNodeInfo

    @Implementation
    protected fun findAccessibilityNodeInfosByViewId(viewId: String): MutableList<AccessibilityNodeInfo> {
      val matches = mutableListOf<AccessibilityNodeInfo>()
      fun visit(current: AccessibilityNodeInfo) {
        if (current.viewIdResourceName == viewId) matches.add(current)
        for (index in 0 until current.childCount) current.getChild(index)?.let(::visit)
      }
      visit(node)
      return matches
    }
  }

  @Before fun setUp() {
    originalZone = TimeZone.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    context = RuntimeEnvironment.getApplication()
    HomeFeedStatusFailureRegistry.clearForTests()
    Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 7)
    ZenGuardPreferences(context).apply {
      acceptCurrentConsent()
      protectionEnabled = true
    }
    shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
    controller = Robolectric.buildService(ZenGuardAccessibilityService::class.java).create()
    val startElapsed = SystemClock.elapsedRealtime()
    val startWall = Instant.parse("2026-10-13T23:59:55Z").toEpochMilli()
    controller.get().appRuleWallClock = { startWall + SystemClock.elapsedRealtime() - startElapsed }
    controller.get().javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(controller.get())
  }

  @After fun tearDown() {
    if (!destroyed) controller.destroy()
    TimeZone.setDefault(originalZone)
    HomeFeedStatusFailureRegistry.clearForTests()
  }

  @Test fun `rolling card updates and releases the same app at the first regained access without events`() {
    val rolling = RollingLimitStore(context)
    rolling.setRule(APP, 1, 15)
    val wallNow = wallNow()
    rolling.addUsage(APP, 60_000L, wallNow - 900_000L + 5_000L)
    rolling.addUsage(APP, 20_000L, wallNow)
    openApp(APP)
    showOverlayWindow(APP)
    assertTrue(texts().contains("Available again in 0:05"))
    val usageWhileBlocked = rolling.usedMs(APP, wallNow())

    advance(Duration.ofSeconds(1))
    assertTrue(texts().contains("Available again in 0:04"))
    assertEquals(usageWhileBlocked, rolling.usedMs(APP, wallNow()))
    assertEquals(1L, count(EnforcementReason.ROLLING_LIMIT))

    advance(Duration.ofSeconds(5))
    assertNull(overlay().requestedPackage())
    assertFalse(rolling.isOver(APP, wallNow()))
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())
  }

  @Test fun `daily midnight reset closes an idle card and does not charge blocked time`() {
    val daily = AppLimitStore(context)
    daily.setLimit(APP, 1)
    daily.addUsage(APP, 60_000L, wallNow())
    openApp(APP)
    showOverlayWindow(APP)
    assertTrue(texts().contains("Available again in 0:05"))
    assertEquals(1L, count(EnforcementReason.APP_LIMIT))

    advance(Duration.ofSeconds(6))
    assertNull(overlay().requestedPackage())
    assertFalse(daily.isOverBudget(APP, wallNow()))
    assertEquals(0L, daily.usageToday(wallNow())[APP] ?: 0L)
  }

  @Test fun `overlapping daily rolling and downtime rules release only when all have cleared`() {
    val now = wallNow()
    AppLimitStore(context).apply { setLimit(APP, 1); addUsage(APP, 60_000L, now) }
    RollingLimitStore(context).apply {
      setRule(APP, 1, 15)
      addUsage(APP, 60_000L, now - 900_000L + 10_000L)
    }
    IntentAppStore(context).apply { setIntent(APP, 1, 1); recordSessionEnd(APP, now - 45_000L) }
    openApp(APP)
    showOverlayWindow(APP)
    assertTrue(texts().contains("Available again in 0:15"))
    assertEquals(1L, count(EnforcementReason.APP_LIMIT))
    assertEquals(0L, count(EnforcementReason.ROLLING_LIMIT))
    assertEquals(1L, EnforcementStatsStore(context).snapshot().total)

    advance(Duration.ofSeconds(6))
    assertEquals(AppRuleOverlayMode.LIMIT, overlay().mode)
    val diagnostic = "texts=${texts()}; wall=${wallNow()}; elapsed=${SystemClock.elapsedRealtime()}; " +
      "dailyAt=${AppLimitStore(context).availableAgainAt(APP, wallNow())}; " +
      "rollingAt=${RollingLimitStore(context).availableAgainAt(APP, wallNow())}; " +
      "cooldownMs=${IntentAppStore(context).cooldownRemainingMs(APP, wallNow())}"
    // The one-second display callback can sample just before this arbitrary read.
    assertTrue(diagnostic, texts().any { it == "Available again in 0:09" || it == "Available again in 0:10" })
    advance(Duration.ofSeconds(5))
    assertEquals(AppRuleOverlayMode.COOLDOWN, overlay().mode)
    advance(Duration.ofSeconds(5))
    assertEquals(AppRuleOverlayMode.ASK, overlay().mode)
    assertTrue(texts().contains("Start 1 min"))
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())
  }

  @Test fun `untouched timed visit ends with a live downtime card then offers Start`() {
    IntentAppStore(context).setIntent(APP, 1, 1)
    openApp(APP)
    showOverlayWindow(APP)
    assertEquals(1L, count(EnforcementReason.TIMED_VISIT))
    views().filterIsInstance<Button>().single { it.text == "Start 1 min" }.performClick()
    assertNull(overlay().requestedPackage())
    showAppWindows(APP)

    advance(Duration.ofMillis(60_020L))
    showOverlayWindow(APP)
    assertEquals(AppRuleOverlayMode.COOLDOWN, overlay().mode)
    assertTrue(texts().contains("Available again in 1:00"))
    assertEquals(2L, count(EnforcementReason.TIMED_VISIT))
    advance(Duration.ofSeconds(1))
    assertTrue(texts().contains("Available again in 0:59"))
    assertEquals(2L, count(EnforcementReason.TIMED_VISIT))
    advance(Duration.ofSeconds(60))
    assertEquals(AppRuleOverlayMode.ASK, overlay().mode)
    assertTrue(texts().contains("Start 1 min"))
  }

  @Test fun `overlay focus preserves the card and launcher escape closes it without a navigation action`() {
    IntentAppStore(context).setIntent(APP, 1, 1)
    openApp(APP)
    showOverlayWindow(APP)
    windowEvent(context.packageName)
    advance(Duration.ofSeconds(1))
    assertEquals(APP, overlay().requestedPackage())
    assertEquals(1L, count(EnforcementReason.TIMED_VISIT))

    showAppWindows("com.android.launcher3")
    advance(Duration.ofSeconds(1))
    assertNull(overlay().requestedPackage())
    advance(Duration.ofSeconds(20))
    assertNull(overlay().requestedPackage())
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())
  }

  @Test fun `an app card pauses and persists Instagram Home until a fresh Home observation`() {
    ZenGuardPreferences(context).apply {
      instagramObservationMode = false
      instagramHomeMinutes = 1
    }
    observeInstagramHome()
    advance(Duration.ofSeconds(20))
    observeInstagramHome()
    advance(Duration.ofSeconds(10))
    IntentAppStore(context).setIntent(INSTAGRAM, 1, 1)
    openApp(INSTAGRAM)
    showOverlayWindow(INSTAGRAM)

    val policy = controller.get().privateField<InstagramGuardStateMachine>("instagramStateMachine")
    val paused = policy.homeRuntimeState(SystemClock.elapsedRealtime())
    assertEquals(HomeFeedUsageState.PAUSED, paused.usageState)
    assertTrue(paused.usedMs in 30_000L..30_100L)
    assertEquals(paused.usedMs, HomeFeedStatusStore(context).instagram().usedMs)

    advance(Duration.ofSeconds(40))
    assertEquals(paused.usedMs, policy.homeRuntimeState(SystemClock.elapsedRealtime()).usedMs)
    assertEquals(paused.usedMs, HomeFeedStatusStore(context).instagram().usedMs)
    views().filterIsInstance<Button>().single { it.text == "Start 1 min" }.performClick()
    observeInstagramHome()
    val resumed = policy.homeRuntimeState(SystemClock.elapsedRealtime())
    assertEquals(paused.usedMs, resumed.usedMs)
    assertEquals(HomeFeedUsageState.ACTIVE, resumed.usageState)
    assertNull(policy.debugState(SystemClock.elapsedRealtime()).blockerReason)

    advance(Duration.ofSeconds(2))
    observeInstagramHome()
    assertEquals(paused.usedMs + 2_000L, HomeFeedStatusStore(context).instagram().usedMs)
  }

  @Test fun `an app card preserves an active Instagram Home rest`() {
    ZenGuardPreferences(context).apply {
      instagramObservationMode = false
      instagramHomeMinutes = 1
    }
    observeInstagramHome()
    advance(Duration.ofSeconds(61))
    observeInstagramHome()
    val policy = controller.get().privateField<InstagramGuardStateMachine>("instagramStateMachine")
    val resting = policy.homeRuntimeState(SystemClock.elapsedRealtime())
    assertEquals(HomeFeedLockoutState.ACTIVE, resting.lockoutState)

    IntentAppStore(context).setIntent(INSTAGRAM, 1, 1)
    openApp(INSTAGRAM)
    showOverlayWindow(INSTAGRAM)
    val stored = HomeFeedStatusStore(context).instagram()
    assertEquals(resting.blockedUntilElapsedMs, stored.blockedUntilElapsedMs)
    assertEquals(HomeFeedLockoutState.ACTIVE, stored.lockoutState)
    advance(Duration.ofSeconds(10))
    showOverlayWindow(instagramRoot("feed_recycler_view"))
    views().filterIsInstance<Button>().single { it.text == "Start 1 min" }.performClick()
    advance(Duration.ofMillis(20))
    assertEquals(InstagramBlockReason.HOME_LIMIT, policy.debugState(SystemClock.elapsedRealtime()).blockerReason)
    assertEquals(resting.blockedUntilElapsedMs, policy.homeRuntimeState(SystemClock.elapsedRealtime()).blockedUntilElapsedMs)
    assertTrue(controller.get().privateField<InstagramIntervention>("instagramIntervention").isShowing)
  }

  @Test fun `idle budget release applies suppressed Chrome and Firefox site rules`() {
    blockExampleSite()
    for (browser in listOf(CHROME, FIREFOX)) {
      val stopsBefore = count(EnforcementReason.BLOCKED_SITE)
      openExpiringBudget(browser)
      showOverlayWindow(browserRoot(browser))
      contentEvent(browser)
      assertEquals(stopsBefore, count(EnforcementReason.BLOCKED_SITE))

      advance(Duration.ofSeconds(6))

      assertNull(overlay().requestedPackage())
      assertTrue(siteBoundaryShowing())
      assertEquals(stopsBefore + 1L, count(EnforcementReason.BLOCKED_SITE))
      siteButton(R.string.zen_guard_go_home)!!.performClick()
    }
  }

  @Test fun `daily Chrome expiry keeps the card until screen-on site checks without an app event`() {
    blockExampleSite()
    val daily = AppLimitStore(context).apply {
      setLimit(CHROME, 1)
      addUsage(CHROME, 60_000L, wallNow())
    }
    openApp(CHROME)
    showOverlayWindow(browserRoot(CHROME))
    turnScreenOff()

    advance(Duration.ofSeconds(22))

    assertEquals(CHROME, overlay().requestedPackage())
    assertTrue(overlay().isShowing())
    assertTrue(hasPendingAppRuleRefresh())
    assertFalse(daily.isOverBudget(CHROME, wallNow()))
    assertEquals(0L, daily.usageToday(wallNow())[CHROME] ?: 0L)
    assertEquals(0L, count(EnforcementReason.BLOCKED_SITE))
    assertFalse(siteBoundaryShowing())
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())

    shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
    advance(Duration.ofMillis(1_020))

    assertNull(overlay().requestedPackage())
    assertFalse(hasPendingAppRuleRefresh())
    assertTrue(siteBoundaryShowing())
    assertEquals(1L, count(EnforcementReason.BLOCKED_SITE))
  }

  @Test fun `rolling Firefox expiry keeps the card and accounting paused until event-free screen-on`() {
    blockExampleSite()
    openExpiringBudget(FIREFOX)
    showOverlayWindow(browserRoot(FIREFOX))
    turnScreenOff()
    val rolling = RollingLimitStore(context)
    val offDuration = Duration.ofSeconds(22)
    val usageAfterBudgetExpiry = rolling.usedMs(FIREFOX, wallNow() + offDuration.toMillis())

    advance(offDuration)

    assertEquals(FIREFOX, overlay().requestedPackage())
    assertTrue(overlay().isShowing())
    assertTrue(hasPendingAppRuleRefresh())
    assertFalse(rolling.isOver(FIREFOX, wallNow()))
    assertEquals(usageAfterBudgetExpiry, rolling.usedMs(FIREFOX, wallNow()))
    assertEquals(0L, count(EnforcementReason.BLOCKED_SITE))
    assertFalse(siteBoundaryShowing())
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())

    shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
    advance(Duration.ofMillis(1_020))

    assertNull(overlay().requestedPackage())
    assertFalse(hasPendingAppRuleRefresh())
    assertTrue(siteBoundaryShowing())
    assertEquals(1L, count(EnforcementReason.BLOCKED_SITE))
  }

  @Test fun `Start checks the blocked site beneath the app question without a new event`() {
    blockExampleSite()
    IntentAppStore(context).setIntent(CHROME, 1, 1)
    openApp(CHROME)
    showOverlayWindow(browserRoot(CHROME))

    views().filterIsInstance<Button>().single { it.text == "Start 1 min" }.performClick()
    advance(Duration.ofMillis(20))

    assertNull(overlay().requestedPackage())
    assertTrue(siteBoundaryShowing())
    assertEquals(1L, count(EnforcementReason.BLOCKED_SITE))
    assertTrue(controller.get().privateField<IntentSessionTracker>("intentSessions").isActive(CHROME, SystemClock.elapsedRealtime()))
  }

  @Test fun `a queued Start while screen-off keeps the question and does not grant a visit`() {
    blockExampleSite()
    IntentAppStore(context).setIntent(CHROME, 1, 1)
    openApp(CHROME)
    showOverlayWindow(browserRoot(CHROME))
    val start = views().filterIsInstance<Button>().single { it.text == "Start 1 min" }
    turnScreenOff()

    start.performClick()
    advance(Duration.ofMillis(1_020))

    assertEquals(CHROME, overlay().requestedPackage())
    assertEquals(AppRuleOverlayMode.ASK, overlay().mode)
    assertTrue(hasPendingAppRuleRefresh())
    assertFalse(controller.get().privateField<IntentSessionTracker>("intentSessions").isActive(CHROME, SystemClock.elapsedRealtime()))
    assertEquals(0L, count(EnforcementReason.BLOCKED_SITE))
    assertFalse(siteBoundaryShowing())
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())

    shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
    views().filterIsInstance<Button>().single { it.text == "Start 1 min" }.performClick()
    advance(Duration.ofMillis(20))

    assertNull(overlay().requestedPackage())
    assertTrue(siteBoundaryShowing())
    assertTrue(controller.get().privateField<IntentSessionTracker>("intentSessions").isActive(CHROME, SystemClock.elapsedRealtime()))
  }

  @Test fun `a stale Start after card dismissal cannot grant or bill while screen-off`() {
    val rolling = RollingLimitStore(context).apply { setRule(APP, 2, 15) }
    IntentAppStore(context).setIntent(APP, 1, 1)
    openApp(APP)
    showOverlayWindow(APP)
    val start = views().filterIsInstance<Button>().single { it.text == "Start 1 min" }
    views().filterIsInstance<Button>().single { it.text == "Not now" }.performClick()
    assertNull(overlay().requestedPackage())
    showAppWindows(APP)
    val usageBeforeQueuedStart = rolling.usedMs(APP, wallNow())
    turnScreenOff()

    start.performClick()
    advance(Duration.ofSeconds(16))

    assertNull(overlay().requestedPackage())
    assertFalse(controller.get().privateField<IntentSessionTracker>("intentSessions").isActive(APP, SystemClock.elapsedRealtime()))
    assertEquals(usageBeforeQueuedStart, rolling.usedMs(APP, wallNow()))
    assertFalse(hasPendingAppRuleRefresh())
    assertEquals(listOf(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME), shadowOf(controller.get()).globalActionsPerformed)

    shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
    openApp(APP)
    showOverlayWindow(APP)
    views().filterIsInstance<Button>().single { it.text == "Start 1 min" }.performClick()
    assertNull(overlay().requestedPackage())
    assertTrue(controller.get().privateField<IntentSessionTracker>("intentSessions").isActive(APP, SystemClock.elapsedRealtime()))
  }

  @Test fun `a polled switch applies the foreground browser rule without a new event`() {
    blockExampleSite()
    IntentAppStore(context).setIntent(APP, 1, 1)
    openApp(APP)
    showOverlayWindow(browserRoot(CHROME))

    advance(Duration.ofMillis(1_020))

    assertNull(overlay().requestedPackage())
    assertTrue(siteBoundaryShowing())
    assertEquals(1L, count(EnforcementReason.BLOCKED_SITE))
  }

  @Test fun `a polled switch retries release after the actual app window returns without an event`() {
    blockExampleSite()
    IntentAppStore(context).setIntent(APP, 1, 1)
    openApp(APP)
    shadowOf(controller.get()).apply {
      setRootInActiveWindow(browserRoot(CHROME))
      setWindows(emptyList())
    }
    advance(Duration.ofMillis(1_020))
    assertEquals(APP, overlay().requestedPackage())
    assertEquals(0L, count(EnforcementReason.BLOCKED_SITE))

    showOverlayWindow(browserRoot(CHROME))
    advance(Duration.ofMillis(1_020))

    assertNull(overlay().requestedPackage())
    assertTrue(siteBoundaryShowing())
    assertEquals(1L, count(EnforcementReason.BLOCKED_SITE))
  }

  @Test fun `a keyboard window event hands off to the actual Explore app beneath the card`() {
    ZenGuardPreferences(context).instagramObservationMode = false
    IntentAppStore(context).setIntent(APP, 1, 1)
    openApp(APP)
    showOverlayWindow(instagramRoot("explore_grid"))

    // The event belongs to the keyboard, not to Instagram or a fabricated app gesture.
    windowEvent("com.google.android.inputmethod.latin")
    advance(Duration.ofMillis(20))

    assertNull(overlay().requestedPackage())
    assertEquals(InstagramBlockReason.EXPLORE, instagramPolicy().debugState(SystemClock.elapsedRealtime()).blockerReason)
    assertTrue(controller.get().privateField<InstagramIntervention>("instagramIntervention").isShowing)
  }

  @Test fun `app downtime and the next question keep priority over a blocked site`() {
    blockExampleSite()
    IntentAppStore(context).apply { setIntent(CHROME, 1, 1); recordSessionEnd(CHROME, wallNow()) }
    openExpiringBudget(CHROME)
    showOverlayWindow(browserRoot(CHROME))

    advance(Duration.ofSeconds(6))
    assertEquals(AppRuleOverlayMode.COOLDOWN, overlay().mode)
    assertEquals(0L, count(EnforcementReason.BLOCKED_SITE))
    advance(Duration.ofSeconds(60))
    assertEquals(AppRuleOverlayMode.ASK, overlay().mode)
    assertEquals(0L, count(EnforcementReason.BLOCKED_SITE))
  }

  @Test fun `idle budget release guards direct Reels from the actual Instagram window`() {
    assertInstagramBudgetHandoff("clips_viewer_view_pager", InstagramBlockReason.REELS_ENTRY)
    assertEquals(1L, count(EnforcementReason.INSTAGRAM_REELS))
  }

  @Test fun `idle budget release guards Explore from the actual Instagram window`() {
    assertInstagramBudgetHandoff("explore_grid", InstagramBlockReason.EXPLORE)
  }

  @Test fun `screen-off budget expiry guards Reels on wake without an Instagram event`() {
    assertScreenOffInstagramBudgetHandoff("clips_viewer_view_pager", InstagramBlockReason.REELS_ENTRY)
  }

  @Test fun `screen-off budget expiry guards Explore on wake without an Instagram event`() {
    assertScreenOffInstagramBudgetHandoff("explore_grid", InstagramBlockReason.EXPLORE)
  }

  @Test fun `an active timed visit waits for screen-on Explore checks after its budget clears`() {
    ZenGuardPreferences(context).instagramObservationMode = false
    IntentAppStore(context).setIntent(INSTAGRAM, 1, 1)
    openApp(INSTAGRAM)
    showOverlayWindow(INSTAGRAM)
    views().filterIsInstance<Button>().single { it.text == "Start 1 min" }.performClick()
    showAppWindows(INSTAGRAM)
    addExpiringBudget(INSTAGRAM)
    windowEvent(INSTAGRAM)
    advance(Duration.ofMillis(20))
    showOverlayWindow(instagramRoot("explore_grid"))
    turnScreenOff()
    val rolling = RollingLimitStore(context)
    val offDuration = Duration.ofSeconds(6)
    val usageAfterBudgetExpiry = rolling.usedMs(INSTAGRAM, wallNow() + offDuration.toMillis())

    advance(offDuration)

    assertEquals(INSTAGRAM, overlay().requestedPackage())
    assertTrue(hasPendingAppRuleRefresh())
    assertTrue(controller.get().privateField<IntentSessionTracker>("intentSessions").isActive(INSTAGRAM, SystemClock.elapsedRealtime()))
    assertNull(instagramPolicy().debugState(SystemClock.elapsedRealtime()).blockerReason)
    assertFalse(controller.get().privateField<InstagramIntervention>("instagramIntervention").isShowing)
    assertEquals(usageAfterBudgetExpiry, rolling.usedMs(INSTAGRAM, wallNow()))
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())

    shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
    advance(Duration.ofMillis(1_020))

    assertNull(overlay().requestedPackage())
    assertTrue(controller.get().privateField<IntentSessionTracker>("intentSessions").isActive(INSTAGRAM, SystemClock.elapsedRealtime()))
    assertEquals(InstagramBlockReason.EXPLORE, instagramPolicy().debugState(SystemClock.elapsedRealtime()).blockerReason)
    assertTrue(controller.get().privateField<InstagramIntervention>("instagramIntervention").isShowing)
  }

  @Test fun `handoff leaves observation and unknown Instagram surfaces unblocked`() {
    openExpiringBudget(INSTAGRAM)
    showOverlayWindow(instagramRoot("explore_grid"))
    ZenGuardPreferences(context).instagramObservationMode = true
    advance(Duration.ofSeconds(6))
    assertNull(overlay().requestedPackage())
    assertNull(instagramPolicy().debugState(SystemClock.elapsedRealtime()).blockerReason)

    ZenGuardPreferences(context).instagramObservationMode = false
    openExpiringBudget(INSTAGRAM)
    showOverlayWindow(instagramRoot("unrecognized_surface"))
    advance(Duration.ofSeconds(6))
    assertNull(overlay().requestedPackage())
    assertNull(instagramPolicy().debugState(SystemClock.elapsedRealtime()).blockerReason)
    assertFalse(controller.get().privateField<InstagramIntervention>("instagramIntervention").isShowing)
  }

  @Test fun `a background blocked browser is not the root used for a polled switch handoff`() {
    blockExampleSite()
    IntentAppStore(context).setIntent(APP, 1, 1)
    openApp(APP)
    val card = node(context.packageName)
    shadowOf(controller.get()).apply {
      setRootInActiveWindow(card)
      setWindows(listOf(
        window(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, card),
        window(AccessibilityWindowInfo.TYPE_APPLICATION, node(OTHER_APP)),
        window(AccessibilityWindowInfo.TYPE_APPLICATION, browserRoot(CHROME)),
      ))
    }

    advance(Duration.ofMillis(1_020))

    assertNull(overlay().requestedPackage())
    assertEquals(0L, count(EnforcementReason.BLOCKED_SITE))
    assertFalse(siteBoundaryShowing())
  }

  @Test fun `idle handoff exits a Short only when the actual collection row proves an advance`() {
    ZenGuardPreferences(context).apply { observationMode = false; shortsEnabled = true }
    val first = youtubeRoot(0)
    showContentAppWindow(first.first)
    contentEvent(YOUTUBE)
    openExpiringBudget(YOUTUBE)
    val advanced = youtubeRoot(1)
    showOverlayWindow(advanced.first)
    contentEvent(YOUTUBE)

    advance(Duration.ofSeconds(6))

    assertNull(overlay().requestedPackage())
    assertTrue(shadowOf(advanced.second).performedActions.contains(AccessibilityNodeInfo.ACTION_CLICK))
    assertEquals(1, controller.get().privateField<EnforcementStateMachine>("stateMachine").pendingExitPageIndex())
  }

  @Test fun `idle handoff cannot invent a Shorts advance when collection metadata is absent`() {
    ZenGuardPreferences(context).apply { observationMode = false; shortsEnabled = true }
    showContentAppWindow(youtubeRoot(0).first)
    contentEvent(YOUTUBE)
    openExpiringBudget(YOUTUBE)
    val unknownPage = youtubeRoot(null)
    showOverlayWindow(unknownPage.first)

    advance(Duration.ofSeconds(6))

    assertNull(overlay().requestedPackage())
    assertTrue(shadowOf(unknownPage.second).performedActions.isEmpty())
    assertNull(controller.get().privateField<EnforcementStateMachine>("stateMachine").pendingExitPageIndex())
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())
  }

  @Test fun `a polled app switch immediately enforces the new daily and rolling budgets`() {
    AppLimitStore(context).apply { setLimit(OTHER_APP, 1); addUsage(OTHER_APP, 60_000L, wallNow()) }
    RollingLimitStore(context).apply { setRule(OTHER_APP, 1, 15); addUsage(OTHER_APP, 60_000L, wallNow()) }

    switchUnderCard(OTHER_APP)

    assertEquals(AppRuleOverlayMode.LIMIT, overlay().mode)
    assertEquals(1L, count(EnforcementReason.APP_LIMIT))
    assertEquals(0L, count(EnforcementReason.ROLLING_LIMIT))
  }

  @Test fun `a polled app switch immediately enforces the new rolling allowance`() {
    RollingLimitStore(context).apply { setRule(OTHER_APP, 1, 15); addUsage(OTHER_APP, 60_000L, wallNow()) }

    switchUnderCard(OTHER_APP)

    assertEquals(AppRuleOverlayMode.LIMIT, overlay().mode)
    assertEquals(1L, count(EnforcementReason.ROLLING_LIMIT))
  }

  @Test fun `a polled app switch asks before starting a new timed visit`() {
    IntentAppStore(context).setIntent(OTHER_APP, 2, 1)

    switchUnderCard(OTHER_APP)

    assertEquals(AppRuleOverlayMode.ASK, overlay().mode)
    assertTrue(texts().contains("Start 2 min"))
    assertFalse(controller.get().privateField<IntentSessionTracker>("intentSessions").isActive(OTHER_APP, SystemClock.elapsedRealtime()))
    assertEquals(2L, count(EnforcementReason.TIMED_VISIT))
  }

  @Test fun `a polled app switch respects the new app downtime`() {
    IntentAppStore(context).apply { setIntent(OTHER_APP, 1, 1); recordSessionEnd(OTHER_APP, wallNow()) }

    switchUnderCard(OTHER_APP)

    assertEquals(AppRuleOverlayMode.COOLDOWN, overlay().mode)
    assertTrue(texts().toString(), texts().any { it.startsWith("Available again in 0:") })
    assertFalse(texts().any { it.startsWith("Start ") })
    assertEquals(2L, count(EnforcementReason.TIMED_VISIT))
  }

  @Test fun `Android Settings is available beneath a card before its event arrives`() {
    IntentAppStore(context).setIntent(APP, 1, 1)
    openApp(APP)
    showOverlayWindow(APP)
    controller.get().javaClass.getDeclaredField("appRuleSafety").apply { isAccessible = true }
      .set(controller.get(), AppRuleSafety.fromPackages(context.packageName, setOf("com.android.settings")))
    showOverlayWindow("com.android.settings")
    advance(Duration.ofSeconds(1))
    assertNull(overlay().requestedPackage())
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())
  }

  @Test fun `global pause clears a card and cancels its refresh callback`() {
    IntentAppStore(context).setIntent(APP, 1, 1)
    openApp(APP)
    showOverlayWindow(APP)
    ZenGuardPreferences(context).protectionEnabled = false
    advance(Duration.ofSeconds(1))
    assertNull(overlay().requestedPackage())
    val handler = controller.get().javaClass.getDeclaredMethod("getAppRuleHandler").apply { isAccessible = true }.invoke(controller.get()) as android.os.Handler
    val callback = controller.get().privateField<Runnable>("appRuleRefresh")
    assertFalse(handler.hasCallbacks(callback))
  }

  @Test fun `Settings while screen-off clears a card and rejects its queued Start`() {
    IntentAppStore(context).setIntent(APP, 1, 1)
    openApp(APP)
    showOverlayWindow(APP)
    val start = views().filterIsInstance<Button>().single { it.text == "Start 1 min" }
    turnScreenOff()
    controller.get().javaClass.getDeclaredField("appRuleSafety").apply { isAccessible = true }
      .set(controller.get(), AppRuleSafety.fromPackages(context.packageName, setOf("com.android.settings")))
    showOverlayWindow("com.android.settings")

    start.performClick()
    advance(Duration.ofSeconds(1))

    assertNull(overlay().requestedPackage())
    assertFalse(hasPendingAppRuleRefresh())
    assertFalse(controller.get().privateField<IntentSessionTracker>("intentSessions").isActive(APP, SystemClock.elapsedRealtime()))
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())
  }

  @Test fun `global pause while screen-off clears a card and rejects its queued Start`() {
    IntentAppStore(context).setIntent(APP, 1, 1)
    openApp(APP)
    showOverlayWindow(APP)
    val start = views().filterIsInstance<Button>().single { it.text == "Start 1 min" }
    turnScreenOff()
    ZenGuardPreferences(context).protectionEnabled = false

    start.performClick()
    advance(Duration.ofSeconds(1))

    assertNull(overlay().requestedPackage())
    assertFalse(hasPendingAppRuleRefresh())
    assertFalse(controller.get().privateField<IntentSessionTracker>("intentSessions").isActive(APP, SystemClock.elapsedRealtime()))
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())
  }

  @Test fun `an early timed deadline still blocks at the actual grant expiry`() {
    startTimedVisit()
    advance(Duration.ofMillis(59_999L))
    val deadline = controller.get().privateField<Runnable>("intentDeadlineCheck")
    val handler = controller.get().javaClass.getDeclaredMethod("getAppRuleHandler").apply { isAccessible = true }
      .invoke(controller.get()) as android.os.Handler
    // Model the platform dispatching and consuming the deadline one millisecond early.
    handler.removeCallbacks(deadline)
    deadline.run()
    advance(Duration.ofMillis(20L))
    assertEquals(AppRuleOverlayMode.COOLDOWN, overlay().mode)
    assertTrue(texts().contains("Available again in 1:00"))
  }

  @Test fun `a combined card predicts the cooldown that starts before its budget clears`() {
    startTimedVisit()
    advance(Duration.ofSeconds(55))
    val now = wallNow()
    RollingLimitStore(context).apply {
      setRule(APP, 1, 15)
      addUsage(APP, 60_000L, now - 900_000L + 10_000L)
    }
    windowEvent(APP)
    advance(Duration.ofMillis(20))
    showOverlayWindow(APP)
    assertTrue(texts().toString(), texts().contains("Available again in 1:05"))
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())
  }

  @Test fun `a budget clearing at grant expiry includes the impending downtime`() {
    startTimedVisit()
    advance(Duration.ofSeconds(55))
    val now = wallNow()
    RollingLimitStore(context).apply {
      setRule(APP, 1, 15)
      addUsage(APP, 60_000L, now - 900_000L + 5_000L)
    }
    windowEvent(APP)
    advance(Duration.ofMillis(20))
    showOverlayWindow(APP)
    assertTrue(texts().toString(), texts().contains("Available again in 1:05"))
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())
  }

  @Test fun `a budget that clears before the grant ends resumes the active visit`() {
    startTimedVisit()
    advance(Duration.ofSeconds(50))
    val now = wallNow()
    RollingLimitStore(context).apply {
      setRule(APP, 1, 15)
      addUsage(APP, 60_000L, now - 900_000L + 5_000L)
    }
    windowEvent(APP)
    advance(Duration.ofMillis(20))
    showOverlayWindow(APP)
    assertTrue(texts().toString(), texts().contains("Available again in 0:05"))
    advance(Duration.ofSeconds(6))
    assertNull(overlay().requestedPackage())
    assertTrue(controller.get().privateField<IntentSessionTracker>("intentSessions").isActive(APP, SystemClock.elapsedRealtime()))
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())
  }

  @Test fun `stored downtime cannot overstate access while an active grant survives a wall-clock change`() {
    IntentAppStore(context).recordSessionEnd(APP, wallNow() - 61_000L)
    startTimedVisit()
    advance(Duration.ofSeconds(50))
    val previousClock = controller.get().appRuleWallClock
    controller.get().appRuleWallClock = { previousClock() - 70_000L }
    assertTrue(IntentAppStore(context).cooldownRemainingMs(APP, wallNow()) > 0L)
    RollingLimitStore(context).apply {
      setRule(APP, 1, 15)
      addUsage(APP, 60_000L, wallNow() - 900_000L + 5_000L)
    }
    windowEvent(APP)
    advance(Duration.ofMillis(20))
    showOverlayWindow(APP)
    assertTrue(texts().toString(), texts().contains("Available again in 0:05"))
    advance(Duration.ofSeconds(6))
    assertNull(overlay().requestedPackage())
    assertTrue(controller.get().privateField<IntentSessionTracker>("intentSessions").isActive(APP, SystemClock.elapsedRealtime()))
  }

  @Test fun `a zero-minute downtime adds no future rest to a combined card`() {
    startTimedVisit()
    IntentAppStore(context).setIntent(APP, 1, 0)
    advance(Duration.ofSeconds(55))
    RollingLimitStore(context).apply {
      setRule(APP, 1, 15)
      addUsage(APP, 60_000L, wallNow() - 900_000L + 10_000L)
    }
    windowEvent(APP)
    advance(Duration.ofMillis(20))
    showOverlayWindow(APP)
    assertTrue(texts().toString(), texts().contains("Available again in 0:10"))
    assertFalse(texts().any { it.contains("opens again when the downtime ends") })
    advance(Duration.ofSeconds(11))
    assertEquals(AppRuleOverlayMode.ASK, overlay().mode)
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())
  }

  @Test fun `an unobservable foreground retains the card and resumes billing only after verified release`() {
    val rolling = RollingLimitStore(context)
    rolling.setRule(APP, 1, 15)
    rolling.addUsage(APP, 60_000L, wallNow() - 900_000L + 5_000L)
    openApp(APP)
    shadowOf(controller.get()).apply { setRootInActiveWindow(null); setWindows(emptyList()) }
    advance(Duration.ofSeconds(6))
    assertEquals(APP, overlay().requestedPackage())
    assertFalse(rolling.isOver(APP, wallNow()))
    val usageBeforeRelease = rolling.usedMs(APP, wallNow())
    val verifiedAt = SystemClock.elapsedRealtime()
    showOverlayWindow(APP)
    controller.get().onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
      packageName = APP
    })
    advance(Duration.ofSeconds(1))
    assertNull(overlay().requestedPackage())
    advance(Duration.ofSeconds(16))
    val used = rolling.usedMs(APP, wallNow())
    assertTrue(used > usageBeforeRelease)
    assertTrue(used <= usageBeforeRelease + SystemClock.elapsedRealtime() - verifiedAt)
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())
  }

  @Test fun `Settings stops a retry while the active root remains unobservable`() {
    IntentAppStore(context).setIntent(APP, 1, 1)
    openApp(APP)
    controller.get().javaClass.getDeclaredField("appRuleSafety").apply { isAccessible = true }
      .set(controller.get(), AppRuleSafety.fromPackages(context.packageName, setOf("com.android.settings")))
    shadowOf(controller.get()).apply { setRootInActiveWindow(null); setWindows(emptyList()) }
    advance(Duration.ofSeconds(1))
    assertEquals(APP, overlay().requestedPackage())
    shadowOf(controller.get()).setWindows(listOf(window(AccessibilityWindowInfo.TYPE_APPLICATION, node("com.android.settings"))))
    advance(Duration.ofSeconds(1))
    assertNull(overlay().requestedPackage())
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())
  }

  @Test fun `an untouched app visit reaches its daily budget without a new event`() {
    val daily = AppLimitStore(context)
    daily.setLimit(APP, 1)
    showAppWindows(APP)
    windowEvent(APP)
    assertNull(overlay().requestedPackage())
    advance(Duration.ofMillis(60_020L))
    assertEquals(AppRuleOverlayMode.LIMIT, overlay().mode)
    assertEquals(1L, count(EnforcementReason.APP_LIMIT))
    val used = daily.usageToday(wallNow())[APP]
    showOverlayWindow(APP)
    advance(Duration.ofSeconds(30))
    assertEquals(used, daily.usageToday(wallNow())[APP])
    assertEquals(1L, count(EnforcementReason.APP_LIMIT))
  }

  @Test fun `an untouched app visit reaches its rolling allowance without a new event`() {
    val rolling = RollingLimitStore(context)
    rolling.setRule(APP, 1, 15)
    showAppWindows(APP)
    windowEvent(APP)
    assertNull(overlay().requestedPackage())
    advance(Duration.ofMillis(60_020L))
    assertEquals(AppRuleOverlayMode.LIMIT, overlay().mode)
    assertEquals(1L, count(EnforcementReason.ROLLING_LIMIT))
    val used = rolling.usedMs(APP, wallNow())
    showOverlayWindow(APP)
    advance(Duration.ofSeconds(30))
    assertEquals(used, rolling.usedMs(APP, wallNow()))
    assertEquals(1L, count(EnforcementReason.ROLLING_LIMIT))
  }

  @Test fun `the Home action leaves a card and does not reopen it on the next tick`() {
    IntentAppStore(context).setIntent(APP, 1, 1)
    openApp(APP)
    showOverlayWindow(APP)
    views().filterIsInstance<Button>().single { it.text == "Not now" }.performClick()
    advance(Duration.ofSeconds(16))
    assertNull(overlay().requestedPackage())
    assertEquals(listOf(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME), shadowOf(controller.get()).globalActionsPerformed)
    assertEquals(1L, count(EnforcementReason.TIMED_VISIT))
  }

  @Test fun `a card canceled before attachment never counts`() {
    IntentAppStore(context).setIntent(APP, 1, 1)
    showAppWindows(APP)
    windowEvent(APP)
    assertEquals(0L, count(EnforcementReason.TIMED_VISIT))
    showAppWindows("other.app")
    windowEvent("other.app")
    advance(Duration.ofMillis(20))
    assertNull(overlay().requestedPackage())
    assertEquals(0L, EnforcementStatsStore(context).snapshot().total)
  }

  @Test fun `service teardown removes its timer card and callbacks`() {
    IntentAppStore(context).setIntent(APP, 1, 1)
    openApp(APP)
    showOverlayWindow(APP)
    val handler = controller.get().javaClass.getDeclaredMethod("getAppRuleHandler").apply { isAccessible = true }.invoke(controller.get()) as android.os.Handler
    val callback = controller.get().privateField<Runnable>("appRuleRefresh")
    controller.destroy()
    destroyed = true
    assertNull(overlay().requestedPackage())
    assertFalse(handler.hasCallbacks(callback))
    advance(Duration.ofSeconds(2))
    assertNull(overlay().requestedPackage())
  }

  @Test fun `P5 observe visit post retained through Settings`() {
    observeP5SettingsVisit(removeVisitPost = false)
  }

  @Test fun `P5 observe manual visit post removal after Settings`() {
    observeP5SettingsVisit(removeVisitPost = true)
  }

  private fun observeP5SettingsVisit(removeVisitPost: Boolean) {
    val service = controller.get()
    val setupElapsedMs = SystemClock.elapsedRealtime()
    val setupUptimeMs = SystemClock.uptimeMillis()
    val setupWallMs = wallNow()
    val handler = service.javaClass.getDeclaredMethod("getAppRuleHandler").apply { isAccessible = true }
      .invoke(service) as android.os.Handler
    val visitPost = service.privateField<Runnable>("intentDeadlineCheck")
    val sessions = service.privateField<IntentSessionTracker>("intentSessions")
    val store = service.privateField<IntentAppStore>("intentStore")
    val stored = store.privateField<android.content.SharedPreferences>("preferences")
    advance(Duration.ofSeconds(7))
    startTimedVisit()
    val grantElapsedMs = SystemClock.elapsedRealtime()
    val expiryElapsedMs = grantElapsedMs + sessions.remainingMs(APP, grantElapsedMs)
    val previousTickElapsedMs = setupElapsedMs + ((expiryElapsedMs - setupElapsedMs) / 15_000L) * 15_000L
    val nextTickElapsedMs = previousTickElapsedMs + 15_000L
    assertTrue("The real Start action must grant a visit", sessions.isActive(APP, grantElapsedMs))
    assertTrue("The visit must have a pending post", handler.hasCallbacks(visitPost))
    assertTrue("Expiry must be between usage ticks", expiryElapsedMs > previousTickElapsedMs && expiryElapsedMs < nextTickElapsedMs)
    val log = { stage: String ->
      val row = org.json.JSONObject()
        .put("decision", "settings_visit")
        .put("variant", if (removeVisitPost) "manual_remove_visit_post" else "production_retained_post")
        .put("stage", stage)
        .put("clockProvenance", "Robolectric SDK35 PAUSED main looper idleFor advances uptime and elapsed together; no clock reset")
        .put("wallProvenance", "setUp appRuleWallClock anchors 2026-10-13T23:59:55Z to elapsedRealtime before onServiceConnected")
        .put("setupElapsedMs", setupElapsedMs)
        .put("setupUptimeMs", setupUptimeMs)
        .put("setupWallMs", setupWallMs)
        .put("grantElapsedMs", grantElapsedMs)
        .put("expiryElapsedMs", expiryElapsedMs)
        .put("previousTickElapsedMs", previousTickElapsedMs)
        .put("nextTickElapsedMs", nextTickElapsedMs)
        .put("elapsedMs", SystemClock.elapsedRealtime())
        .put("uptimeMs", SystemClock.uptimeMillis())
        .put("wallMs", wallNow())
        .put("visitPostPending", handler.hasCallbacks(visitPost))
        .put("cardRefreshPending", hasPendingAppRuleRefresh())
        .put("sessionActive", sessions.isActive(APP, SystemClock.elapsedRealtime()))
        .put("sessionRetained", sessions.privateField<Map<String, Long>>("expiries").containsKey(APP))
        .put("remainingMs", sessions.remainingMs(APP, SystemClock.elapsedRealtime()))
        .put("overlayMode", overlay().mode?.name ?: org.json.JSONObject.NULL)
        .put("overlayRequestedPackage", overlay().requestedPackage() ?: org.json.JSONObject.NULL)
        .put("overlayShowing", overlay().isShowing())
        .put("activeRootPackage", service.rootInActiveWindow?.packageName?.toString() ?: org.json.JSONObject.NULL)
        .put("trackedForegroundPackage", service.privateField<String?>("currentForegroundPackage") ?: org.json.JSONObject.NULL)
        .put("navigationActions", org.json.JSONArray(shadowOf(service).globalActionsPerformed))
        .put("storedLastEnds", org.json.JSONObject(stored.getString("last_ends", "{}") ?: "{}"))
        .put("cooldownRemainingMs", store.cooldownRemainingMs(APP, wallNow()))
      println("P5_PROBE $row")
    }
    log("active_grant")
    advance(Duration.ofSeconds(1))
    service.javaClass.getDeclaredField("appRuleSafety").apply { isAccessible = true }
      .set(service, AppRuleSafety.fromPackages(context.packageName, setOf("com.android.settings")))
    showAppWindows("com.android.settings")
    windowEvent("com.android.settings")
    val settingsGateAllows = service.javaClass.getDeclaredMethod("canRunEnforcementAction").apply { isAccessible = true }
      .invoke(service) as Boolean
    assertFalse("The configured real Settings gate must reject enforcement", settingsGateAllows)
    log("settings_entered_before_simulation")
    if (removeVisitPost) handler.removeCallbacks(visitPost)
    log("settings_after_simulation")
    advance(Duration.ofMillis(expiryElapsedMs - SystemClock.elapsedRealtime() - 1L))
    assertTrue("Root restoration must follow the last usage tick", SystemClock.elapsedRealtime() > previousTickElapsedMs)
    log("settings_after_last_tick")
    showAppWindows(APP)
    log("root_restored_without_event")
    advance(Duration.ofMillis(1))
    log("original_expiry")
    advance(Duration.ofMillis(nextTickElapsedMs - SystemClock.elapsedRealtime() + 20L))
    log("after_next_usage_tick")
  }

  private fun startTimedVisit() {
    IntentAppStore(context).setIntent(APP, 1, 1)
    openApp(APP)
    showOverlayWindow(APP)
    views().filterIsInstance<Button>().single { it.text == "Start 1 min" }.performClick()
    showAppWindows(APP)
  }

  private fun switchUnderCard(packageName: String) {
    IntentAppStore(context).setIntent(APP, 1, 1)
    openApp(APP)
    showOverlayWindow(APP)
    // No window event: only the card's refresh can identify and enforce this switch.
    showOverlayWindow(packageName)
    advance(Duration.ofSeconds(1))
    assertEquals(packageName, overlay().requestedPackage())
    assertTrue(overlay().isShowing())
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())
  }

  private fun observeInstagramHome() {
    val root = node(INSTAGRAM).apply { viewIdResourceName = "$INSTAGRAM:id/feed_recycler_view" }
    shadowOf(controller.get()).apply {
      setRootInActiveWindow(root)
      setWindows(listOf(window(AccessibilityWindowInfo.TYPE_APPLICATION, root)))
    }
    controller.get().onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
      packageName = INSTAGRAM
    })
  }

  private fun instagramPolicy(): InstagramGuardStateMachine = controller.get().privateField("instagramStateMachine")

  private fun blockExampleSite() {
    AdultSiteRuleStore(context).apply { enabled = true; add("example.com") }
  }

  private fun addExpiringBudget(packageName: String) {
    RollingLimitStore(context).apply {
      setRule(packageName, 1, 15)
      addUsage(packageName, 60_000L, wallNow() - 900_000L + 5_000L)
    }
  }

  private fun openExpiringBudget(packageName: String) {
    addExpiringBudget(packageName)
    openApp(packageName)
  }

  private fun browserRoot(browser: String): AccessibilityNodeInfo = node(browser).apply {
    if (browser == FIREFOX) {
      viewIdResourceName = "ADDRESSBAR_URL_BOX"
      contentDescription = " example.com. Search or enter address"
    } else {
      viewIdResourceName = "$CHROME:id/url_bar"
      text = "example.com"
    }
  }

  private fun instagramRoot(id: String): AccessibilityNodeInfo = node(INSTAGRAM).apply {
    viewIdResourceName = "$INSTAGRAM:id/$id"
  }

  private fun assertInstagramBudgetHandoff(id: String, reason: InstagramBlockReason) {
    ZenGuardPreferences(context).instagramObservationMode = false
    openExpiringBudget(INSTAGRAM)
    showOverlayWindow(instagramRoot(id))
    contentEvent(INSTAGRAM)
    assertNull(instagramPolicy().debugState(SystemClock.elapsedRealtime()).blockerReason)
    advance(Duration.ofSeconds(6))
    assertNull(overlay().requestedPackage())
    assertEquals(reason, instagramPolicy().debugState(SystemClock.elapsedRealtime()).blockerReason)
    assertTrue(controller.get().privateField<InstagramIntervention>("instagramIntervention").isShowing)
  }

  private fun assertScreenOffInstagramBudgetHandoff(id: String, reason: InstagramBlockReason) {
    ZenGuardPreferences(context).instagramObservationMode = false
    openExpiringBudget(INSTAGRAM)
    showOverlayWindow(instagramRoot(id))
    turnScreenOff()
    val rolling = RollingLimitStore(context)
    val offDuration = Duration.ofSeconds(22)
    val usageAfterBudgetExpiry = rolling.usedMs(INSTAGRAM, wallNow() + offDuration.toMillis())

    advance(offDuration)

    assertEquals(INSTAGRAM, overlay().requestedPackage())
    assertTrue(overlay().isShowing())
    assertTrue(hasPendingAppRuleRefresh())
    assertNull(instagramPolicy().debugState(SystemClock.elapsedRealtime()).blockerReason)
    assertFalse(controller.get().privateField<InstagramIntervention>("instagramIntervention").isShowing)
    assertEquals(usageAfterBudgetExpiry, rolling.usedMs(INSTAGRAM, wallNow()))
    assertTrue(shadowOf(controller.get()).globalActionsPerformed.isEmpty())

    shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
    advance(Duration.ofMillis(1_020))

    assertNull(overlay().requestedPackage())
    assertFalse(hasPendingAppRuleRefresh())
    assertEquals(reason, instagramPolicy().debugState(SystemClock.elapsedRealtime()).blockerReason)
    assertTrue(controller.get().privateField<InstagramIntervention>("instagramIntervention").isShowing)
  }

  private fun turnScreenOff() {
    shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
    context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
    shadowOf(Looper.getMainLooper()).idle()
  }

  private fun hasPendingAppRuleRefresh(): Boolean {
    val handler = controller.get().javaClass.getDeclaredMethod("getAppRuleHandler").apply { isAccessible = true }
      .invoke(controller.get()) as android.os.Handler
    return handler.hasCallbacks(controller.get().privateField<Runnable>("appRuleRefresh"))
  }

  /** The fixture exposes real page metadata and records the native Home-tab action. */
  private fun youtubeRoot(rowIndex: Int?): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo> {
    val page = node(YOUTUBE).apply {
      viewIdResourceName = "$YOUTUBE:id/reel_player_page_container"
      if (rowIndex != null) collectionItemInfo = AccessibilityNodeInfo.CollectionItemInfo.obtain(rowIndex, 1, 0, 1, false)
    }
    val home = node(YOUTUBE).apply { isClickable = true }
    // Robolectric clones share an existing action log; initialize it before cloning the window.
    assertTrue(shadowOf(home).performedActions.isEmpty())
    shadowOf(home).setOnPerformActionListener { action, _ -> action == AccessibilityNodeInfo.ACTION_CLICK }
    val item = node(YOUTUBE).also { shadowOf(it).addChild(home) }
    val tabs = node(YOUTUBE).apply { viewIdResourceName = "$YOUTUBE:id/pivot_bar" }
    shadowOf(tabs).addChild(item)
    val root = node(YOUTUBE)
    shadowOf(root).addChild(page)
    shadowOf(root).addChild(tabs)
    return root to home
  }

  private fun contentEvent(packageName: String) {
    controller.get().onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
      this.packageName = packageName
    })
  }

  private fun showContentAppWindow(root: AccessibilityNodeInfo) {
    shadowOf(controller.get()).apply {
      setRootInActiveWindow(root)
      setWindows(listOf(window(AccessibilityWindowInfo.TYPE_APPLICATION, root)))
    }
  }

  private fun wallNow() = controller.get().appRuleWallClock()

  private fun openApp(packageName: String) {
    showAppWindows(packageName)
    windowEvent(packageName)
    advance(Duration.ofMillis(20))
    assertTrue(overlay().isShowing())
  }

  private fun windowEvent(packageName: String) {
    controller.get().onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
      this.packageName = packageName
    })
  }

  private fun showAppWindows(packageName: String) {
    val node = node(packageName)
    shadowOf(controller.get()).apply {
      setRootInActiveWindow(node)
      setWindows(listOf(window(AccessibilityWindowInfo.TYPE_APPLICATION, node)))
    }
  }

  private fun showOverlayWindow(packageName: String) {
    showOverlayWindow(node(packageName))
  }

  private fun showOverlayWindow(appRoot: AccessibilityNodeInfo) {
    val node = node(context.packageName)
    shadowOf(controller.get()).apply {
      setRootInActiveWindow(node)
      setWindows(listOf(window(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, node), window(AccessibilityWindowInfo.TYPE_APPLICATION, appRoot)))
    }
  }

  private fun node(packageName: String) = AccessibilityNodeInfo.obtain().apply {
    this.packageName = packageName
    isVisibleToUser = true
  }

  private fun window(type: Int, root: AccessibilityNodeInfo) = AccessibilityWindowInfo().also {
    shadowOf(it).setType(type)
    shadowOf(it).setRoot(root)
  }

  private fun siteBoundaryShowing() = siteButton(R.string.zen_guard_site_go_back)?.isAttachedToWindow == true

  private fun siteButton(label: Int): Button? {
    val windows = Shadow.extract<ShadowWindowManagerImpl>(controller.get().getSystemService(WindowManager::class.java))
    val matches = ArrayList<View>()
    windows.views.forEach { it.findViewsWithText(matches, context.getString(label), View.FIND_VIEWS_WITH_TEXT) }
    val buttons = matches.filterIsInstance<Button>()
    return if (buttons.isEmpty()) null else buttons.single()
  }

  private fun count(reason: EnforcementReason) = EnforcementStatsStore(context).snapshot().counts[reason.key] ?: 0L
  private fun advance(duration: Duration) { shadowOf(Looper.getMainLooper()).idleFor(duration) }
  private fun overlay(): AppRuleOverlay = controller.get().privateField("appRuleOverlay")
  private fun texts(): List<String> = views().filterIsInstance<TextView>().map { it.text.toString() }.toList()
  private fun views(): Sequence<View> {
    val root = overlay().privateField<View?>("root") ?: return emptySequence()
    fun children(view: View): Sequence<View> = sequence {
      yield(view)
      if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(children(view.getChildAt(index)))
    }
    return children(root)
  }

  @Suppress("UNCHECKED_CAST")
  private fun <T> Any.privateField(name: String): T = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

  private companion object {
    const val APP = "app.one"
    const val OTHER_APP = "app.two"
    const val INSTAGRAM = "com.instagram.android"
    const val CHROME = "com.android.chrome"
    const val FIREFOX = "org.mozilla.firefox"
    const val YOUTUBE = "com.google.android.youtube"
  }
}
