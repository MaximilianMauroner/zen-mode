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

/**
 * The intent question shown when an intent-gated app opens: confirming starts
 * one timed visit of the app's fixed length. Leaving goes home without
 * starting a visit.
 *
 * While the app's downtime runs, the same overlay instead shows when it opens
 * again, with nothing to start.
 *
 * Full-screen accessibility overlay, like the Instagram blocker, so it needs
 * no extra permission beyond the accessibility service itself.
 */
internal class IntentOverlay(
  private val service: AccessibilityService,
) {
  private val windowManager = service.getSystemService(WindowManager::class.java)
  private var root: View? = null
  private var shownFor: String? = null
  private var isCooldown = false

  fun isShowing(): Boolean = root != null
  fun shownPackage(): String? = shownFor
  fun isCooldown(): Boolean = root != null && isCooldown

  fun showAsk(
    packageName: String,
    appLabel: String,
    sessionMinutes: Int,
    onStart: () -> Unit,
    onLeave: () -> Unit,
  ) {
    show(
      packageName = packageName,
      cooldown = false,
      appLabel = appLabel,
      cooldownRemainingMs = 0L,
      sessionMinutes = sessionMinutes,
      onStart = onStart,
      onLeave = onLeave,
    )
  }

  fun showCooldown(
    packageName: String,
    appLabel: String,
    cooldownRemainingMs: Long,
    onLeave: () -> Unit,
  ) {
    show(
      packageName = packageName,
      cooldown = true,
      appLabel = appLabel,
      cooldownRemainingMs = cooldownRemainingMs,
      sessionMinutes = 0,
      onStart = {},
      onLeave = onLeave,
    )
  }

  private fun show(
    packageName: String,
    cooldown: Boolean,
    appLabel: String,
    cooldownRemainingMs: Long,
    sessionMinutes: Int,
    onStart: () -> Unit,
    onLeave: () -> Unit,
  ) {
    hide()
    shownFor = packageName
    isCooldown = cooldown

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
      setOnApplyWindowInsetsListener { v, insets ->
        @Suppress("DEPRECATION")
        val topInset = insets.systemWindowInsetTop
        @Suppress("DEPRECATION")
        val bottomInset = insets.systemWindowInsetBottom
        v.setPadding(dp(24), dp(16) + topInset, dp(24), dp(16) + bottomInset)
        insets
      }
    }

    val top = LinearLayout(service).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    val tagContainer = LinearLayout(service).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    val dot = View(service).apply {
      background = roundedBackground(accent, accent, dp(3).toFloat())
    }
    tagContainer.addView(dot, fixedSize(dp(6)))
    val topLabel = styledText("  " + service.getString(R.string.zen_guard_intent_eyebrow), 11f, muted, bold = true).apply {
      letterSpacing = 0.12f
    }
    tagContainer.addView(topLabel, wrapContent())
    top.addView(tagContainer, weightedWrap(weight = 1f))

    val state = styledText(
      if (cooldown) formatCooldown(cooldownRemainingMs) else service.getString(R.string.zen_guard_intent_state_ask, sessionMinutes),
      11f,
      if (cooldown) muted else accent,
      bold = true,
    ).apply {
      letterSpacing = 0.08f
      gravity = Gravity.CENTER
      setPadding(dp(12), dp(5), dp(12), dp(5))
      background = roundedBackground(panel2, line2, dp(20).toFloat())
    }
    top.addView(state, wrapContent())
    container.addView(top, matchWrap())

    val scroll = ScrollView(service).apply {
      isFillViewport = true
      isVerticalScrollBarEnabled = false
      overScrollMode = View.OVER_SCROLL_NEVER
    }
    container.addView(scroll, weightedHeight())

    val centerBody = LinearLayout(service).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
    }
    scroll.addView(
      centerBody,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER_VERTICAL,
      ),
    )

    val mark = ZenStillMarkView(service)
    val markWrapper = LinearLayout(service).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_HORIZONTAL
    }
    markWrapper.addView(mark, wrapContent())
    centerBody.addView(markWrapper, matchWrap(topMargin = dp(12)))

    val eyebrow = styledText(
      if (cooldown) service.getString(R.string.zen_guard_intent_eyebrow_cooldown) else service.getString(R.string.zen_guard_intent_eyebrow),
      11f,
      accent,
      bold = true,
    ).apply {
      letterSpacing = 0.14f
      gravity = Gravity.CENTER_HORIZONTAL
    }
    centerBody.addView(eyebrow, matchWrap(topMargin = dp(12)))

    val title = styledTitle(
      if (cooldown) service.getString(R.string.zen_guard_intent_cooldown_title) else service.getString(R.string.zen_guard_intent_title, sessionMinutes),
      copy,
      accent,
    ).apply {
      gravity = Gravity.CENTER_HORIZONTAL
    }
    centerBody.addView(title, matchWrap(topMargin = dp(8)))

    val description = styledText(
      if (cooldown) service.getString(R.string.zen_guard_intent_cooldown_detail, appLabel) else service.getString(R.string.zen_guard_intent_detail, appLabel, sessionMinutes),
      15f,
      muted,
    ).apply {
      setLineSpacing(0f, 1.4f)
      gravity = Gravity.CENTER_HORIZONTAL
      textAlignment = View.TEXT_ALIGNMENT_CENTER
    }
    centerBody.addView(description, matchWrap(topMargin = dp(10)))

    val actions = LinearLayout(service).apply {
      orientation = LinearLayout.VERTICAL
    }

    if (!cooldown) {
      val start = actionButton(service.getString(R.string.zen_guard_intent_start, sessionMinutes), primary = true).apply {
        setOnClickListener { onStart() }
      }
      actions.addView(start, matchWrap())
    }

    val leave = actionButton(
      if (cooldown) service.getString(R.string.zen_guard_intent_cooldown_leave) else service.getString(R.string.zen_guard_intent_leave),
      primary = cooldown,
    ).apply {
      setOnClickListener { onLeave() }
    }
    actions.addView(leave, matchWrap(topMargin = if (cooldown) 0 else dp(10)))

    val footerNote = styledText(
      service.getString(R.string.zen_guard_quiet_boundary),
      11f,
      faint,
    ).apply {
      gravity = Gravity.CENTER
      setPadding(0, dp(12), 0, dp(4))
    }
    actions.addView(footerNote, matchWrap())

    container.addView(actions, matchWrap())

    val params = WindowManager.LayoutParams(
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.TRANSLUCENT,
    )
    root = container
    try {
      windowManager.addView(container, params)
    } catch (_: RuntimeException) {
      hide()
    }
  }

  fun hide() {
    val existingRoot = root
    root = null
    shownFor = null
    isCooldown = false
    existingRoot?.let {
      try {
        windowManager.removeView(it)
      } catch (_: IllegalArgumentException) {
        // The view was already detached.
      }
    }
  }

  private fun formatCooldown(remainingMs: Long): String {
    val totalMinutes = (remainingMs / 60_000L).coerceAtLeast(1L)
    val hours = (totalMinutes / 60).toInt()
    val minutes = (totalMinutes % 60).toInt()
    return if (hours > 0) {
      service.getString(R.string.zen_guard_stat_hours_minutes, hours, minutes)
    } else {
      service.getString(R.string.zen_guard_stat_minutes, minutes)
    }
  }

  private fun styledText(text: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(service).apply {
    this.text = text
    setTextColor(color)
    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size)
    if (bold) typeface = Typeface.DEFAULT_BOLD
    includeFontPadding = false
  }

  private fun styledTitle(value: String, copy: Int, accent: Int): TextView {
    val normalized = value.replace("\\n", "\n")
    val spannable = android.text.SpannableString(normalized).apply {
      val accentStart = normalized.lastIndexOf('\n').takeIf { it >= 0 }?.plus(1) ?: 0
      setSpan(android.text.style.ForegroundColorSpan(accent), accentStart, normalized.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return styledText(spannable, 28f, copy, bold = true).apply {
      setLineSpacing(0f, 1.12f)
      letterSpacing = -0.02f
      gravity = Gravity.CENTER_HORIZONTAL
      textAlignment = View.TEXT_ALIGNMENT_CENTER
    }
  }

  private fun actionButton(label: String, primary: Boolean) = Button(service).apply {
    text = label
    setAllCaps(false)
    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
    typeface = Typeface.DEFAULT_BOLD
    minHeight = dp(52)
    minimumHeight = dp(52)
    stateListAnimator = null
    elevation = 0f
    setPadding(dp(20), dp(12), dp(20), dp(12))
    if (primary) {
      setTextColor(service.getColor(R.color.zen_on_accent))
      background = roundedBackground(
        service.getColor(R.color.zen_accent),
        service.getColor(R.color.zen_accent),
        dp(16).toFloat(),
      )
    } else {
      setTextColor(service.getColor(R.color.zen_copy))
      background = roundedBackground(
        service.getColor(R.color.zen_panel2),
        service.getColor(R.color.zen_line2),
        dp(16).toFloat(),
      )
    }
  }

  private fun roundedBackground(fill: Int, stroke: Int, radius: Float = dp(14).toFloat()) = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = radius
    setStroke(dp(1), stroke)
  }

  private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).roundToInt()

  private fun wrapContent() = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.WRAP_CONTENT,
    LinearLayout.LayoutParams.WRAP_CONTENT,
  )

  private fun fixedSize(size: Int) = LinearLayout.LayoutParams(size, size)

  private fun weightedWrap(weight: Float) = LinearLayout.LayoutParams(
    0,
    LinearLayout.LayoutParams.WRAP_CONTENT,
    weight,
  )

  private fun weightedHeight() = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT,
    0,
    1f,
  )

  private fun matchWrap(topMargin: Int = 0) = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT,
    LinearLayout.LayoutParams.WRAP_CONTENT,
  ).apply {
    this.topMargin = topMargin
  }
}
