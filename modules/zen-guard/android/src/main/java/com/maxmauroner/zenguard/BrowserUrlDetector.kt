package com.maxmauroner.zenguard

internal data class BrowserNodeSignal(
  val viewId: String,
  val text: String,
  val visible: Boolean,
)

internal data class BrowserUrlDetection(
  val packageName: String,
  val browserMask: Int,
  val host: String,
)

/** Package-specific, address-bar-only detection. It never inspects arbitrary page text. */
internal object BrowserUrlDetector {
  private data class Adapter(val mask: Int, val addressBarIds: Set<String>)

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
    ),
  )

  fun supports(packageName: String?): Boolean = packageName in adapters

  fun isAddressBar(packageName: String, viewId: String): Boolean =
    viewId in (adapters[packageName]?.addressBarIds ?: emptySet())

  fun detect(packageName: String?, nodes: List<BrowserNodeSignal>): BrowserUrlDetection? {
    val name = packageName ?: return null
    val adapter = adapters[name] ?: return null
    val hosts = nodes.asSequence()
      .filter { it.visible && it.viewId in adapter.addressBarIds }
      .mapNotNull { AdultSitePolicy.hostFromAddressBar(it.text) }
      .distinct()
      .toList()
    if (hosts.size != 1) return null
    return BrowserUrlDetection(name, adapter.mask, hosts.single())
  }
}
