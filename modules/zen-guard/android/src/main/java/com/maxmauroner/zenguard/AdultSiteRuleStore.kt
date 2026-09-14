package com.maxmauroner.zenguard

import android.content.Context
import org.json.JSONArray

/** App-private website rules used directly by the service while JavaScript is not running. */
internal class AdultSiteRuleStore(context: Context) {
  private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

  var enabled: Boolean
    get() = try {
      preferences.getBoolean(KEY_ENABLED, false)
    } catch (_: ClassCastException) {
      false
    }
    set(value) = preferences.edit().putBoolean(KEY_ENABLED, value).apply()

  val browserSignalMask: Int
    get() = try {
      preferences.getInt(KEY_BROWSER_SIGNAL_MASK, 0)
    } catch (_: ClassCastException) {
      0
    }

  fun recordBrowserSignal(mask: Int) {
    if (browserSignalMask and mask == mask) return
    preferences.edit().putInt(KEY_BROWSER_SIGNAL_MASK, browserSignalMask or mask).apply()
  }

  fun customHosts(): Set<String> = try {
    val raw = JSONArray(preferences.getString(KEY_CUSTOM_HOSTS, "[]") ?: "[]")
    buildSet {
      for (index in 0 until raw.length()) {
        val value = raw.optString(index)
        try {
          add(AdultSitePolicy.canonicalRule(value))
        } catch (_: IllegalArgumentException) {
          // A corrupt or legacy entry cannot broaden enforcement.
        }
      }
    }.take(AdultSitePolicy.MAX_CUSTOM_HOSTS).toSet()
  } catch (_: Exception) {
    emptySet()
  }

  fun add(input: String): String {
    val host = AdultSitePolicy.canonicalRule(input)
    val current = customHosts()
    if (host in current) return host
    require(current.size < AdultSitePolicy.MAX_CUSTOM_HOSTS) { "You can block up to 100 additional sites" }
    write(current + host)
    return host
  }

  fun remove(input: String) {
    write(customHosts() - AdultSitePolicy.canonicalRule(input))
  }

  private fun write(hosts: Set<String>) {
    val json = JSONArray()
    hosts.sorted().forEach(json::put)
    preferences.edit().putString(KEY_CUSTOM_HOSTS, json.toString()).apply()
  }

  companion object {
    private const val FILE_NAME = "zen_guard_adult_sites"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_CUSTOM_HOSTS = "custom_hosts"
    private const val KEY_BROWSER_SIGNAL_MASK = "browser_signal_mask"
  }
}
