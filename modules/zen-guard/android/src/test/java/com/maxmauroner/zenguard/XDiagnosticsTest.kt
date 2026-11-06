package com.maxmauroner.zenguard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Parcel
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class XDiagnosticsTest {
  private val context: Context get() = RuntimeEnvironment.getApplication()

  @Test fun fullRecordingCanBeExportedWithoutExceedingBinderLimit() {
    XDiagnostics.start(context, true)
    val data = JSONObject()
      .put("reason", "evaluated").put("surface", "HOME")
      .put("markers", JSONArray().put("scaffold_home_tabbed").put("MainLanding"))
      .put("nodeCount", 250).put("pager", true).put("advanced", false)
      .put("eventType", 2048).put("action", "ALLOW").put("overlayShowing", false)
      .put("homeEnabled", true).put("videosEnabled", true).put("signalMask", 3)
      .put("homeMinutes", 5).put("observationMode", false).put("usedMs", 123456)
      .put("usageState", "KNOWN").put("lockoutState", "NONE").put("storageState", "READY")
      .put("blockedUntilElapsedMs", JSONObject.NULL).put("freshObservationRequired", false)
    val storage = context.getSharedPreferences("x_diagnostics", Context.MODE_PRIVATE)
    val trace = JSONObject(storage.getString("trace", null)!!)
    val events = trace.getJSONArray("events")
    repeat(799) {
      events.put(JSONObject().put("kind", "x")
        .put("at", trace.getLong("startedAt") + it * 500L)
        .put("elapsedMs", trace.getLong("startedElapsedMs") + it * 500L)
        .put("data", JSONObject(data.toString()).put("usedMs", it)))
    }
    check(storage.edit().putString("trace", trace.toString()).commit())
    ShadowSystemClock.advanceBy(Duration.ofMinutes(10))
    XDiagnostics.finish(context, "duration")
    XDiagnostics.share(context)
    val chooser = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
    val parcel = Parcel.obtain()
    try {
      chooser.writeToParcel(parcel, 0)
      println("X export intent bytes: ${parcel.dataSize()}")
      assertTrue("Report must not cross Binder's 1 MiB transaction limit", parcel.dataSize() < 1_048_576)
    } finally {
      parcel.recycle()
    }
    val send = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
    assertEquals(Intent.ACTION_SEND, send.action)
    assertEquals("application/json", send.type)
    assertFalse(send.hasExtra(Intent.EXTRA_TEXT))
    val uri = requireNotNull(send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
    assertEquals("content", uri.scheme)
    val provider = requireNotNull(context.packageManager.resolveContentProvider(uri.authority!!, 0))
    assertFalse(provider.exported)
    assertTrue(provider.grantUriPermissions)
    assertTrue(uri.lastPathSegment!!.endsWith(".json"))
    assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    assertEquals(uri, send.clipData!!.getItemAt(0).uri)
    assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    assertEquals("application/json", context.contentResolver.getType(uri))
    val output = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
    assertEquals(XDiagnostics.status(context)["json"], output)
    val exported = JSONObject(output)
    assertEquals(800, exported.getJSONArray("events").length())
    assertTrue(exported.has("final"))
    println("X export output: ${output.toByteArray().size} bytes, 800 events, complete JSON")
  }

  @Test fun exportRequiresACompletedRecording() {
    assertThrows(IllegalStateException::class.java) { XDiagnostics.share(context) }
    XDiagnostics.start(context, true)
    assertThrows(IllegalStateException::class.java) { XDiagnostics.share(context) }
    assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    XDiagnostics.finish(context, "test")
  }

  @Test fun recordingEndsAtDeadlineAndDoesNotRecordLaterEvents() {
    XDiagnostics.start(context, true)
    XDiagnostics.record(context, "test", JSONObject().put("value", 1), true)
    ShadowSystemClock.advanceBy(Duration.ofMinutes(10))
    XDiagnostics.record(context, "late", JSONObject(), true)
    val status = XDiagnostics.status(context)
    assertEquals("complete", status["state"])
    val report = JSONObject(status["json"] as String)
    assertTrue(report.has("final"))
    val events = report.getJSONArray("events")
    assertEquals(2, events.length())
    assertFalse(events.toString().contains("late"))
  }

  @Test fun rebootEndsTheRecordingEvenWhenElapsedTimeIsAmbiguous() {
    Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 7)
    XDiagnostics.start(context, true)
    Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 8)
    assertEquals("complete", XDiagnostics.status(context)["state"])
  }

  @Test fun recordingIsBoundedAndRestartReplacesIt() {
    XDiagnostics.start(context, false)
    repeat(810) { XDiagnostics.record(context, "test", JSONObject().put("index", it), true) }
    XDiagnostics.finish(context, "test")
    val report = JSONObject(XDiagnostics.status(context)["json"] as String)
    assertEquals(800, report.getJSONArray("events").length())
    assertEquals(11, report.getInt("droppedEvents"))
    XDiagnostics.start(context, true)
    XDiagnostics.finish(context, "test")
    val replacement = JSONObject(XDiagnostics.status(context)["json"] as String)
    assertEquals(1, replacement.getJSONArray("events").length())
    assertEquals(0, replacement.getInt("droppedEvents"))
  }
}
