package com.maxmauroner.zenguard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Real figures for the phase-two usage grid, collected by the service. */
internal data class InstagramBlockerStats(
  val homeUsedMinutes: Int,
  val homeAllowanceMinutes: Int,
  val stoppedToday: Int,
  val continuedToday: Int,
  val resetsInMs: Long,
)

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

/**
 * Still concentric mark for phase one. Deliberately static: no animator, no
 * invalidation, one draw and done. Replaces the breathing orb that repainted
 * every frame for the whole block.
 */
internal class ZenStillMarkView(context: Context) : View(context) {
  private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = dp(1.5f)
  }

  private val middlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = dp(1.5f)
  }

  private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
  }

  private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val size = dp(132f).toInt()
    setMeasuredDimension(size, size)
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val accent = context.getColor(R.color.zen_accent)
    val cx = width / 2f
    val cy = height / 2f

    outerPaint.color = accent
    outerPaint.alpha = 45
    canvas.drawCircle(cx, cy, dp(60f), outerPaint)

    middlePaint.color = accent
    middlePaint.alpha = 80
    canvas.drawCircle(cx, cy, dp(42f), middlePaint)

    innerPaint.color = accent
    innerPaint.alpha = 22
    canvas.drawCircle(cx, cy, dp(26f), innerPaint)

    dotPaint.color = accent
    dotPaint.alpha = 230
    canvas.drawCircle(cx, cy, dp(7f), dotPaint)
  }

  private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

/**
 * Compact circular progress indicator for the mindful pause countdown.
 * Only invalidated by the 300ms tick, never by a frame animator.
 */
internal class ZenProgressRingView(context: Context) : View(context) {
  private var progressRatio = 0f

  private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = dp(2.8f)
    color = context.getColor(R.color.zen_track)
  }

  private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = dp(2.8f)
    strokeCap = Paint.Cap.ROUND
    color = context.getColor(R.color.zen_accent)
  }

  fun setProgress(ratio: Float) {
    progressRatio = ratio.coerceIn(0f, 1f)
    invalidate()
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val size = dp(22f).toInt()
    setMeasuredDimension(size, size)
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val strokeHalf = dp(1.4f)
    val rect = RectF(strokeHalf, strokeHalf, width - strokeHalf, height - strokeHalf)
    canvas.drawOval(rect, trackPaint)
    canvas.drawArc(rect, -90f, 360f * progressRatio, false, progressPaint)
  }

  private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

