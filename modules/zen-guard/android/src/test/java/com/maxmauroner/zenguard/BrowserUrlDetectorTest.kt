package com.maxmauroner.zenguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

  @Test fun `Firefox 157 url box resolves the address from its description`() {
    val detected = BrowserUrlDetector.detect(
      "org.mozilla.firefox",
      listOf(BrowserNodeSignal("ADDRESSBAR_URL_BOX", "", true, FIREFOX_157_DESCRIPTION)),
    )
    assertEquals("example.com", detected?.host)
    assertEquals(8, detected?.browserMask)
    assertTrue(BrowserUrlDetector.isAddressBar("org.mozilla.firefox", "ADDRESSBAR_URL_BOX"))
  }

  @Test fun `Firefox 157 placeholder malformed hidden wrong and conflicting signals fail open`() {
    fun firefox(vararg nodes: BrowserNodeSignal) = BrowserUrlDetector.detect("org.mozilla.firefox", nodes.toList())
    assertNull(firefox(BrowserNodeSignal("ADDRESSBAR_URL_BOX", "", true, "Search or enter address")))
    assertNull(firefox(BrowserNodeSignal("ADDRESSBAR_URL_BOX", "", true, "not a host. Search or enter address")))
    assertNull(firefox(BrowserNodeSignal("ADDRESSBAR_URL_BOX", "", false, FIREFOX_157_DESCRIPTION)))
    assertNull(firefox(BrowserNodeSignal("page:link", "", true, FIREFOX_157_DESCRIPTION)))
    assertNull(firefox(
      BrowserNodeSignal("ADDRESSBAR_URL_BOX", "", true, FIREFOX_157_DESCRIPTION),
      BrowserNodeSignal("org.mozilla.firefox:id/mozac_browser_toolbar_url_view", "other.example", true),
    ))
    assertNull(BrowserUrlDetector.detect(
      "com.android.chrome",
      listOf(BrowserNodeSignal("ADDRESSBAR_URL_BOX", "", true, FIREFOX_157_DESCRIPTION)),
    ))
    assertFalse(BrowserUrlDetector.isAddressBar("com.android.chrome", "ADDRESSBAR_URL_BOX"))
  }

  @Test fun `earlier Firefox toolbar ids still use their text`() {
    val detected = BrowserUrlDetector.detect(
      "org.mozilla.firefox",
      listOf(BrowserNodeSignal("org.mozilla.firefox:id/mozac_browser_toolbar_url_view", "example.com", true, "Address")),
    )
    assertEquals("example.com", detected?.host)
  }

  private companion object {
    const val FIREFOX_157_DESCRIPTION = " example.com. Search or enter address"
  }
}
