package com.maxmauroner.zenguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdultSitePolicyTest {
  @Test fun `canonicalizes domains and http urls`() {
    assertEquals("example.com", AdultSitePolicy.canonicalRule(" Example.COM. "))
    assertEquals("example.com", AdultSitePolicy.canonicalRule("https://Example.com:8443/a?q=1"))
    assertEquals("xn--bcher-kva.example", AdultSitePolicy.canonicalRule("bücher.example"))
  }

  @Test fun `rejects unsafe or overbroad rules`() {
    listOf("com", "co.uk", "localhost", "127.0.0.1", "https://user@example.com", "ftp://example.com", "not a host")
      .forEach { value -> assertThrows(value, IllegalArgumentException::class.java) { AdultSitePolicy.canonicalRule(value) } }
  }

  @Test fun `matches exact hosts and subdomains without lookalikes`() {
    val custom = setOf("example.com")
    assertTrue(AdultSitePolicy.isBlocked("example.com", custom))
    assertTrue(AdultSitePolicy.isBlocked("www.example.com", custom))
    assertFalse(AdultSitePolicy.isBlocked("notexample.com", custom))
    assertFalse(AdultSitePolicy.isBlocked("example.com.evil.test", custom))
    assertTrue(AdultSitePolicy.isBlocked("www.pornhub.com", emptySet()))
  }

  @Test fun `extracts hosts from realistic address bar values`() {
    assertEquals("example.com", AdultSitePolicy.hostFromAddressBar("example.com/path?q=1"))
    assertEquals("example.com", AdultSitePolicy.hostFromAddressBar("https://example.com:443/path"))
    assertEquals("safe.example", AdultSitePolicy.hostFromAddressBar("https://safe.example/?next=pornhub.com"))
    assertNull(AdultSitePolicy.hostFromAddressBar("search words"))
    assertNull(AdultSitePolicy.hostFromAddressBar("https://user@example.com/path"))
    assertNull(AdultSitePolicy.hostFromAddressBar("https://example.com@evil.test/path"))
    assertNull(AdultSitePolicy.hostFromAddressBar("about:blank"))
  }

  @Test fun `bounds user supplied rules`() {
    assertThrows(IllegalArgumentException::class.java) {
      AdultSitePolicy.canonicalRule("a".repeat(AdultSitePolicy.MAX_INPUT_LENGTH + 1) + ".com")
    }
  }
}
