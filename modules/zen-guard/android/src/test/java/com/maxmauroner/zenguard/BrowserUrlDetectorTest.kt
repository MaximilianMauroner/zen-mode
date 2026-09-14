package com.maxmauroner.zenguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserUrlDetectorTest {
  @Test fun `detects only a visible known chrome address bar`() {
    val detected = BrowserUrlDetector.detect(
      "com.android.chrome",
      listOf(
        BrowserNodeSignal("page:title", "pornhub.com", true),
        BrowserNodeSignal("com.android.chrome:id/url_bar", "Example.com/path", true),
      ),
    )
    assertEquals("example.com", detected?.host)
    assertEquals(1, detected?.browserMask)
  }

  @Test fun `unknown hidden wrong and ambiguous signals fail open`() {
    assertNull(BrowserUrlDetector.detect("unknown.browser", listOf(BrowserNodeSignal("url_bar", "example.com", true))))
    assertNull(BrowserUrlDetector.detect("com.android.chrome", listOf(BrowserNodeSignal("page:title", "example.com", true))))
    assertNull(BrowserUrlDetector.detect("com.android.chrome", listOf(BrowserNodeSignal("com.android.chrome:id/url_bar", "example.com", false))))
    assertNull(BrowserUrlDetector.detect("com.android.chrome", listOf(
      BrowserNodeSignal("com.android.chrome:id/url_bar", "example.com", true),
      BrowserNodeSignal("com.android.chrome:id/url_bar", "different.example", true),
    )))
  }

  @Test fun `duplicate address bar nodes may agree`() {
    val result = BrowserUrlDetector.detect("com.sec.android.app.sbrowser", listOf(
      BrowserNodeSignal("com.sec.android.app.sbrowser:id/location_bar_edit_text", "https://example.com", true),
      BrowserNodeSignal("com.sec.android.app.sbrowser:id/location_bar_edit_text_view", "example.com", true),
    ))
    assertEquals("example.com", result?.host)
    assertEquals(2, result?.browserMask)
  }
}
