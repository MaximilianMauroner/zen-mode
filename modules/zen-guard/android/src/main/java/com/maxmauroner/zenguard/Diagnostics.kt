package com.maxmauroner.zenguard

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** A single, bounded recording. Only known structural signals and guard state enter the trace. */
internal object Diagnostics {
  private const val MIN_DURATION_MS = 1_000L
  private const val MAX_DURATION_MS = 3_600_000L
  private const val MAX_EVENTS = 800
  private val handler = Handler(Looper.getMainLooper())
  private var serviceConnected = false
  private var lastState = JSONObject()
  private val lastSamples = mutableMapOf<String, Pair<String, Long>>()
  private var stopTask: Runnable? = null

  @Synchronized fun service(context: Context, connected: Boolean, reason: String) {
    serviceConnected = connected
    record(context, "service", JSONObject().put("connected", connected).put("reason", reason), true)
  }

  @Synchronized fun start(context: Context, serviceEnabled: Boolean, durationMs: Long) {
    require(durationMs in MIN_DURATION_MS..MAX_DURATION_MS) { "Choose a duration from 1 second to 60 minutes." }
    check(status(context)["state"] != "recording") { "A recording is already running." }
    val now = SystemClock.elapsedRealtime()
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val trace = JSONObject()
      .put("schema", 2)
      .put("startedAt", System.currentTimeMillis())
      .put("startedElapsedMs", now)
      .put("bootCount", bootCount(context))
      .put("endsElapsedMs", now + durationMs)
      .put("build", JSONObject().put("versionName", packageInfo.versionName)
        .put("versionCode", if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else @Suppress("DEPRECATION") packageInfo.versionCode.toLong())
        .put("androidSdk", Build.VERSION.SDK_INT))
      .put("events", JSONArray())
      .put("droppedEvents", 0)
    context.getSharedPreferences("x_diagnostics", Context.MODE_PRIVATE).edit()
      .putString("trace", trace.toString()).putBoolean("active", true).apply()
    lastSamples.clear()
    lastState = JSONObject()
    record(context, "initial", snapshot(context).put("serviceEnabled", serviceEnabled), true)
    stopTask?.let(handler::removeCallbacks)
    stopTask = Runnable { runCatching { finish(context.applicationContext, "duration") } }.also { handler.postDelayed(it, durationMs) }
  }

  fun isRecording(context: Context): Boolean = context.getSharedPreferences("x_diagnostics", Context.MODE_PRIVATE).getBoolean("active", false)

  @Synchronized fun record(context: Context, kind: String, data: JSONObject, force: Boolean = false) {
    if (!isRecording(context)) return
    val trace = load(context) ?: return
    if (trace.has("endedAt")) return
    val now = SystemClock.elapsedRealtime()
    if (expired(context, trace, now)) {
      finish(context, "duration_or_restart")
      return
    }
    val key = if (kind == "x") {
      JSONObject(data.toString()).apply { remove("usedMs"); remove("blockedUntilElapsedMs"); remove("eventType") }.toString()
    } else "$kind:$data"
    val previous = lastSamples[kind]
    if (!force && previous != null && key == previous.first && now - previous.second < 10_000L) return
    lastSamples[kind] = key to now
    if (kind == "x") lastState = JSONObject(data.toString())
    val events = trace.getJSONArray("events")
    if (events.length() < MAX_EVENTS) {
      events.put(JSONObject().put("at", System.currentTimeMillis()).put("elapsedMs", now).put("kind", kind).put("data", data))
    } else {
      trace.put("droppedEvents", trace.getInt("droppedEvents") + 1)
    }
    save(context, trace)
  }

  @Synchronized fun finish(context: Context, reason: String) {
    val trace = load(context) ?: return
    if (trace.has("endedAt")) return
    stopTask?.let(handler::removeCallbacks)
    stopTask = null
    trace.put("endedAt", System.currentTimeMillis())
    trace.put("endedElapsedMs", SystemClock.elapsedRealtime())
    trace.put("endReason", reason)
    trace.put("final", snapshot(context))
    context.getSharedPreferences("x_diagnostics", Context.MODE_PRIVATE).edit()
      .putString("trace", trace.toString()).putBoolean("active", false).apply()
  }

  @Synchronized fun status(context: Context): Map<String, Any> {
    var trace = load(context) ?: return mapOf("state" to "empty")
    if (!trace.has("endedAt") && (!isRecording(context) || expired(context, trace, SystemClock.elapsedRealtime()))) {
      finish(context, if (isRecording(context)) "duration_or_restart" else "interrupted")
      trace = load(context) ?: return mapOf("state" to "empty")
    }
    return if (trace.has("endedAt")) mapOf("state" to "complete", "json" to trace.toString(2))
    else mapOf("state" to "recording", "remainingSeconds" to ((trace.getLong("endsElapsedMs") - SystemClock.elapsedRealtime() + 999L) / 1_000L).coerceAtLeast(0L).toDouble())
  }

