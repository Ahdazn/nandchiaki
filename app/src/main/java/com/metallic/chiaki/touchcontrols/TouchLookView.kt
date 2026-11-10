// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

/**
 * Touch-look area: swiping a finger inside this view emulates moving the right analog stick,
 * as used for camera control in shooters on mobile.
 *
 * The stick deflection follows the finger *speed* (not its distance from the touch-down point):
 * moving the finger fast tilts the stick far, holding the finger still brings the stick back to the center.
 */
class TouchLookView @JvmOverloads constructor(
	context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr)
{
	companion object
	{
		const val MIN_SENSITIVITY = 0.2f
		const val MAX_SENSITIVITY = 3.0f

		/** Finger speed that gives full stick deflection at sensitivity 1.0 */
		private const val FULL_SPEED_DP_PER_SECOND = 600f
		private const val DEADZONE = 0.02f
		private const val CURVE_EXPONENT = 1.2f
	}

	/** Emulated right stick, both axes in -1..1, positive y is down (same convention as AnalogStickView) */
	var stateChangedCallback: ((Vector) -> Unit)? = null

	var sensitivity: Float = 1.0f
		set(value)
		{
			field = value.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
		}

	var invertY: Boolean = false

	private val density = resources.displayMetrics.density

	private var pointerId = -1
	private var lastRawX = 0f
	private var lastRawY = 0f
	private var accumX = 0f
	private var accumY = 0f
	private var lastFrameNanos = 0L
	private var touchX = 0f
	private var touchY = 0f
	private var state = Vector(0f, 0f)

	private val areaRect = RectF()

	private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.FILL
		color = Color.argb(28, 255, 255, 255)
	}

	private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = 1.5f * density
		color = Color.argb(90, 255, 255, 255)
	}

	private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = 3f * density
		color = Color.argb(150, 255, 255, 255)
	}

	private val frameRunnable = object : Runnable
	{
		override fun run()
		{
			if(pointerId < 0)
				return
			val now = System.nanoTime()
			val dt = ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0.004f, 0.1f)
			lastFrameNanos = now

			// finger speed in dp per second, measured on screen (independent of the view's scale)
			val vx = accumX / density / dt
			val vy = accumY / density / dt
			accumX = 0f
			accumY = 0f

			val fullSpeed = FULL_SPEED_DP_PER_SECOND / sensitivity
			val x = shape(vx / fullSpeed)
			var y = shape(vy / fullSpeed)
			if(invertY)
				y = -y
			setState(Vector(x, y))
			postOnAnimation(this)
		}
	}

	private fun shape(n: Float): Float
	{
		val a = min(abs(n), 1f)
		if(a < DEADZONE)
			return 0f
		return sign(n) * a.pow(CURVE_EXPONENT)
	}

	private fun setState(newState: Vector)
	{
		if(newState != state)
		{
			state = newState
			stateChangedCallback?.invoke(newState)
		}
	}

	private fun release()
	{
		pointerId = -1
		accumX = 0f
		accumY = 0f
		removeCallbacks(frameRunnable)
		setState(Vector(0f, 0f))
		invalidate()
	}

	override fun onDraw(canvas: Canvas)
	{
		super.onDraw(canvas)
		val inset = strokePaint.strokeWidth
		areaRect.set(inset, inset, width - inset, height - inset)
		val corner = 16f * density
		canvas.drawRoundRect(areaRect, corner, corner, fillPaint)
		canvas.drawRoundRect(areaRect, corner, corner, strokePaint)
		if(pointerId >= 0)
			canvas.drawCircle(touchX, touchY, 28f * density, ringPaint)
	}

	override fun onTouchEvent(event: MotionEvent): Boolean
	{
		when(event.actionMasked)
		{
			MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
			{
				if(pointerId < 0)
				{
					val index = event.actionIndex
					pointerId = event.getPointerId(index)
					lastRawX = event.getRawX(index)
					lastRawY = event.getRawY(index)
					touchX = event.getX(index)
					touchY = event.getY(index)
					accumX = 0f
					accumY = 0f
					lastFrameNanos = System.nanoTime()
					postOnAnimation(frameRunnable)
					invalidate()
				}
			}

			MotionEvent.ACTION_MOVE ->
			{
				val index = event.findPointerIndex(pointerId)
				if(pointerId >= 0 && index >= 0)
				{
					val rawX = event.getRawX(index)
					val rawY = event.getRawY(index)
					accumX += rawX - lastRawX
					accumY += rawY - lastRawY
					lastRawX = rawX
					lastRawY = rawY
					touchX = event.getX(index)
					touchY = event.getY(index)
					invalidate()
				}
			}

			MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
			{
				if(event.getPointerId(event.actionIndex) == pointerId)
					release()
			}

			MotionEvent.ACTION_CANCEL -> release()
		}
		return true
	}

	override fun onDetachedFromWindow()
	{
		removeCallbacks(frameRunnable)
		pointerId = -1
		super.onDetachedFromWindow()
	}
}
