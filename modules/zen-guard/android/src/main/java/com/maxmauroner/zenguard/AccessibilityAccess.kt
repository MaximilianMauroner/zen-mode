package com.maxmauroner.zenguard

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings

internal object AccessibilityAccess {
  data class OffPeriod(val since: Long, val through: Long)
  data class Status(val enabled: Boolean, val offPeriod: OffPeriod?)

  private enum class Lifecycle { CHECK, CONNECTED, DISCONNECTED }

  fun isEnabled(context: Context): Boolean {
    val service = ComponentName(context, ZenGuardAccessibilityService::class.java)
    val enabledServices = Settings.Secure.getString(
      context.contentResolver,
      Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return enabledServices.split(':').any { ComponentName.unflattenFromString(it) == service }
  }

  @Synchronized
  private fun record(context: Context, lifecycle: Lifecycle): Status {
    val enabled = isEnabled(context)
    val preferences = store(context)
    val wasEnabled = preferences.getBoolean(ENABLED, false)
    val disconnectedAt = preferences.getLong(DISCONNECTED_AT, 0L).takeIf { it > 0 }
    val previousPeriod = preferences.getLong(DISABLED_AT, 0L).takeIf { it > 0 }?.let {
      OffPeriod(it, preferences.getLong(DISABLED_THROUGH, it))
    }
    val now = System.currentTimeMillis()
    val connecting = enabled && lifecycle == Lifecycle.CONNECTED
    val bootCount = if (connecting) Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) else -1
    val newOffStart = when {
      wasEnabled && !enabled -> disconnectedAt ?: now
      wasEnabled && bootCount >= 0 && bootCount == preferences.getInt(BOOT_COUNT, -1) ->
        reconnectOffStart(preferences, now, disconnectedAt)
      else -> null
    }
    val offPeriod = when {
      newOffStart != null -> OffPeriod(
        minOf(previousPeriod?.since ?: newOffStart, newOffStart),
        maxOf(previousPeriod?.through ?: now, now),
      )
      enabled && !wasEnabled && previousPeriod != null -> previousPeriod.copy(through = maxOf(previousPeriod.through, now))
      else -> previousPeriod
    }
    val nextDisconnectedAt = when {
      !enabled || lifecycle == Lifecycle.CONNECTED -> null
      lifecycle == Lifecycle.DISCONNECTED -> disconnectedAt ?: now
      else -> disconnectedAt
    }
    if (connecting || enabled != wasEnabled || offPeriod != previousPeriod || nextDisconnectedAt != disconnectedAt) {
      val update = preferences.edit().putBoolean(ENABLED, enabled)
      if (offPeriod != null) update.putLong(DISABLED_AT, offPeriod.since).putLong(DISABLED_THROUGH, offPeriod.through)
      if (nextDisconnectedAt == null) update.remove(DISCONNECTED_AT)
      else update.putLong(DISCONNECTED_AT, nextDisconnectedAt)
      if (connecting) update.putInt(BOOT_COUNT, bootCount).putHeartbeat(now)
      update.commit()
    }
    return Status(enabled, offPeriod)
  }

  fun read(context: Context) = record(context, Lifecycle.CHECK)

  fun connected(context: Context) {
    record(context, Lifecycle.CONNECTED)
  }

  fun disconnected(context: Context) {
    record(context, Lifecycle.DISCONNECTED)
  }

  @Synchronized
  fun heartbeat(context: Context) {
    val preferences = store(context)
    if (preferences.getBoolean(ENABLED, false)) {
      preferences.edit().putHeartbeat(System.currentTimeMillis()).commit()
    }
  }

  @Synchronized
  fun acknowledge(context: Context, disabledThrough: Long) {
    val status = read(context)
    if (status.enabled && status.offPeriod?.through == disabledThrough) {
      store(context).edit().remove(DISABLED_AT).remove(DISABLED_THROUGH).commit()
    }
  }

  private fun reconnectOffStart(preferences: SharedPreferences, now: Long, disconnectedAt: Long?): Long? {
    if (disconnectedAt != null) return disconnectedAt.takeIf { now - it > RECONNECT_GRACE_MS }
    val heartbeatUptime = preferences.getLong(HEARTBEAT_UPTIME, 0L)
    return preferences.getLong(HEARTBEAT_AT, 0L).takeIf {
      it in 1..now && SystemClock.uptimeMillis() - heartbeatUptime > RECONNECT_GRACE_MS
    }
  }

  private fun SharedPreferences.Editor.putHeartbeat(now: Long) =
    putLong(HEARTBEAT_AT, now).putLong(HEARTBEAT_UPTIME, SystemClock.uptimeMillis())

  private fun store(context: Context) =
    context.getSharedPreferences("zen_guard_accessibility_access", Context.MODE_PRIVATE)

  private const val ENABLED = "enabled"
  private const val DISABLED_AT = "disabled_at"
  private const val DISABLED_THROUGH = "disabled_through"
  private const val DISCONNECTED_AT = "disconnected_at"
  private const val BOOT_COUNT = "boot_count"
  private const val HEARTBEAT_AT = "heartbeat_at"
  private const val HEARTBEAT_UPTIME = "heartbeat_uptime"
  private const val RECONNECT_GRACE_MS = 60_000L
  const val HEARTBEAT_INTERVAL_MS = 30_000L
}
