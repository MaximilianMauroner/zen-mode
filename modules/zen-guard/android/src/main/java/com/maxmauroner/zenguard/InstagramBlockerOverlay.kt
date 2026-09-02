package com.maxmauroner.zenguard

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt
import kotlin.math.ceil

internal data class InstagramBlockerDebugInfo(
  val surface: InstagramSurface,
  val reason: InstagramBlockReason,
  val event: String,
  val eventPackage: String,
  val rootPackage: String,
  val source: String,
  val limits: String,
  val dmThreadVisible: Boolean,
  val dmThreadClicked: Boolean,
  val reelPagerVisible: Boolean,
  val reelPagerScrolled: Boolean,
  val dmProvenanceAgeMs: Long?,
  val dmThreadActive: Boolean,
  val homeElapsedMs: Long,
  val reelsWindowRemainingMs: Long?,
)

internal class InstagramBlockerOverlay(
  private val service: AccessibilityService,
  private val onLeave: () -> Unit,
  private val onContinue: () -> Boolean,
) {
  private val handler = Handler(Looper.getMainLooper())
  private val windowManager = service.getSystemService(WindowManager::class.java)
  private var root: View? = null
  private var continueAt: Long? = null
  private var continueButton: Button? = null
  private var countdownText: TextView? = null

  private val tick = object : Runnable {
    override fun run() {
      updateContinueButton()
      if (root != null && continueAt != null) handler.postDelayed(this, 500)
    }
  }

  fun show(
    action: InstagramGuardAction.ShowBlocker,
    homeMinutes: Int,
    reelsMinutes: Int,
    debugInfo: InstagramBlockerDebugInfo,
  ) {
    root?.let {
      if (it.isAttachedToWindow) return
      // A service can be interrupted while WindowManager is tearing down the view. Clear this
      // stale reference before creating the next blocker.
      hide()
    }
    continueAt = action.continueAvailableAt

    val container = LinearLayout(service).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(20), dp(22), dp(20), dp(20))
      setBackgroundColor(BACKGROUND)
    }

    val top = LinearLayout(service).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    val topLabel = styledText(topLabel(action), 10f, MUTED, bold = true).apply { letterSpacing = 0.12f }
    top.addView(topLabel, weightedWrap(weight = 1f))
    val topState = styledText(topState(action, homeMinutes), 10f, if (action.reason == InstagramBlockReason.HOME_LIMIT) MUTED else DANGER, bold = true).apply {
      letterSpacing = 0.08f
      gravity = Gravity.CENTER
      setPadding(dp(10), dp(6), dp(10), dp(6))
      background = roundedBackground(if (action.reason == InstagramBlockReason.HOME_LIMIT) PANEL2 else DANGER_BACKGROUND, if (action.reason == InstagramBlockReason.HOME_LIMIT) LINE else DANGER_LINE)
    }
    top.addView(topState, wrapContent())
    container.addView(top, matchWrap())

    val main = LinearLayout(service).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_VERTICAL
    }
    container.addView(main, weightedHeight())

    val icon = styledText("✓", 24f, ACCENT, bold = true).apply {
      gravity = Gravity.CENTER
      background = roundedBackground(Color.TRANSPARENT, ACCENT, dp(15).toFloat())
    }
    main.addView(icon, fixedSize(dp(47)))

    val eyebrow = styledText(eyebrow(action), 10f, if (action.reason == InstagramBlockReason.HOME_LIMIT) ACCENT else DANGER, bold = true).apply {
      letterSpacing = 0.12f
    }
    main.addView(eyebrow, matchWrap(topMargin = dp(18)))

    val title = styledTitle(title(action))
    main.addView(title, matchWrap(topMargin = dp(10)))

    val description = styledText(description(action), 16f, MUTED).apply {
      setLineSpacing(0f, 1.35f)
    }
    main.addView(description, matchWrap(topMargin = dp(10)))

    if (action.continueAvailableAt != null) {
      countdownText = styledText("", 42f, ACCENT, bold = true).apply {
        typeface = Typeface.MONOSPACE
        letterSpacing = -0.05f
      }
      main.addView(countdownText, matchWrap(topMargin = dp(17)))
    }

    val detail = LinearLayout(service).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(0, dp(11), 0, dp(11))
    }
    detail.addView(divider(), matchWrap())
    detail.addView(detailRow("surface", debugInfo.surface.name), matchWrap(topMargin = dp(7)))
    detail.addView(detailRow("rule", action.reason.name), matchWrap(topMargin = dp(7)))
    detail.addView(detailRow("guard", "ACTIVE", ACCENT), matchWrap(topMargin = dp(7)))
    detail.addView(divider(), matchWrap(topMargin = dp(7)))
    container.addView(detail, matchWrap(topMargin = dp(12)))

    val actions = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
    val leave = actionButton(leaveLabel(action), primary = action.continueAvailableAt == null).apply {
      setOnClickListener { onLeave() }
    }
    actions.addView(leave, matchWrap())

    if (action.continueAvailableAt != null) {
      continueButton = actionButton("", primary = true).apply {
        tag = reelsMinutes
        setOnClickListener {
          if (onContinue()) hide()
        }
      }
      actions.addView(continueButton, matchWrap(topMargin = dp(8)))
    }
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
      // A service may lose its accessibility window between detection and addView(). Keep the
      // policy state alive, but do not let a transient WindowManager failure crash the service.
      hide()
      return
    }
    updateContinueButton()
    handler.post(tick)
  }

  fun hide() {
    val existingRoot = root
    root = null
    continueButton = null
    countdownText = null
    continueAt = null
    handler.removeCallbacks(tick)
    existingRoot?.let {
      // removeView() throws when another lifecycle callback already removed the same view. The
      // state is cleared first, making repeated hide() calls safe even in that race.
      try {
        windowManager.removeView(it)
      } catch (_: IllegalArgumentException) {
        // The view was already detached.
      }
    }
  }

  private fun updateContinueButton() {
    val button = continueButton ?: return
    val availableAt = continueAt ?: return
    val remainingMs = availableAt - SystemClock.elapsedRealtime()
    button.isEnabled = remainingMs <= 0
    button.alpha = if (button.isEnabled) 1f else 0.55f
    countdownText?.text = if (remainingMs <= 0) "READY" else formatCountdown(remainingMs)
    button.text = if (remainingMs <= 0) {
      service.getString(R.string.zen_guard_continue_doomscrolling, button.tag as Int)
    } else {
      val seconds = ceil(remainingMs / 1_000.0).toInt()
      service.getString(R.string.zen_guard_continue_countdown, seconds)
    }
  }

  private fun styledText(text: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(service).apply {
    this.text = text
    setTextColor(color)
    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size)
    if (bold) typeface = Typeface.DEFAULT_BOLD
    includeFontPadding = false
  }

  private fun styledTitle(value: String) = styledText(SpannableString(value).apply {
    val accentStart = lastIndexOf('\n').takeIf { it >= 0 }?.plus(1) ?: 0
    setSpan(ForegroundColorSpan(ACCENT), accentStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
  }, 32f, COPY, bold = true).apply {
    setLineSpacing(0f, 0.98f)
  }

  private fun actionButton(label: String, primary: Boolean) = Button(service).apply {
    text = label
    setAllCaps(true)
    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
    setTextColor(if (primary) ON_ACCENT else COPY)
    typeface = Typeface.DEFAULT_BOLD
    minHeight = dp(46)
    minimumHeight = dp(46)
    stateListAnimator = null
    setPadding(dp(16), dp(9), dp(16), dp(9))
    background = roundedBackground(if (primary) ACCENT else PANEL2, if (primary) ACCENT else LINE, dp(14).toFloat())
  }

  private fun detailRow(label: String, value: String, valueColor: Int = MUTED) = LinearLayout(service).apply {
    orientation = LinearLayout.HORIZONTAL
    val left = styledText(label, 11f, MUTED)
    val right = styledText(value, 11f, valueColor, bold = true).apply {
      typeface = Typeface.MONOSPACE
      gravity = Gravity.END
    }
    addView(left, weightedWrap(weight = 1f))
    addView(right, wrapContent())
  }

  private fun divider() = View(service).apply { setBackgroundColor(LINE) }

  private fun roundedBackground(fill: Int, stroke: Int, radius: Float = dp(10).toFloat()) = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = radius
    setStroke(dp(1), stroke)
  }

  private fun formatCountdown(remainingMs: Long): String {
    val seconds = ceil(remainingMs / 1_000.0).toInt()
    return "%02d:%02d".format(seconds / 60, seconds % 60)
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

  private fun matchWrap(topMargin: Int = 0, bottomMargin: Int = 0) = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT,
    LinearLayout.LayoutParams.WRAP_CONTENT,
  ).apply {
    this.topMargin = topMargin
    this.bottomMargin = bottomMargin
  }

  private fun topLabel(action: InstagramGuardAction.ShowBlocker): String = when (action.reason) {
    InstagramBlockReason.HOME_LIMIT -> "INSTAGRAM / HOME"
    InstagramBlockReason.EXPLORE -> "INSTAGRAM / EXPLORE"
    else -> "INSTAGRAM / REELS"
  }

  private fun topState(action: InstagramGuardAction.ShowBlocker, homeMinutes: Int): String = when (action.reason) {
    InstagramBlockReason.HOME_LIMIT -> "${homeMinutes}m USED"
    InstagramBlockReason.REELS_SWIPE,
    InstagramBlockReason.REELS_WINDOW_EXPIRED,
    -> "WAIT"
    else -> "BLOCKED"
  }

  private fun eyebrow(action: InstagramGuardAction.ShowBlocker): String = when (action.reason) {
    InstagramBlockReason.HOME_LIMIT -> "HOME ALLOWANCE COMPLETE"
    InstagramBlockReason.EXPLORE -> "EXPLORE IS CLOSED"
    InstagramBlockReason.REELS_ENTRY -> "REELS ARE CLOSED"
    else -> "TAKE A SMALL PAUSE"
  }

  private fun title(action: InstagramGuardAction.ShowBlocker): String = when (action.reason) {
    InstagramBlockReason.HOME_LIMIT -> service.getString(R.string.zen_guard_instagram_home_limit)
    InstagramBlockReason.EXPLORE -> service.getString(R.string.zen_guard_instagram_explore_blocked)
    InstagramBlockReason.REELS_ENTRY -> service.getString(R.string.zen_guard_instagram_reels_entry_blocked)
    else -> service.getString(R.string.zen_guard_instagram_reels_blocked)
  }

  private fun description(action: InstagramGuardAction.ShowBlocker): String = when (action.reason) {
    InstagramBlockReason.HOME_LIMIT -> service.getString(R.string.zen_guard_instagram_home_detail)
    InstagramBlockReason.EXPLORE -> service.getString(R.string.zen_guard_instagram_explore_detail)
    InstagramBlockReason.REELS_ENTRY -> service.getString(R.string.zen_guard_instagram_reels_entry_detail)
    else -> service.getString(R.string.zen_guard_instagram_reels_detail)
  }

  private fun leaveLabel(action: InstagramGuardAction.ShowBlocker): String = when (action.reason) {
    InstagramBlockReason.HOME_LIMIT -> service.getString(R.string.zen_guard_return_home)
    InstagramBlockReason.EXPLORE -> service.getString(R.string.zen_guard_open_messages)
    InstagramBlockReason.REELS_ENTRY -> service.getString(R.string.zen_guard_leave_reels)
    else -> service.getString(R.string.zen_guard_return_to_dm)
  }

  companion object {
    private val BACKGROUND = Color.rgb(19, 28, 22)
    private val PANEL2 = Color.rgb(27, 39, 32)
    private val LINE = Color.rgb(43, 58, 48)
    private val COPY = Color.rgb(237, 245, 239)
    private val MUTED = Color.rgb(148, 168, 154)
    private val ACCENT = Color.rgb(167, 231, 130)
    private val ON_ACCENT = Color.rgb(11, 22, 9)
    private val DANGER = Color.rgb(255, 154, 121)
    private val DANGER_BACKGROUND = Color.rgb(33, 21, 17)
    private val DANGER_LINE = Color.rgb(93, 52, 40)
  }
}
