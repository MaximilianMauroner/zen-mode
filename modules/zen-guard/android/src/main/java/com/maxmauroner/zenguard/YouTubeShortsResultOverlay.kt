package com.maxmauroner.zenguard

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.LinearLayout
import android.widget.TextView

/** A brief, readable result over YouTube Home after a verified Shorts exit. */
internal class YouTubeShortsResultOverlay(private val service: AccessibilityService) {
  private val manager = service.getSystemService(WindowManager::class.java)
  private val handler = Handler(Looper.getMainLooper())
  private var root: View? = null
  private val hideRunnable = Runnable { hide() }

  fun show() {
    hide()
    val content = LinearLayout(service).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(20), dp(18), dp(20), dp(18))
      background = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(service.getColor(R.color.zen_panel))
        setStroke(dp(1), service.getColor(R.color.zen_accent))
      }
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
      accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
      contentDescription = service.getString(R.string.zen_guard_shorts_result_title) + ". " +
        service.getString(R.string.zen_guard_shorts_result_detail)
    }
    content.addView(TextView(service).apply {
      text = service.getString(R.string.zen_guard_shorts_result_title)
      textSize = 17f
      setTypeface(typeface, Typeface.BOLD)
      setTextColor(service.getColor(R.color.zen_copy))
    })
    content.addView(TextView(service).apply {
      text = service.getString(R.string.zen_guard_shorts_result_detail)
      textSize = 14f
      setPadding(0, dp(5), 0, 0)
      setTextColor(service.getColor(R.color.zen_muted))
    })
    val params = WindowManager.LayoutParams(
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
      PixelFormat.TRANSLUCENT,
    ).apply {
      gravity = Gravity.TOP
      y = dp(24)
    }
    manager.addView(content, params)
    root = content
    content.announceForAccessibility(content.contentDescription)
    val accessibility = service.getSystemService(AccessibilityManager::class.java)
    val timeoutMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      accessibility?.getRecommendedTimeoutMillis(10_000, AccessibilityManager.FLAG_CONTENT_TEXT) ?: 10_000
    } else 10_000
    handler.postDelayed(hideRunnable, timeoutMs.toLong())
  }

  fun hide() {
    handler.removeCallbacks(hideRunnable)
    val old = root
    root = null
    old?.let {
      try {
        manager.removeView(it)
      } catch (_: IllegalArgumentException) {
        // The system can detach accessibility windows during service teardown.
      }
    }
  }

  private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
}
