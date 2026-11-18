package com.maxmauroner.zenguard

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

internal object AccessibilityAccess {
  data class Status(val enabled: Boolean, val disabledAt: Long?)

  fun isEnabled(context: Context): Boolean {
    val service = ComponentName(context, ZenGuardAccessibilityService::class.java)
    val enabledServices = Settings.Secure.getString(
      context.contentResolver,
      Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return enabledServices.split(':').any { ComponentName.unflattenFromString(it) == service }
  }

  @Synchronized
  fun read(context: Context): Status {
    val enabled = isEnabled(context)
    val preferences = store(context)
    var disabledAt = preferences.getLong(DISABLED_AT, 0L).takeIf { it > 0 }
    if (enabled != preferences.getBoolean(ENABLED, false)) {
      val update = preferences.edit().putBoolean(ENABLED, enabled)
      if (!enabled) {
        disabledAt = System.currentTimeMillis()
        update.putLong(DISABLED_AT, disabledAt)
      }
      update.apply()
    }
    return Status(enabled, disabledAt)
  }

  @Synchronized
  fun acknowledge(context: Context, disabledAt: Long) {
    if (read(context).disabledAt == disabledAt) {
      store(context).edit().remove(DISABLED_AT).apply()
    }
  }

  private fun store(context: Context) =
    context.getSharedPreferences("zen_guard_accessibility_access", Context.MODE_PRIVATE)

  private const val ENABLED = "enabled"
  private const val DISABLED_AT = "disabled_at"
}
