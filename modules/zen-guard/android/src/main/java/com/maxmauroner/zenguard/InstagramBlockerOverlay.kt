package com.maxmauroner.zenguard

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.ceil

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

  private val tick = object : Runnable {
    override fun run() {
      updateContinueButton()
      if (root != null && continueAt != null) handler.postDelayed(this, 500)
    }
  }

  fun show(action: InstagramGuardAction.ShowBlocker, reelsMinutes: Int) {
    if (root != null) return
    continueAt = action.continueAvailableAt

    val container = LinearLayout(service).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setPadding(56, 72, 56, 72)
      setBackgroundColor(Color.rgb(25, 35, 29))
    }
    val title = TextView(service).apply {
      text = when (action.reason) {
        InstagramBlockReason.HOME_LIMIT -> service.getString(R.string.zen_guard_instagram_home_limit)
        InstagramBlockReason.EXPLORE -> service.getString(R.string.zen_guard_instagram_explore_blocked)
        else -> service.getString(R.string.zen_guard_instagram_reels_blocked)
      }
      setTextColor(Color.WHITE)
      textSize = 30f
      gravity = Gravity.CENTER
    }
    val detail = TextView(service).apply {
      text = service.getString(R.string.zen_guard_instagram_block_detail)
      setTextColor(Color.rgb(217, 226, 218))
      textSize = 17f
      gravity = Gravity.CENTER
      setPadding(0, 24, 0, 40)
    }
    val leave = Button(service).apply {
      text = when (action.reason) {
        InstagramBlockReason.REELS_SWIPE,
        InstagramBlockReason.REELS_WINDOW_EXPIRED,
        -> service.getString(R.string.zen_guard_return_to_dm)
        InstagramBlockReason.REELS_ENTRY -> service.getString(R.string.zen_guard_leave_reels)
        else -> service.getString(R.string.zen_guard_leave_instagram)
      }
      setOnClickListener { onLeave() }
    }
    container.addView(title, matchWrap())
    container.addView(detail, matchWrap())
    container.addView(leave, matchWrap())

    if (action.continueAvailableAt != null) {
      continueButton = Button(service).apply {
        tag = reelsMinutes
        setOnClickListener {
          if (onContinue()) hide()
        }
      }
      container.addView(continueButton, matchWrap(topMargin = 20))
    }

    val params = WindowManager.LayoutParams(
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.TRANSLUCENT,
    )
    windowManager.addView(container, params)
    root = container
    updateContinueButton()
    handler.post(tick)
  }

  fun hide() {
    handler.removeCallbacks(tick)
    root?.let { windowManager.removeView(it) }
    root = null
    continueButton = null
    continueAt = null
  }

  private fun updateContinueButton() {
    val button = continueButton ?: return
    val availableAt = continueAt ?: return
    val remainingMs = availableAt - SystemClock.elapsedRealtime()
    button.isEnabled = remainingMs <= 0
    button.text = if (remainingMs <= 0) {
      service.getString(R.string.zen_guard_continue_doomscrolling, button.tag as Int)
    } else {
      val seconds = ceil(remainingMs / 1_000.0).toInt()
      service.getString(R.string.zen_guard_continue_countdown, seconds)
    }
  }

  private fun matchWrap(topMargin: Int = 0) = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT,
    LinearLayout.LayoutParams.WRAP_CONTENT,
  ).apply { this.topMargin = topMargin }
}
