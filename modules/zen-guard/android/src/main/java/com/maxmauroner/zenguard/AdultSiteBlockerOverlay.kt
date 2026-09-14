package com.maxmauroner.zenguard

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** Neutral, full-screen boundary for a matched website. It never renders the sensitive host. */
internal class AdultSiteBlockerOverlay(private val service: AccessibilityService) {
  private val windowManager = service.getSystemService(WindowManager::class.java)
  private var root: View? = null
  private var packageName: String? = null

  val isShowing: Boolean get() = root?.isAttachedToWindow == true
  fun shownPackage(): String? = packageName.takeIf { isShowing }

  fun show(browserPackage: String, onBack: () -> Unit, onHome: () -> Unit) {
    if (isShowing && packageName == browserPackage) return
    hide()
    packageName = browserPackage

    val accent = service.getColor(R.color.zen_accent)
    val copy = service.getColor(R.color.zen_copy)
    val muted = service.getColor(R.color.zen_muted)
    val faint = service.getColor(R.color.zen_faint)
    val night = service.getColor(R.color.zen_night)
    val panel2 = service.getColor(R.color.zen_panel2)
    val line2 = service.getColor(R.color.zen_line2)

    val container = LinearLayout(service).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(night)
      setPadding(dp(24), dp(16), dp(24), dp(16))
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
      accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
      setOnApplyWindowInsetsListener { view, insets ->
        @Suppress("DEPRECATION") val top = insets.systemWindowInsetTop
        @Suppress("DEPRECATION") val bottom = insets.systemWindowInsetBottom
        view.setPadding(dp(24), dp(16) + top, dp(24), dp(16) + bottom)
        insets
      }
    }

    val top = LinearLayout(service).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    val surface = styledText(service.getString(R.string.zen_guard_site_surface), 11f, muted, bold = true).apply {
      letterSpacing = 0.12f
    }
    top.addView(surface, LinearLayout.LayoutParams(0, -2, 1f))
    top.addView(styledText(service.getString(R.string.zen_guard_state_closed), 11f, accent, bold = true).apply {
      letterSpacing = 0.08f
      gravity = Gravity.CENTER
      setPadding(dp(12), dp(5), dp(12), dp(5))
      background = roundedBackground(panel2, line2, dp(20).toFloat())
    })
    container.addView(top, matchWrap())

    val scroll = ScrollView(service).apply {
      isFillViewport = true
      isVerticalScrollBarEnabled = false
      overScrollMode = View.OVER_SCROLL_NEVER
    }
    container.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    val body = LinearLayout(service).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
    }
    scroll.addView(body, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))

    body.addView(LinearLayout(service).apply {
      gravity = Gravity.CENTER_HORIZONTAL
      addView(ZenStillMarkView(service))
    }, matchWrap(dp(12)))
    body.addView(styledText(service.getString(R.string.zen_guard_site_eyebrow), 11f, accent, bold = true).apply {
      letterSpacing = 0.14f
      gravity = Gravity.CENTER
    }, matchWrap(dp(12)))
    body.addView(styledTitle(service.getString(R.string.zen_guard_site_title), copy, accent), matchWrap(dp(8)))
    body.addView(styledText(service.getString(R.string.zen_guard_site_detail), 15f, muted).apply {
      setLineSpacing(0f, 1.4f)
      gravity = Gravity.CENTER
      textAlignment = View.TEXT_ALIGNMENT_CENTER
    }, matchWrap(dp(10)))

    val actions = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
    actions.addView(actionButton(service.getString(R.string.zen_guard_site_go_back), true).apply {
      setOnClickListener { onBack() }
    }, matchWrap())
    actions.addView(actionButton(service.getString(R.string.zen_guard_go_home), false).apply {
      setOnClickListener { onHome() }
    }, matchWrap(dp(10)))
    actions.addView(styledText(service.getString(R.string.zen_guard_quiet_boundary), 11f, faint).apply {
      gravity = Gravity.CENTER
      setPadding(0, dp(12), 0, dp(4))
    }, matchWrap())
    container.addView(actions, matchWrap())

    root = container
    try {
      windowManager.addView(container, WindowManager.LayoutParams(
        -1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT,
      ))
    } catch (_: RuntimeException) {
      hide()
    }
  }

  fun hide() {
    val existing = root
    root = null
    packageName = null
    existing?.let {
      try { windowManager.removeView(it) } catch (_: IllegalArgumentException) { }
    }
  }

  private fun styledText(value: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(service).apply {
    text = value
    setTextColor(color)
    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size)
    if (bold) typeface = Typeface.DEFAULT_BOLD
    includeFontPadding = false
  }

  private fun styledTitle(value: String, copy: Int, accent: Int): TextView {
    val normalized = value.replace("\\n", "\n")
    val styled = android.text.SpannableString(normalized).apply {
      val start = normalized.lastIndexOf('\n').takeIf { it >= 0 }?.plus(1) ?: 0
      setSpan(android.text.style.ForegroundColorSpan(accent), start, normalized.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return styledText(styled, 28f, copy, bold = true).apply {
      gravity = Gravity.CENTER
      textAlignment = View.TEXT_ALIGNMENT_CENTER
      setLineSpacing(0f, 1.12f)
      letterSpacing = -0.02f
    }
  }

  private fun actionButton(label: String, primary: Boolean) = Button(service).apply {
    text = label
    isAllCaps = false
    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
    typeface = Typeface.DEFAULT_BOLD
    minHeight = dp(52)
    minimumHeight = dp(52)
    stateListAnimator = null
    elevation = 0f
    setPadding(dp(20), dp(12), dp(20), dp(12))
    setTextColor(service.getColor(if (primary) R.color.zen_on_accent else R.color.zen_copy))
    background = roundedBackground(
      service.getColor(if (primary) R.color.zen_accent else R.color.zen_panel2),
      service.getColor(if (primary) R.color.zen_accent else R.color.zen_line2),
      dp(16).toFloat(),
    )
  }

  private fun roundedBackground(fill: Int, stroke: Int, radius: Float) = GradientDrawable().apply {
    setColor(fill); cornerRadius = radius; setStroke(dp(1), stroke)
  }
  private fun dp(value: Int) = (value * service.resources.displayMetrics.density).roundToInt()
  private fun matchWrap(topMargin: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { this.topMargin = topMargin }
}
