package com.maxmauroner.zenguard

import java.net.IDN
import java.net.URI

/** Pure host parsing and matching. Browser nodes, persistence, and UI stay outside this policy. */
internal object AdultSitePolicy {
  const val MAX_CUSTOM_HOSTS = 100
  const val MAX_INPUT_LENGTH = 2_048

  private val publicSuffixOnly = setOf(
    "co.uk", "org.uk", "ac.uk", "com.au", "net.au", "org.au", "co.nz", "co.jp",
  )

  fun canonicalRule(input: String): String {
    val value = input.trim()
    require(value.isNotEmpty()) { "Enter a domain like example.com" }
    require(value.length <= MAX_INPUT_LENGTH) { "That site is too long" }

    val rawHost = if (value.contains("://")) {
      val uri = parseUri(value)
      require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
        "Only website addresses can be blocked"
      }
      require(uri.rawUserInfo == null && !uri.rawAuthority.orEmpty().contains('@')) {
        "Website addresses with credentials are not supported"
      }
      authorityHost(uri.rawAuthority.orEmpty())
    } else {
      require(value.none { it == '/' || it == '?' || it == '#' || it == '@' || it == ':' }) {
        "Enter a domain like example.com"
      }
      value
    }
    return canonicalHost(rawHost)
  }

  /** Address bars often omit the scheme but retain a path. Search terms must not become hosts. */
  fun hostFromAddressBar(value: String): String? {
    val text = value.trim()
    if (text.isEmpty() || text.length > MAX_INPUT_LENGTH || text.any(Char::isWhitespace)) return null
    return try {
      if (text.contains("://")) {
        val uri = parseUri(text)
        if (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) return null
        if (uri.rawUserInfo != null || uri.rawAuthority.orEmpty().contains('@')) return null
        canonicalHost(authorityHost(uri.rawAuthority.orEmpty()))
      } else {
        val authority = text.substringBefore('/').substringBefore('?').substringBefore('#')
        canonicalHost(authorityHost(authority))
      }
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  fun isBlocked(host: String, customHosts: Set<String>): Boolean =
    (AdultSiteCatalog.HOSTS + customHosts).any { rule -> host == rule || host.endsWith(".$rule") }

  private fun parseUri(value: String): URI = try {
    URI(value)
  } catch (_: Exception) {
    throw IllegalArgumentException("Enter a valid website address")
  }

  private fun authorityHost(authority: String): String {
    require(authority.isNotBlank() && !authority.startsWith('[')) { "Enter a valid website address" }
    val colonCount = authority.count { it == ':' }
    require(colonCount <= 1) { "IP addresses are not supported" }
    if (colonCount == 0) return authority
    val host = authority.substringBeforeLast(':')
    val port = authority.substringAfterLast(':').toIntOrNull()
    require(port != null && port in 1..65_535) { "Enter a valid website address" }
    return host
  }

  private fun canonicalHost(input: String): String {
    val withoutDot = input.trim().trimEnd('.')
    require(withoutDot.isNotEmpty()) { "Enter a valid website address" }
    val ascii = try {
      IDN.toASCII(withoutDot, IDN.USE_STD3_ASCII_RULES).lowercase()
    } catch (_: IllegalArgumentException) {
      throw IllegalArgumentException("Enter a valid website address")
    }
    require(ascii.length <= 253 && '.' in ascii) { "Enter a complete domain like example.com" }
    require(ascii !in publicSuffixOnly) { "Enter a site, not a public domain suffix" }
    require(!isIpLiteral(ascii) && ascii != "localhost" && !ascii.endsWith(".local")) {
      "Local and IP addresses are not supported"
    }
    require(ascii.split('.').all { label ->
      label.isNotEmpty() && label.length <= 63 && !label.startsWith('-') && !label.endsWith('-')
    }) { "Enter a valid website address" }
    return ascii
  }

  private fun isIpLiteral(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == 4 && parts.all { part ->
      part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull() in 0..255
    }
  }
}
