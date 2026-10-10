package com.maxmauroner.zenguard

internal data class BrowserNodeSignal(
  val viewId: String,
  val text: String,
  val visible: Boolean,
  val description: String = "",
)

internal data class BrowserUrlDetection(
  val packageName: String,
  val browserMask: Int,
  val host: String,
)

/** Package-specific, address-bar-only detection. It never inspects arbitrary page text. */
internal object BrowserUrlDetector {
  private data class Adapter(
    val mask: Int,
    val addressBarIds: Set<String>,
    /** Address bars with empty text whose content description reads "<address>. <label>". */
    val describedAddressBarIds: Set<String> = emptySet(),
  )

  private val adapters = mapOf(
    "com.android.chrome" to Adapter(1, setOf("com.android.chrome:id/url_bar")),
    "com.sec.android.app.sbrowser" to Adapter(
      2,
      setOf(
        "com.sec.android.app.sbrowser:id/location_bar_edit_text",
        "com.sec.android.app.sbrowser:id/location_bar_edit_text_view",
      ),
    ),
    "com.opera.browser" to Adapter(
      4,
      setOf("com.opera.browser:id/url_bar", "com.opera.browser:id/url_field"),
    ),
    "org.mozilla.firefox" to Adapter(
      8,
      setOf(
        "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
        "org.mozilla.firefox:id/mozac_browser_toolbar_edit_url_view",
      ),
      // Firefox 157 describes this box as " example.com. Search or enter address".
      describedAddressBarIds = setOf("ADDRESSBAR_URL_BOX"),
    ),
  )

  fun supports(packageName: String?): Boolean = packageName in adapters

  fun isAddressBar(packageName: String, viewId: String): Boolean {
    val adapter = adapters[packageName] ?: return false
    return viewId in adapter.addressBarIds || viewId in adapter.describedAddressBarIds
  }

  fun detect(packageName: String?, nodes: List<BrowserNodeSignal>): BrowserUrlDetection? {
    val name = packageName ?: return null
    val adapter = adapters[name] ?: return null
    val hosts = nodes.asSequence()
      .filter { it.visible }
      .mapNotNull { adapter.address(it) }
      .mapNotNull(AdultSitePolicy::hostFromAddressBar)
      .distinct()
      .toList()
    if (hosts.size != 1) return null
    return BrowserUrlDetection(name, adapter.mask, hosts.single())
  }

  /** A described bar without the "<address>. " prefix shows only its label, so it yields nothing. */
  private fun Adapter.address(node: BrowserNodeSignal): String? = when (node.viewId) {
    in addressBarIds -> node.text
    in describedAddressBarIds -> node.description.substringBefore(". ", missingDelimiterValue = "")
    else -> null
  }
}
