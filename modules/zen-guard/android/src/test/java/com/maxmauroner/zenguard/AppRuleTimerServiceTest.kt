package com.maxmauroner.zenguard

import android.content.Context
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
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
import org.robolectric.annotation.LooperMode

/** Exercise the actual service with its own overlay above the guarded app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class AppRuleTimerServiceTest {
  private lateinit var context: Context
  private lateinit var controller: ServiceController<ZenGuardAccessibilityService>
  private lateinit var originalZone: TimeZone
  private var destroyed = false

  @Before fun setUp() {
    originalZone = TimeZone.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    context = RuntimeEnvironment.getApplication()
    HomeFeedStatusFailureRegistry.clearForTests()
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
    val node = node(context.packageName)
    shadowOf(controller.get()).apply {
      setRootInActiveWindow(node)
      setWindows(listOf(window(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, node), window(AccessibilityWindowInfo.TYPE_APPLICATION, node(packageName))))
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

  private companion object { const val APP = "app.one" }
}
