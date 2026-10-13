package com.maxmauroner.zenguard

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** A Home break has one action: leave the feed and end the session. */
internal class XBreakOverlay(
  private val service: AccessibilityService,
  private val onLeave: () -> Unit,
  private val onExpired: () -> Unit,
) {
  private enum class Mode { BREAK, UNAVAILABLE }
  private val manager = service.getSystemService(WindowManager::class.java)
  private var root: View? = null
  private var mode: Mode? = null
  private var deadlineElapsedMs: Long? = null
  private var countdownView: TextView? = null
  private val handler = Handler(Looper.getMainLooper())
  val isShowing: Boolean get() = root?.isAttachedToWindow == true
  private val tick = object : Runnable {
    override fun run() {
      val deadline = deadlineElapsedMs ?: return
      val remaining = deadline - SystemClock.elapsedRealtime()
      if (remaining <= 0L) {
        hide()
        onExpired()
        return
      }
      updateCountdown(remaining)
      if (isShowing) handler.postDelayed(this, 1_000L)
    }
  }

  fun show(minutes: Int, blockedUntilElapsedMs: Long, onAttached: () -> Unit) {
    deadlineElapsedMs = blockedUntilElapsedMs
    showDetail(
      Mode.BREAK,
      service.resources.getQuantityString(R.plurals.zen_guard_x_home_detail, minutes, minutes),
      onAttached,
    )
  }

  /** Used when accounting cannot be verified; it intentionally contains no countdown. */
  fun showUnavailable() {
    showDetail(Mode.UNAVAILABLE, service.getString(R.string.zen_guard_x_home_unavailable_detail))
  }

  private fun showDetail(requestedMode: Mode, detail: CharSequence, onAttached: () -> Unit = {}) {
    if (root != null && mode == requestedMode) {
      deadlineElapsedMs?.let { updateCountdown(it - SystemClock.elapsedRealtime()) }
      return
    }
    val deadline = deadlineElapsedMs.takeIf { requestedMode == Mode.BREAK }
    hide()
    deadlineElapsedMs = deadline
    val body = LinearLayout(service).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(28), dp(48), dp(28), dp(48))
      setBackgroundColor(service.getColor(R.color.zen_night))
    }
    body.addView(TextView(service).apply {
      text = service.getString(R.string.zen_guard_x_home_title)
      textSize = 28f
      setTypeface(typeface, Typeface.BOLD)
      setTextColor(service.getColor(R.color.zen_copy))
    })
    body.addView(TextView(service).apply {
      text = detail
      textSize = 15f
      setPadding(0, dp(12), 0, dp(26))
      setTextColor(service.getColor(R.color.zen_muted))
    })
    if (requestedMode == Mode.BREAK) {
      countdownView = TextView(service).apply {
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(26))
        setTextColor(service.getColor(R.color.zen_copy))
      }.also { body.addView(it) }
      deadlineElapsedMs?.let { updateCountdown(it - SystemClock.elapsedRealtime()) }
    }
    body.addView(Button(service).apply {
      text = service.getString(R.string.zen_guard_leave_x)
      isAllCaps = false
      textSize = 16f
      minimumHeight = dp(54)
      setTextColor(service.getColor(R.color.zen_on_accent))
      background = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(service.getColor(R.color.zen_accent))
      }
      setOnClickListener { onLeave() }
    }, LinearLayout.LayoutParams(-1, -2))
    root = body
    mode = requestedMode
    body.doOnFirstAttach {
      if (root === body) {
        onAttached()
        if (requestedMode == Mode.BREAK) handler.post(tick)
      }
    }
    try {
      manager.addView(body, WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT))
    } catch (_: RuntimeException) {
      hide()
    }
  }

  fun hide() {
    val existingRoot = root
    root = null
    mode = null
    deadlineElapsedMs = null
    countdownView = null
    handler.removeCallbacks(tick)
    existingRoot?.let {
      try {
        manager.removeView(it)
      } catch (_: IllegalArgumentException) {
        // Android may already have detached an accessibility overlay during service teardown.
      }
    }
  }
  private fun updateCountdown(remainingMs: Long) {
    countdownView?.text = service.getString(R.string.zen_guard_x_available_again, formatAvailabilityCountdown(remainingMs))
  }

  private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
}
