package com.airplaytv.receiver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * A thin, Apple-style playback timeline.
 *
 * The sender only reports progress a few times per second, so the bar would
 * visibly jump if we drew the raw value. Instead the drawn fraction eases
 * toward the target on every animation frame, which makes the motion look
 * continuous while still correcting itself the moment a new position arrives.
 */
class TimelineView(context: Context) : View(context) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        style = Paint.Style.FILL
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD9D9D9.toInt()
        style = Paint.Style.FILL
    }
    private val rect = RectF()

    private var targetFraction = 0f
    private var shownFraction = 0f
    private var animating = false

    private val animateStep = object : Runnable {
        override fun run() {
            val diff = targetFraction - shownFraction
            if (kotlin.math.abs(diff) < 0.0004f) {
                shownFraction = targetFraction
                animating = false
                invalidate()
                return
            }
            // 18% per frame: smooth, but converges in ~0.2 s.
            shownFraction += diff * 0.18f
            invalidate()
            postOnAnimation(this)
        }
    }

    fun setFraction(fraction: Float, immediate: Boolean = false) {
        targetFraction = fraction.coerceIn(0f, 1f)
        if (immediate) {
            shownFraction = targetFraction
            invalidate()
            return
        }
        if (!animating) {
            animating = true
            postOnAnimation(animateStep)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val radius = h / 2f
        rect.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)
        val filled = width * shownFraction
        if (filled > 0f) {
            rect.set(0f, 0f, filled.coerceAtLeast(h), h)
            canvas.drawRoundRect(rect, radius, radius, progressPaint)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(animateStep)
        animating = false
        super.onDetachedFromWindow()
    }
}
