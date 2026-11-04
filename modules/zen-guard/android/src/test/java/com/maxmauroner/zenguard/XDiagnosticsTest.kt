package com.maxmauroner.zenguard

import android.content.Context
import android.provider.Settings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class XDiagnosticsTest {
  private val context: Context get() = RuntimeEnvironment.getApplication()

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
