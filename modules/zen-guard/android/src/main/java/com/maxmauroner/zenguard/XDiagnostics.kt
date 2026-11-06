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
internal object XDiagnostics {
  private const val DURATION_MS = 600_000L
  private const val MAX_EVENTS = 800
  private val handler = Handler(Looper.getMainLooper())
  private var serviceConnected = false
  private var lastState = JSONObject()
  private var lastKey = ""
  private var lastSampleAt = 0L
  private var stopTask: Runnable? = null

  @Synchronized fun service(context: Context, connected: Boolean, reason: String) {
    serviceConnected = connected
    record(context, "service", JSONObject().put("connected", connected).put("reason", reason), true)
  }

  @Synchronized fun start(context: Context, serviceEnabled: Boolean) {
    val now = SystemClock.elapsedRealtime()
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val trace = JSONObject()
      .put("schema", 1)
      .put("startedAt", System.currentTimeMillis())
      .put("startedElapsedMs", now)
      .put("bootCount", bootCount(context))
      .put("endsElapsedMs", now + DURATION_MS)
      .put("build", JSONObject().put("versionName", packageInfo.versionName)
        .put("versionCode", if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else @Suppress("DEPRECATION") packageInfo.versionCode.toLong())
        .put("androidSdk", Build.VERSION.SDK_INT))
      .put("events", JSONArray())
      .put("droppedEvents", 0)
    save(context, trace)
    context.getSharedPreferences("x_diagnostics", Context.MODE_PRIVATE).edit().putBoolean("active", true).apply()
    lastKey = ""
    lastSampleAt = 0L
    lastState = JSONObject()
    record(context, "initial", snapshot(context).put("serviceEnabled", serviceEnabled), true)
    stopTask?.let(handler::removeCallbacks)
    stopTask = Runnable { runCatching { finish(context.applicationContext, "duration") } }.also { handler.postDelayed(it, DURATION_MS) }
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
    if (!force && key == lastKey && now - lastSampleAt < 10_000L) return
    lastKey = key
    lastSampleAt = now
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
    if (!trace.has("endedAt") && expired(context, trace, SystemClock.elapsedRealtime())) {
      finish(context, "duration_or_restart")
      trace = load(context) ?: return mapOf("state" to "empty")
    }
    return if (trace.has("endedAt")) mapOf("state" to "complete", "json" to trace.toString(2))
    else mapOf("state" to "recording")
  }

  fun share(context: Context) {
    val report = status(context)["json"] as? String ?: error("Complete an X recording before exporting it.")
    val directory = File(context.cacheDir, "x-diagnostics")
    check(directory.isDirectory || directory.mkdirs()) { "Could not create the X report folder." }
    val file = File.createTempFile("x-diagnostics-", ".json", directory)
    file.writeText(report)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.x-diagnostics", file)
    val send = Intent(Intent.ACTION_SEND).setType("application/json")
      .putExtra(Intent.EXTRA_STREAM, uri)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newUri(context.contentResolver, "X diagnostic report", uri)
    context.startActivity(Intent.createChooser(send, "Share X diagnostic report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }

  private fun bootCount(context: Context): Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

  private fun expired(context: Context, trace: JSONObject, now: Long): Boolean {
    val boot = bootCount(context)
    val originalBoot = trace.getInt("bootCount")
    return now < trace.getLong("startedElapsedMs") || now >= trace.getLong("endsElapsedMs") ||
      (boot >= 0 && originalBoot >= 0 && boot != originalBoot) ||
      ((boot < 0 || originalBoot < 0) && System.currentTimeMillis() >= trace.getLong("startedAt") + DURATION_MS)
  }

  private fun snapshot(context: Context): JSONObject {
    val preferences = ZenGuardPreferences(context)
    val now = SystemClock.elapsedRealtime()
    val home = HomeFeedStatusStore(context).x(now)
    return JSONObject().put("serviceConnected", serviceConnected)
      .put("consent", preferences.hasCurrentConsent).put("protectionEnabled", preferences.protectionEnabled)
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
