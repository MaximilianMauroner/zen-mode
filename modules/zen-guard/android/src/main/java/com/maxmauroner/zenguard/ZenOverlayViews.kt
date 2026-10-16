package com.maxmauroner.zenguard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

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