internal class InstagramBlockerOverlay(
  private val service: AccessibilityService,
  private val onLeave: () -> Unit,
  private val onContinue: () -> Boolean,
) {
  private val handler = Handler(Looper.getMainLooper())
  private val windowManager = service.getSystemService(WindowManager::class.java)
  private val audioManager = service.getSystemService(AudioManager::class.java)
  private var audioFocusRequest: AudioFocusRequest? = null
  private val focusListener = AudioManager.OnAudioFocusChangeListener {
    // The blocker holds no playback of its own; focus is only a lever to
    // pause whoever is playing. Nothing to do on changes.
  }
  private var root: View? = null
  private var continueAt: Long? = null
  private var pauseDurationMs: Long = 15_000L
  private var reelsMinutes: Int = 5
  private var stats: InstagramBlockerStats? = null
  private var isPhaseTwo = false
  private var isUnlocked = false

  private var continueButton: Button? = null
  private var progressRingView: ZenProgressRingView? = null
  private var timerLabel: TextView? = null
  private var markLayer: View? = null
  private var statsLayer: View? = null
  private var eyebrowView: TextView? = null
  private var titleView: TextView? = null
  private var descriptionView: TextView? = null

  // Triple-tap gesture state for developer diagnostics
  private var debugTapCount = 0
  private var lastDebugTapAtMs = 0L

  private val enterPhaseTwoRunnable = Runnable { enterPhaseTwo() }

  private val tick = object : Runnable {
    override fun run() {
      updateCountdown()
      if (root != null && continueAt != null) handler.postDelayed(this, 300)
    }
  }

  fun show(
    action: InstagramGuardAction.ShowBlocker,
    homeMinutes: Int,
    reelsMinutes: Int,
    stats: InstagramBlockerStats,
    debugInfo: InstagramBlockerDebugInfo,
  ) {
    root?.let {
      if (it.isAttachedToWindow) return
      hide()
    }
    continueAt = action.continueAvailableAt
    this.reelsMinutes = reelsMinutes
    this.stats = stats
    val hasPause = action.continueAvailableAt != null
    action.continueAvailableAt?.let { target ->
      pauseDurationMs = (target - SystemClock.elapsedRealtime()).coerceAtLeast(1_000L)
    }
    // No-pause screens open directly in phase two. Pause screens start calm.
    isPhaseTwo = !hasPause
    isUnlocked = !hasPause

    val accent = service.getColor(R.color.zen_accent)
    val copy = service.getColor(R.color.zen_copy)
    val muted = service.getColor(R.color.zen_muted)
    val faint = service.getColor(R.color.zen_faint)
    val night = service.getColor(R.color.zen_night)
    val panel2 = service.getColor(R.color.zen_panel2)
    val line2 = service.getColor(R.color.zen_line2)
    val accentLine = service.getColor(R.color.zen_accent_line)

    // Root full-screen container. Plain solid background, no custom onDraw,
    // so nothing repaints after the first layout.
    val container = LinearLayout(service).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(night)
      setPadding(dp(24), dp(16), dp(24), dp(16))
      setOnApplyWindowInsetsListener { v, insets ->
        @Suppress("DEPRECATION")
        val topInset = insets.systemWindowInsetTop
        @Suppress("DEPRECATION")
        val bottomInset = insets.systemWindowInsetBottom
        v.setPadding(
          dp(24),
          dp(16) + topInset,
          dp(24),
          dp(16) + bottomInset,
        )
        insets
      }
    }

    // Top Header Bar
    val top = LinearLayout(service).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      isClickable = true
      isFocusable = false
      setOnClickListener {
        handleHeaderTap(debugInfo)
      }
    }

    // Left Tag: Green Dot + Label
    val tagContainer = LinearLayout(service).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    val dot = View(service).apply {
      background = roundedBackground(accent, accent, dp(3).toFloat())
    }
    tagContainer.addView(dot, fixedSize(dp(6)))
    val topLabel = styledText("  " + topLabel(action), 11f, muted, bold = true).apply {
      letterSpacing = 0.12f
    }
    tagContainer.addView(topLabel, wrapContent())
    top.addView(tagContainer, weightedWrap(weight = 1f))

    // Right State Pill Badge
    val isPauseReason = action.reason == InstagramBlockReason.REELS_SWIPE ||
      action.reason == InstagramBlockReason.REELS_WINDOW_EXPIRED
    val topState = styledText(topState(action, homeMinutes), 11f, if (isPauseReason) accent else muted, bold = true).apply {
      letterSpacing = 0.08f
      gravity = Gravity.CENTER
      setPadding(dp(12), dp(5), dp(12), dp(5))
      background = roundedBackground(panel2, if (isPauseReason) accentLine else line2, dp(20).toFloat())
    }
    top.addView(topState, wrapContent())
    container.addView(top, matchWrap())

    // Central body in a ScrollView so short screens and large fonts scroll
    // instead of overflowing. Normally everything fits and nothing moves.
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

    // Middle region: fixed 196dp box holding both layers. The crossfade swaps
    // alpha only, so rail, headline, countdown, and buttons never shift.
    val middleBox = FrameLayout(service)
    val middleParams = LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT,
      dp(196),
    ).apply {
      gravity = Gravity.CENTER_HORIZONTAL
      topMargin = dp(12)
    }
    centerBody.addView(middleBox, middleParams)

    val mark = ZenStillMarkView(service).apply {
      alpha = if (isPhaseTwo) 0f else 1f
    }
    markLayer = mark
    middleBox.addView(
      mark,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER,
      ),
    )

    val grid = buildStatsGrid(stats, copy, muted).apply {
      alpha = if (isPhaseTwo) 1f else 0f
    }
    statsLayer = grid
    middleBox.addView(
      grid,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
        Gravity.CENTER,
      ),
    )

    // Eyebrow swaps at the switch. Headline and description do not: they only
    // change at 0:00 when the state genuinely changes.
    val eyebrowInitial = if (isPhaseTwo) {
      service.getString(R.string.zen_guard_eyebrow_stats)
    } else {
      service.getString(R.string.zen_guard_eyebrow_pause)
    }
    val eyebrow = styledText(eyebrowInitial, 11f, accent, bold = true).apply {
      letterSpacing = 0.14f
      gravity = Gravity.CENTER_HORIZONTAL
    }
    eyebrowView = eyebrow
    centerBody.addView(eyebrow, matchWrap(topMargin = dp(12)))

    // Poetic Headline (with accent highlight on the second line)
    val title = styledTitle(title(action), copy, accent).apply {
      gravity = Gravity.CENTER_HORIZONTAL
    }
    titleView = title
    centerBody.addView(title, matchWrap(topMargin = dp(8)))

    // Balanced Mindful Description. MATCH_PARENT with center gravity instead
    // of a fixed 290dp box, so large fonts wrap instead of clipping.
    val description = styledText(description(action), 15f, muted).apply {
      setLineSpacing(0f, 1.4f)
      gravity = Gravity.CENTER_HORIZONTAL
      textAlignment = View.TEXT_ALIGNMENT_CENTER
    }
    descriptionView = description
    centerBody.addView(description, matchWrap(topMargin = dp(10)))

    // Mindful Countdown Pill (only shown when pause is active)
    if (hasPause) {
      val timerCard = LinearLayout(service).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(9), dp(20), dp(9))
        background = roundedBackground(panel2, line2, dp(26).toFloat())
      }
      val ring = ZenProgressRingView(service)
      progressRingView = ring
      timerCard.addView(ring, wrapContent())

      timerLabel = styledText("", 13f, copy, bold = true).apply {
        setPadding(dp(12), 0, 0, 0)
      }
      timerCard.addView(timerLabel, wrapContent())

      val timerWrapper = LinearLayout(service).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_HORIZONTAL
      }
      timerWrapper.addView(timerCard, wrapContent())
      centerBody.addView(timerWrapper, matchWrap(topMargin = dp(18)))
    }

    // Bottom Action Area. Leave keeps the accent; Continue stays the quiet
    // button even when it becomes real.
    val actions = LinearLayout(service).apply {
      orientation = LinearLayout.VERTICAL
    }

    val leave = actionButton(leaveLabel(action), primary = true).apply {
      setOnClickListener { onLeave() }
    }
    actions.addView(leave, matchWrap())

    // Mindful Continue Action (only shown if timer available)
    if (hasPause) {
      continueButton = actionButton("", primary = false).apply {
        tag = reelsMinutes
        setOnClickListener {
          if (onContinue()) hide()
        }
      }
      actions.addView(continueButton, matchWrap(topMargin = dp(10)))
    }

    // Soft footer reassurance
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
      return
    }

    if (hasPause) {
      // Halfway switch, one shot. Cancelled in hide() so an early exit
      // never flips a detached view.
      handler.postDelayed(enterPhaseTwoRunnable, pauseDurationMs / 2)
      updateCountdown()
      handler.post(tick)
    }

    if (isReelsReason(action.reason)) pauseBackgroundPlayback()
  }

  /**
   * Mid-pause switch: crossfade the middle region and swap the eyebrow behind
   * the same 280ms fade. One-shot animator, runs once and stops. Headline,
   * countdown, buttons, rail, and footer are untouched.
   */
  private fun enterPhaseTwo() {
    if (isPhaseTwo) return
    isPhaseTwo = true
    markLayer?.animate()?.alpha(0f)?.setDuration(280)?.start()
    statsLayer?.animate()?.alpha(1f)?.setDuration(280)?.start()
    eyebrowView?.text = service.getString(R.string.zen_guard_eyebrow_stats)
  }

  private fun isReelsReason(reason: InstagramBlockReason): Boolean = when (reason) {
    InstagramBlockReason.REELS_ENTRY,
    InstagramBlockReason.REELS_SWIPE,
    InstagramBlockReason.REELS_WINDOW_EXPIRED,
    -> true
    InstagramBlockReason.HOME_LIMIT,
    InstagramBlockReason.EXPLORE,
    -> false
  }

  /**
   * Freezes the reel behind the blocker. Transient audio focus makes
   * ExoPlayer-based players (like Instagram's) pause, and the media-key event
   * covers players keyed to a media session instead. Scoped to reel blocks
   * only, so a Home or Explore block never pauses the user's own music.
   */
  private fun pauseBackgroundPlayback() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build(),
        )
        .setOnAudioFocusChangeListener(focusListener)
        .build()
      audioFocusRequest = request
      audioManager.requestAudioFocus(request)
    } else {
      @Suppress("DEPRECATION")
      audioManager.requestAudioFocus(
        focusListener,
        AudioManager.STREAM_MUSIC,
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
      )
    }
    val now = SystemClock.uptimeMillis()
    audioManager.dispatchMediaKeyEvent(
      KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0),
    )
    audioManager.dispatchMediaKeyEvent(
      KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0),
    )
  }

  private fun resumeBackgroundPlayback() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
      audioFocusRequest = null
    } else {
      @Suppress("DEPRECATION")
      audioManager.abandonAudioFocus(focusListener)
    }
  }

  fun hide() {
    resumeBackgroundPlayback()
    val existingRoot = root
    root = null
    continueButton = null
    progressRingView = null
    timerLabel = null
    markLayer = null
    statsLayer = null
    eyebrowView = null
    titleView = null
    descriptionView = null
    stats = null
    continueAt = null
    isPhaseTwo = false
    isUnlocked = false
    handler.removeCallbacks(tick)
    handler.removeCallbacks(enterPhaseTwoRunnable)
    existingRoot?.let {
      try {
        windowManager.removeView(it)
      } catch (_: IllegalArgumentException) {
        // The view was already detached.
      }
    }
  }

  private fun updateCountdown() {
    val button = continueButton ?: return
    val availableAt = continueAt ?: return
    val remainingMs = availableAt - SystemClock.elapsedRealtime()
    val isReady = remainingMs <= 0

    val remainingSeconds = ceil(remainingMs.coerceAtLeast(0L) / 1_000.0).toInt()
    val ratio = if (pauseDurationMs > 0) {
      (1f - (remainingMs.toFloat() / pauseDurationMs.toFloat())).coerceIn(0f, 1f)
    } else 1f

    progressRingView?.setProgress(ratio)

    val panel = service.getColor(R.color.zen_panel)
    val panel2 = service.getColor(R.color.zen_panel2)
    val line = service.getColor(R.color.zen_line)
    val line2 = service.getColor(R.color.zen_line2)
    val copy = service.getColor(R.color.zen_copy)
    val faint = service.getColor(R.color.zen_faint)
    val accent = service.getColor(R.color.zen_accent)

    if (isReady) {
      timerLabel?.text = service.getString(R.string.zen_guard_pause_label) + " " +
        service.getString(R.string.zen_guard_pause_done)
      button.isEnabled = true
      button.text = service.getString(R.string.zen_guard_continue_doomscrolling, button.tag as Int)
      // Continue becomes real but stays the quiet button. Leave keeps accent.
      button.setTextColor(copy)
      button.background = roundedBackground(panel2, line2, dp(16).toFloat())
      if (!isUnlocked) {
        isUnlocked = true
        // The state genuinely changed: headline and description follow now,
        // not at the halfway switch.
        titleView?.let { title ->
          title.text = pauseOverTitle(accent)
        }
        descriptionView?.text = pauseOverDetail()
      }
    } else {
      timerLabel?.text = service.getString(R.string.zen_guard_pause_label) + " " + formatPauseClock(remainingMs)
      button.isEnabled = false
      button.text = service.getString(R.string.zen_guard_continue_in_seconds, remainingSeconds)
      button.setTextColor(faint)
      button.background = roundedBackground(panel, line, dp(16).toFloat())
    }
  }

  private fun pauseOverTitle(accent: Int): SpannableString {
    val normalized = service.getString(R.string.zen_guard_pause_over_title).replace("\\n", "\n")
    return SpannableString(normalized).apply {
      val accentStart = normalized.lastIndexOf('\n').takeIf { it >= 0 }?.plus(1) ?: 0
      setSpan(ForegroundColorSpan(accent), accentStart, normalized.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
  }

  private fun pauseOverDetail(): String {
    val stopped = stats?.stoppedToday ?: 0
    return if (stopped > 0) {
      service.getString(R.string.zen_guard_pause_over_detail_count, stopped)
    } else {
      service.getString(R.string.zen_guard_pause_over_detail)
    }
  }

  private fun formatPauseClock(remainingMs: Long): String {
    val seconds = ceil(remainingMs.coerceAtLeast(0L) / 1_000.0).toInt()
    return "%d:%02d".format(seconds / 60, seconds % 60)
  }

  private fun buildStatsGrid(
    stats: InstagramBlockerStats,
    copy: Int,
    muted: Int,
  ): LinearLayout {
    val grid = LinearLayout(service).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
    }
    val topRow = LinearLayout(service).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER
    }
    topRow.addView(
      statCell(
        value = "${stats.homeUsedMinutes} / ${stats.homeAllowanceMinutes}m",
        label = service.getString(R.string.zen_guard_stat_home_used),
        copy = copy,
        muted = muted,
      ),
      cellWeight(),
    )
    topRow.addView(
      statCell(
        value = formatResets(stats.resetsInMs),
        label = service.getString(R.string.zen_guard_stat_resets),
        copy = copy,
        muted = muted,
      ),
      cellWeight(),
    )
    grid.addView(topRow, matchWrap())

    val bottomRow = LinearLayout(service).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER
    }
    bottomRow.addView(
      statCell(
        value = stats.stoppedToday.toString(),
        label = service.getString(R.string.zen_guard_stat_stopped),
        copy = copy,
        muted = muted,
      ),
      cellWeight(),
    )
    bottomRow.addView(
      statCell(
        value = stats.continuedToday.toString(),
        label = service.getString(R.string.zen_guard_stat_continued),
        copy = copy,
        muted = muted,
      ),
      cellWeight(),
    )
    grid.addView(bottomRow, matchWrap(topMargin = dp(12)))
    return grid
  }

  private fun statCell(value: String, label: String, copy: Int, muted: Int): LinearLayout {
    val cell = LinearLayout(service).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
    }
    val valueView = styledText(value, 16f, copy, bold = true).apply {
      gravity = Gravity.CENTER_HORIZONTAL
      textAlignment = View.TEXT_ALIGNMENT_CENTER
    }
    cell.addView(valueView, matchWrap())
    val labelView = styledText(label, 11f, muted).apply {
      gravity = Gravity.CENTER_HORIZONTAL
      textAlignment = View.TEXT_ALIGNMENT_CENTER
      setPadding(0, dp(2), 0, 0)
    }
    cell.addView(labelView, matchWrap())
    return cell
  }

  private fun formatResets(resetsInMs: Long): String {
    val totalMinutes = (resetsInMs / 60_000L).coerceAtLeast(0L)
    val hours = (totalMinutes / 60).toInt()
    val minutes = (totalMinutes % 60).toInt()
    return if (hours > 0) {
      service.getString(R.string.zen_guard_stat_hours_minutes, hours, minutes)
    } else {
      service.getString(R.string.zen_guard_stat_minutes, minutes)
    }
  }

  private fun handleHeaderTap(debugInfo: InstagramBlockerDebugInfo) {
    val now = SystemClock.elapsedRealtime()
    if (now - lastDebugTapAtMs > 1_200L) {
      debugTapCount = 0
    }
    lastDebugTapAtMs = now
    debugTapCount++

    if (debugTapCount >= 3) {
      debugTapCount = 0
      val msg = "Zen Guard [${debugInfo.reason.name}] on ${debugInfo.surface.name}\n" +
        "source: ${debugInfo.source} | age: ${debugInfo.dmProvenanceAgeMs ?: -1}ms\n" +
        "limits: ${debugInfo.limits}"
      Toast.makeText(service, msg, Toast.LENGTH_LONG).show()
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
    val spannable = SpannableString(normalized).apply {
      val accentStart = normalized.lastIndexOf('\n').takeIf { it >= 0 }?.plus(1) ?: 0
      setSpan(ForegroundColorSpan(accent), accentStart, normalized.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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

  private fun cellWeight() = LinearLayout.LayoutParams(
    0,
    LinearLayout.LayoutParams.WRAP_CONTENT,
    1f,
  )

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
    InstagramBlockReason.HOME_LIMIT -> service.getString(R.string.zen_guard_surface_home)
    InstagramBlockReason.EXPLORE -> service.getString(R.string.zen_guard_surface_explore)
    else -> service.getString(R.string.zen_guard_surface_reels)
  }

  private fun topState(action: InstagramGuardAction.ShowBlocker, homeMinutes: Int): String = when (action.reason) {
    InstagramBlockReason.HOME_LIMIT -> service.getString(R.string.zen_guard_state_minutes_used, homeMinutes)
    InstagramBlockReason.REELS_SWIPE,
    InstagramBlockReason.REELS_WINDOW_EXPIRED,
    -> service.getString(R.string.zen_guard_state_pause)
    else -> service.getString(R.string.zen_guard_state_closed)
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
}