  @Synchronized fun clear(context: Context) {
    deleteExports(context)
    stopTask?.let(handler::removeCallbacks)
    stopTask = null
    lastSamples.clear()
    context.getSharedPreferences("x_diagnostics", Context.MODE_PRIVATE).edit().remove("trace").remove("active").apply()
  }

  private fun deleteExports(context: Context) {
    val directory = File(context.cacheDir, "x-diagnostics")
    if (!directory.exists()) return
    val files = checkNotNull(directory.listFiles()) { "Could not read diagnostic exports." }
    files.forEach { check(it.delete()) { "Could not delete a diagnostic export." } }
  }

  fun share(context: Context, reportText: String = "") {
    val recording = status(context)["json"] as? String ?: error("Complete a recording before exporting it.")
    val report = if (reportText.isBlank()) recording else JSONObject()
      .put("report", reportText).put("diagnostics", JSONObject(recording)).toString(2)
    val directory = File(context.cacheDir, "x-diagnostics")
    check(directory.isDirectory || directory.mkdirs()) { "Could not create the diagnostic report folder." }
    val file = File.createTempFile("zen-diagnostics-", ".json", directory)
    file.writeText(report)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.x-diagnostics", file)
    val send = Intent(Intent.ACTION_SEND).setType("application/json")
      .putExtra(Intent.EXTRA_STREAM, uri)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (reportText.isNotBlank()) send.putExtra(Intent.EXTRA_TEXT, reportText)
    send.clipData = ClipData.newUri(context.contentResolver, "Diagnostic report", uri)
    context.startActivity(Intent.createChooser(send, "Share diagnostic report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }

  private fun bootCount(context: Context): Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

  private fun expired(context: Context, trace: JSONObject, now: Long): Boolean {
    val boot = bootCount(context)
    val originalBoot = trace.getInt("bootCount")
    return now < trace.getLong("startedElapsedMs") || now >= trace.getLong("endsElapsedMs") ||
      (boot >= 0 && originalBoot >= 0 && boot != originalBoot) ||
      ((boot < 0 || originalBoot < 0) && System.currentTimeMillis() >=
        trace.getLong("startedAt") + trace.getLong("endsElapsedMs") - trace.getLong("startedElapsedMs"))
  }

  private fun snapshot(context: Context): JSONObject {
    val preferences = ZenGuardPreferences(context)
    val now = SystemClock.elapsedRealtime()
    val home = HomeFeedStatusStore(context).x(now)
    return JSONObject().put("serviceConnected", serviceConnected)
      .put("consent", preferences.hasCurrentConsent).put("protectionEnabled", preferences.protectionEnabled)
      .put("youtubeShortsEnabled", preferences.shortsEnabled).put("youtubeHomeEnabled", preferences.youtubeHomeEnabled)
      .put("youtubeHomeObserved", preferences.youtubeHomeObserved).put("youtubeObservationMode", preferences.observationMode)
      .put("instagramWaitSeconds", preferences.instagramWaitSeconds).put("instagramReelsMinutes", preferences.instagramReelsMinutes)
      .put("instagramHomeMinutes", preferences.instagramHomeMinutes).put("instagramExploreBlocked", preferences.instagramExploreBlocked)
      .put("instagramObservationMode", preferences.instagramObservationMode).put("instagramSignalMask", preferences.instagramSignalMask)
      .put("adultSiteEnabled", AdultSiteRuleStore(context).enabled)
      .put("homeEnabled", preferences.xHomeEnabled).put("videosEnabled", preferences.xVideosEnabled)
      .put("homeMinutes", preferences.xHomeMinutes).put("observationMode", preferences.xObservationMode)
      .put("signalMask", preferences.xSignalMask).put("usedMs", home.usedMs)
      .put("usageState", home.usageState.name).put("lockoutState", home.lockoutState.name)
      .put("storageState", home.storageState.name).put("blockedUntilElapsedMs", home.blockedUntilElapsedMs ?: JSONObject.NULL)
      .put("lastObservation", lastState)
  }

  private fun load(context: Context): JSONObject? = context.getSharedPreferences("x_diagnostics", Context.MODE_PRIVATE)
    .getString("trace", null)?.let { JSONObject(it) }

  private fun save(context: Context, trace: JSONObject) {
    context.getSharedPreferences("x_diagnostics", Context.MODE_PRIVATE).edit().putString("trace", trace.toString()).apply()
  }
}
