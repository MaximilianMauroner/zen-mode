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
import org.junit.After
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
class DiagnosticsTest {
  private val context: Context get() = RuntimeEnvironment.getApplication()

  @After fun clearRecording() = Diagnostics.clear(context)

  @Test fun recordsOnlyDuringTheRequestedInterval() {
    Diagnostics.record(context, "before", JSONObject(), true)
    assertEquals("empty", Diagnostics.status(context)["state"])
    Diagnostics.start(context, true, 2_000L)
    Diagnostics.record(context, "inside", JSONObject().put("surface", "HOME"), true)
    ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
    Diagnostics.record(context, "after", JSONObject(), true)
    val trace = JSONObject(Diagnostics.status(context)["json"] as String)
    val events = trace.getJSONArray("events").toString()
    assertTrue(events.contains("inside"))
    assertFalse(events.contains("before"))
    assertFalse(events.contains("after"))
    assertEquals("complete", Diagnostics.status(context)["state"])
  }

  @Test fun stopAndClearGiveTheUserControl() {
    Diagnostics.start(context, true, 60_000L)
    assertThrows(IllegalStateException::class.java) { Diagnostics.start(context, true, 60_000L) }
    Diagnostics.finish(context, "user")
    assertEquals("complete", Diagnostics.status(context)["state"])
    Diagnostics.clear(context)
    assertEquals("empty", Diagnostics.status(context)["state"])
    assertFalse(Diagnostics.isRecording(context))
  }

  @Test fun interruptedOlderRecordingRemainsAvailable() {
    Diagnostics.start(context, true, 60_000L)
    Diagnostics.record(context, "x", JSONObject().put("surface", "HOME"), true)
    val storage = context.getSharedPreferences("x_diagnostics", Context.MODE_PRIVATE)
    val olderTrace = JSONObject(storage.getString("trace", null)!!).put("schema", 1)
    storage.edit().putString("trace", olderTrace.toString()).putBoolean("active", false).commit()
    val status = Diagnostics.status(context)
    assertEquals("complete", status["state"])
    val trace = JSONObject(status["json"] as String)
    assertEquals("interrupted", trace.getString("endReason"))
    assertTrue(trace.getJSONArray("events").toString().contains("HOME"))
  }

  @Test fun rejectsInvalidDurations() {
    assertThrows(IllegalArgumentException::class.java) { Diagnostics.start(context, true, 0L) }
    assertThrows(IllegalArgumentException::class.java) { Diagnostics.start(context, true, 3_600_001L) }
    assertEquals("empty", Diagnostics.status(context)["state"])
  }

  @Test fun repeatedInterleavedEventsDoNotFillTheRecording() {
    Diagnostics.start(context, true, 60_000L)
    repeat(100) {
      Diagnostics.record(context, "instagram", JSONObject().put("surface", "HOME_FEED"))
      Diagnostics.record(context, "instagram_action", JSONObject().put("action", "None"))
    }
    Diagnostics.finish(context, "user")
    val trace = JSONObject(Diagnostics.status(context)["json"] as String)
    assertEquals(3, trace.getJSONArray("events").length())
    assertEquals(0, trace.getInt("droppedEvents"))
  }

  @Test fun fullRecordingCanBeExportedWithoutExceedingBinderLimit() {
    Diagnostics.start(context, true, 600_000L)
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
    Diagnostics.finish(context, "duration")
    Diagnostics.share(context)
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
    assertEquals(Diagnostics.status(context)["json"], output)
    val exported = JSONObject(output)
    assertEquals(800, exported.getJSONArray("events").length())
    assertTrue(exported.has("final"))
    println("X export output: ${output.toByteArray().size} bytes, 800 events, complete JSON")

    Diagnostics.share(context, "Problem: A rule did not run")
    val reportChooser = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
    val reportSend = requireNotNull(reportChooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
    val reportUri = requireNotNull(reportSend.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
    val report = context.contentResolver.openInputStream(reportUri)!!.bufferedReader().use { JSONObject(it.readText()) }
    assertEquals("Problem: A rule did not run", report.getString("report"))
    assertEquals(800, report.getJSONObject("diagnostics").getJSONArray("events").length())
    assertEquals("Problem: A rule did not run", reportSend.getStringExtra(Intent.EXTRA_TEXT))
    Diagnostics.clear(context)
    assertThrows(java.io.FileNotFoundException::class.java) { context.contentResolver.openInputStream(reportUri) }
  }

  @Test fun exportRequiresACompletedRecording() {
    assertThrows(IllegalStateException::class.java) { Diagnostics.share(context) }
    Diagnostics.start(context, true, 600_000L)
    assertThrows(IllegalStateException::class.java) { Diagnostics.share(context) }
    assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    Diagnostics.finish(context, "test")
  }

  @Test fun recordingEndsAtDeadlineAndDoesNotRecordLaterEvents() {
    Diagnostics.start(context, true, 600_000L)
    Diagnostics.record(context, "test", JSONObject().put("value", 1), true)
    ShadowSystemClock.advanceBy(Duration.ofMinutes(10))
    Diagnostics.record(context, "late", JSONObject(), true)
    val status = Diagnostics.status(context)
    assertEquals("complete", status["state"])
    val report = JSONObject(status["json"] as String)
    assertTrue(report.has("final"))
    val events = report.getJSONArray("events")
    assertEquals(2, events.length())
    assertFalse(events.toString().contains("late"))
  }

  @Test fun rebootEndsTheRecordingEvenWhenElapsedTimeIsAmbiguous() {
    Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 7)
    Diagnostics.start(context, true, 600_000L)
    Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 8)
    assertEquals("complete", Diagnostics.status(context)["state"])
  }

  @Test fun recordingIsBoundedAndRestartReplacesIt() {
    Diagnostics.start(context, false, 600_000L)
    repeat(810) { Diagnostics.record(context, "test", JSONObject().put("index", it), true) }
    Diagnostics.finish(context, "test")
    val report = JSONObject(Diagnostics.status(context)["json"] as String)
    assertEquals(800, report.getJSONArray("events").length())
    assertEquals(11, report.getInt("droppedEvents"))
    Diagnostics.start(context, true, 600_000L)
    Diagnostics.finish(context, "test")
    val replacement = JSONObject(Diagnostics.status(context)["json"] as String)
    assertEquals(1, replacement.getJSONArray("events").length())
    assertEquals(0, replacement.getInt("droppedEvents"))
  }
}
