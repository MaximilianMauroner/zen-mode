package com.maxmauroner.zenguard

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** A Home break has one action: leave the feed and end the session. */
internal class XBreakOverlay(private val service: AccessibilityService, private val onLeave: () -> Unit) {
  private val manager = service.getSystemService(WindowManager::class.java)
  private var root: View? = null
  val isShowing: Boolean get() = root != null

  fun show(minutes: Int) {
    if (isShowing) return
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
      text = service.resources.getQuantityString(R.plurals.zen_guard_x_home_detail, minutes, minutes)
      textSize = 15f
      setPadding(0, dp(12), 0, dp(26))
      setTextColor(service.getColor(R.color.zen_muted))
    })
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
    manager.addView(body, WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT))
    root = body
  }

  fun hide() { root?.let(manager::removeView); root = null }
  private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
}
