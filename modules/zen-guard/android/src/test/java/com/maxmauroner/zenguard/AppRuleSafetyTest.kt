package com.maxmauroner.zenguard

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRuleSafetyTest {
  private val safety = AppRuleSafety.fromPackages(
    appPackage = "com.lab4code.zenmode",
    systemSettingsPackages = setOf("com.android.settings", "com.oem.accessibilitysettings"),
  )

  @Test fun excludesZenModeAndResolvedSettingsFromThePicker() {
    assertTrue(safety.isExempt("com.lab4code.zenmode"))
    assertTrue(safety.isExempt("com.android.settings"))
    assertTrue(safety.isExempt("com.oem.accessibilitysettings"))
    assertFalse(safety.isExempt("com.example.reader"))
  }

  @Test fun staleSavedRulesCannotReachAnyEnforcementDecisionForSettings() {
    assertFalse(safety.allowsDailyEnforcement("com.android.settings"))
    assertFalse(safety.allowsTimedVisitEnforcement("com.android.settings"))
    assertFalse(safety.allowsRollingEnforcement("com.android.settings"))
  }

  @Test fun settingsUnderTheGuardOverlayStopsQueuedActionsBeforeItsEventArrives() {
    assertTrue(safety.hasSystemSettingsWindow(
      activePackage = "com.lab4code.zenmode",
      windowPackages = listOf("com.lab4code.zenmode", "com.android.settings"),
    ))
  }

  @Test fun ordinaryForegroundWindowsDoNotStopRuleEnforcement() {
    assertFalse(safety.hasSystemSettingsWindow(
      activePackage = "com.example.reader",
      windowPackages = listOf("com.example.reader", "com.android.systemui"),
    ))
  }

  @Test fun pickerFilteringKeepsOrdinaryAppsAndRemovesEveryExemptPackage() {
    val visible = listOf(
      "com.lab4code.zenmode",
      "com.android.settings",
      "com.oem.accessibilitysettings",
      "com.example.reader",
    ).filterNot(safety::isExempt)

    assertTrue(visible == listOf("com.example.reader"))
  }

  @Test fun onlySystemAndUpdatedSystemSettingsHandlersBecomeExempt() {
    val packages = AppRuleSafety.systemHandlerPackages(listOf(
      "com.android.settings" to ApplicationInfo.FLAG_SYSTEM,
      "com.oem.accessibilitysettings" to ApplicationInfo.FLAG_UPDATED_SYSTEM_APP,
      "com.example.fake-settings" to 0,
    ))

    assertTrue("com.android.settings" in packages)
    assertTrue("com.oem.accessibilitysettings" in packages)
    assertFalse("com.example.fake-settings" in packages)
  }
}
