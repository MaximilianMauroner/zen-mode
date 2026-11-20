package com.maxmauroner.zenguard

import android.accessibilityservice.AccessibilityService
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Date
import kotlin.math.roundToInt

internal enum class AppRuleOverlayMode { ASK, COOLDOWN, LIMIT, KIDS_END, EARN }

/** One whole-app boundary for daily budgets, rolling allowances, timed visits, and earned time. */
internal class AppRuleOverlay(
  private val service: AccessibilityService,
) {
  private val windowManager = service.getSystemService(WindowManager::class.java)
  private var root: View? = null
  private var shownFor: String? = null
  var mode: AppRuleOverlayMode? = null
    private set
  private var titleView: TextView? = null
  private var descriptionView: TextView? = null
  private var availabilityView: TextView? = null
  private var startButtons: List<Pair<Int, Button>> = emptyList()
  private var capLeftMs: Long? = null
  private var entranceAnimators: List<ObjectAnimator> = emptyList()

  fun isShowing(): Boolean = root?.isAttachedToWindow == true
  fun requestedPackage(): String? = shownFor

  fun updateCountdown(remainingMs: Long) {
    val clock = formatAvailabilityCountdown(remainingMs)
    availabilityView?.text = service.getString(R.string.zen_guard_available_again_in, clock)
  }

  /**
   * Asks how long to stay. Each of [choices] is one Start button that names its end time. A daily
   * cap with [capLeftMs] left ends a visit early, so no button names a time after the cap runs out.
   */
  fun showAsk(
    packageName: String,
    appLabel: String,
    choices: List<Int>,
    nowWallMs: Long,
    capLeftMs: Long?,
    onStart: (Int) -> Unit,
    onLeave: () -> Unit,
    onAttached: () -> Unit,
    onWatchOneVideo: (() -> Unit)? = null,
  ) {
    require(choices.isNotEmpty()) { "The ask card needs at least one visit length." }
    show(
      packageName = packageName,
      mode = AppRuleOverlayMode.ASK,
      appLabel = appLabel,
      cooldownRemainingMs = 0L,
      onPrimary = {},
      onLeave = onLeave,
      onAttached = onAttached,
      choices = choices,
      nowWallMs = nowWallMs,
      capLeftMs = capLeftMs,
      onChoose = onStart,
      onWatchOneVideo = onWatchOneVideo,
    )
  }

  fun showCooldown(
    packageName: String,
    appLabel: String,
    cooldownRemainingMs: Long,
    onLeave: () -> Unit,
    onAttached: () -> Unit,
  ) {
    show(
      packageName = packageName,
      mode = AppRuleOverlayMode.COOLDOWN,
      appLabel = appLabel,
      cooldownRemainingMs = cooldownRemainingMs,
      onPrimary = {},
      onLeave = onLeave,
      onAttached = onAttached,
    )
  }

  fun showKids(packageName: String, appLabel: String, onLeave: () -> Unit, onAttached: () -> Unit) {
    if (shownFor == packageName && mode == AppRuleOverlayMode.KIDS_END) {
      root?.let { container ->
        if (container.isAttachedToWindow) onAttached()
        else container.doOnFirstAttach { if (root === container) onAttached() }
      }
      return
    }
    hide()
    shownFor = packageName
    mode = AppRuleOverlayMode.KIDS_END
    val container = LinearLayout(service).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(service.getColor(R.color.zen_night))
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
    val scroll = ScrollView(service).apply {
      isFillViewport = true
      isVerticalScrollBarEnabled = false
      overScrollMode = View.OVER_SCROLL_NEVER
    }
    container.addView(scroll, weightedHeight())
    val content = LinearLayout(service).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setPadding(0, dp(32), 0, dp(32))
    }
    scroll.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    val title = styledText(service.getString(R.string.zen_guard_kids_end_title), 40f, service.getColor(R.color.zen_copy), bold = true).apply {
      gravity = Gravity.CENTER
    }
    content.addView(title, matchWrap())
    content.addView(styledText(appLabel, 20f, service.getColor(R.color.zen_muted)).apply {
      gravity = Gravity.CENTER
    }, matchWrap(topMargin = dp(16)))
    val leave = actionButton(service.getString(R.string.zen_guard_intent_cooldown_leave), primary = true).apply {
      setOnClickListener { onLeave() }
    }
    content.addView(leave, matchWrap(topMargin = dp(32)))
    val animationsEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      ValueAnimator.areAnimatorsEnabled()
    } else {
      Settings.Global.getFloat(service.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    if (animationsEnabled) {
      title.alpha = 0f
      title.scaleX = 0.92f
      title.scaleY = 0.92f
      leave.alpha = 0f
      entranceAnimators = listOf(
        ObjectAnimator.ofPropertyValuesHolder(
          title,
          PropertyValuesHolder.ofFloat(View.ALPHA, 1f),
          PropertyValuesHolder.ofFloat(View.SCALE_X, 1f),
          PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f),
        ).apply { duration = 300L },
        ObjectAnimator.ofFloat(leave, View.ALPHA, 1f).apply {
          startDelay = 150L
          duration = 200L
        },
      )
      container.doOnFirstAttach {
        if (root === container) entranceAnimators.forEach { it.start() }
      }
    }
    val params = WindowManager.LayoutParams(
      WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT,
    )
    root = container
    container.doOnFirstAttach { if (root === container) onAttached() }
    try {
      windowManager.addView(container, params)
    } catch (_: RuntimeException) {
      hide()
    }
  }

  fun showLimit(
    packageName: String,
    title: String,
    detail: String,
    remainingMs: Long,
    onLeave: () -> Unit,
    onAttached: () -> Unit,
  ) {
    show(
      packageName, AppRuleOverlayMode.LIMIT, "", remainingMs, {}, onLeave, onAttached, title, detail,
    )
  }

  /** An app that opens only on time earned in [habitAction]'s habit app. There is no countdown. */
  fun showEarn(
    packageName: String,
    title: String,
    detail: String,
    habitAction: String,
    onOpenHabit: () -> Unit,
    onLeave: () -> Unit,
    onAttached: () -> Unit,
  ) {
    show(
      packageName, AppRuleOverlayMode.EARN, "", 0L, onOpenHabit, onLeave, onAttached, title, detail, habitAction,
    )
  }

  private fun show(
    packageName: String,
    mode: AppRuleOverlayMode,
    appLabel: String,
    cooldownRemainingMs: Long,
    onPrimary: () -> Unit,
    onLeave: () -> Unit,
    onAttached: () -> Unit,
    limitTitle: String = "",
    limitDetail: String = "",
    habitAction: String = "",
    choices: List<Int> = emptyList(),
    nowWallMs: Long = 0L,
    capLeftMs: Long? = null,
    onChoose: (Int) -> Unit = {},
    onWatchOneVideo: (() -> Unit)? = null,
  ) {
    val cooldown = mode == AppRuleOverlayMode.COOLDOWN || mode == AppRuleOverlayMode.LIMIT
    val isEarn = mode == AppRuleOverlayMode.EARN
    val isLimit = mode == AppRuleOverlayMode.LIMIT || isEarn
    val sameChoices = mode != AppRuleOverlayMode.ASK || startButtons.map { it.first } == choices
    if (shownFor == packageName && this.mode == mode && sameChoices) {
      this.capLeftMs = capLeftMs
      if (cooldown) updateCountdown(cooldownRemainingMs)
      if (mode == AppRuleOverlayMode.ASK) showEndTimes(nowWallMs)
      if (isLimit) {
        titleView?.text = titleText(limitTitle, service.getColor(R.color.zen_accent))
        descriptionView?.text = limitDetail
      }
      return
    }
    hide()
    shownFor = packageName
    this.mode = mode
    this.capLeftMs = capLeftMs

    val accent = service.getColor(R.color.zen_accent)
    val copy = service.getColor(R.color.zen_copy)
    val muted = service.getColor(R.color.zen_muted)
    val night = service.getColor(R.color.zen_night)

    val container = ScrollView(service).apply {
      isFillViewport = true
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
    val content = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
    container.addView(content, FrameLayout.LayoutParams(
      FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
    ))

    val centerBody = LinearLayout(service).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
    }
    content.addView(centerBody, LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
    ))

    val mark = ZenStillMarkView(service)
    val markWrapper = LinearLayout(service).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_HORIZONTAL
    }
    markWrapper.addView(mark, wrapContent())
    centerBody.addView(markWrapper, matchWrap(topMargin = dp(12)))

    val title = styledTitle(
      if (isLimit) limitTitle else if (cooldown) service.getString(R.string.zen_guard_intent_cooldown_title) else service.getString(R.string.zen_guard_intent_title),
      copy,
      accent,
    ).apply {
      gravity = Gravity.CENTER_HORIZONTAL
    }
    titleView = title
    centerBody.addView(title, matchWrap(topMargin = dp(8)))

    val detail = when {
      isLimit -> limitDetail
      cooldown -> service.getString(R.string.zen_guard_intent_cooldown_detail, appLabel)
      onWatchOneVideo != null -> service.getString(R.string.zen_guard_intent_watch_detail)
      else -> service.getString(R.string.zen_guard_intent_detail, appLabel)
    }
    val description = styledText(detail, 15f, muted).apply {
      setLineSpacing(0f, 1.4f)
      gravity = Gravity.CENTER_HORIZONTAL
      textAlignment = View.TEXT_ALIGNMENT_CENTER
    }
    descriptionView = description
    centerBody.addView(description, matchWrap(topMargin = dp(10)))

    if (cooldown) {
      availabilityView = styledText("", 20f, copy, bold = true).apply {
        gravity = Gravity.CENTER_HORIZONTAL
      }.also { centerBody.addView(it, matchWrap(topMargin = dp(18))) }
      updateCountdown(cooldownRemainingMs)
    }

    val actions = LinearLayout(service).apply {
      orientation = LinearLayout.VERTICAL
    }

    if (isEarn) {
      val primary = actionButton(habitAction, primary = true).apply {
        setOnClickListener { onPrimary() }
      }
      actions.addView(primary, matchWrap())
    }
    if (mode == AppRuleOverlayMode.ASK) {
      startButtons = choices.mapIndexed { index, minutes ->
        val start = actionButton("", primary = index == 0).apply {
          setOnClickListener { onChoose(minutes) }
        }
        actions.addView(start, matchWrap(topMargin = if (index == 0) 0 else dp(10)))
        minutes to start
      }
      showEndTimes(nowWallMs)
      if (onWatchOneVideo != null) {
        val watch = actionButton(service.getString(R.string.zen_guard_intent_watch_one), primary = false)
        watch.setOnClickListener { onWatchOneVideo() }
        actions.addView(watch, matchWrap(topMargin = dp(10)))
      }
    }

    val leave = actionButton(
      if (cooldown) service.getString(R.string.zen_guard_intent_cooldown_leave) else service.getString(R.string.zen_guard_intent_leave),
      primary = cooldown,
    ).apply {
      setOnClickListener { onLeave() }
    }
    actions.addView(leave, matchWrap(topMargin = if (cooldown) 0 else dp(10)))

    content.addView(actions, matchWrap())

    val params = WindowManager.LayoutParams(
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.TRANSLUCENT,
    )
    root = container
    container.doOnFirstAttach { if (root === container) onAttached() }
    try {
      windowManager.addView(container, params)
    } catch (_: RuntimeException) {
      hide()
    }
  }

  /** The card stays open while the clock moves, so each Start names the end time it would give now. */
  fun showEndTimes(nowWallMs: Long) {
    val timeFormat = DateFormat.getTimeFormat(service)
    startButtons.forEach { (minutes, button) ->
      val visitMs = minOf(minutes * 60_000L, capLeftMs ?: Long.MAX_VALUE)
      button.text = service.getString(R.string.zen_guard_intent_start, minutes, timeFormat.format(Date(nowWallMs + visitMs)))
    }
  }

  fun hide() {
    val existingRoot = root
    root = null
    entranceAnimators.forEach { it.end() }
    entranceAnimators = emptyList()
    shownFor = null
    mode = null
    titleView = null
    descriptionView = null
    availabilityView = null
    startButtons = emptyList()
    capLeftMs = null
    existingRoot?.let {
      try {
        windowManager.removeView(it)
      } catch (_: IllegalArgumentException) {
        // The view was already detached.
      }
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
    return styledText(titleText(value, accent), 28f, copy, bold = true).apply {
      setLineSpacing(0f, 1.12f)
      letterSpacing = -0.02f
      gravity = Gravity.CENTER_HORIZONTAL
      textAlignment = View.TEXT_ALIGNMENT_CENTER
    }
  }

  private fun titleText(value: String, accent: Int): CharSequence {
    val normalized = value.replace("\\n", "\n")
    return android.text.SpannableString(normalized).apply {
      val accentStart = normalized.lastIndexOf('\n').takeIf { it >= 0 }?.plus(1) ?: 0
      setSpan(android.text.style.ForegroundColorSpan(accent), accentStart, normalized.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
