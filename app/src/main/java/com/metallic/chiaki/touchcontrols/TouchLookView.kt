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

/**
 * Touch-look area: swiping a finger inside this view emulates moving the right analog stick,
 * as used for camera control in shooters on mobile.
 *
 * The distance the finger travels is converted into an amount of stick deflection over time
 * (stick * seconds), which is then played back at up to full deflection. So a long or fast swipe really
 * pushes the stick all the way and keeps it there until the whole distance has been "played", instead of
 * just giving a short pulse. A higher sensitivity means more camera rotation for the same swipe distance.
 */
class TouchLookView @JvmOverloads constructor(
	context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr)
{
	companion object
	{
		const val MIN_SENSITIVITY = 0.2f
		const val MAX_SENSITIVITY = 6.0f

		/** At sensitivity 1.0 a finger speed of this many dp per second gives full stick deflection */
		private const val FULL_SPEED_DP_PER_SECOND = 600f

		/** Upper limit for the not yet played movement, in seconds of full deflection */
		private const val MAX_BACKLOG_SECONDS = 1.0f

		private const val EPSILON = 0.0005f
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

	/** finger movement (px) since the last frame */
	private var accumX = 0f
	private var accumY = 0f

	/** movement that still has to be played back, in stick * seconds */
	private var pendingX = 0f
	private var pendingY = 0f

	private var loopRunning = false
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
			val now = System.nanoTime()
			val dt = ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0.004f, 0.1f)
			lastFrameNanos = now

			// finger movement of this frame (dp) -> stick deflection over time (stick * seconds)
			val gain = sensitivity / FULL_SPEED_DP_PER_SECOND
			pendingX = (pendingX + accumX / density * gain).coerceIn(-MAX_BACKLOG_SECONDS, MAX_BACKLOG_SECONDS)
			pendingY = (pendingY + accumY / density * gain).coerceIn(-MAX_BACKLOG_SECONDS, MAX_BACKLOG_SECONDS)
			accumX = 0f
			accumY = 0f

			// play it back at up to full deflection; what does not fit into this frame is kept for the next ones
			val x = (pendingX / dt).coerceIn(-1f, 1f)
			val y = (pendingY / dt).coerceIn(-1f, 1f)
			pendingX -= x * dt
			pendingY -= y * dt
			setState(Vector(x, if(invertY) -y else y))

			if(pointerId < 0 && abs(pendingX) < EPSILON && abs(pendingY) < EPSILON)
			{
				// finger lifted and everything was played back
				pendingX = 0f
				pendingY = 0f
				loopRunning = false
				setState(Vector(0f, 0f))
				return
			}
			postOnAnimation(this)
		}
	}

	private fun ensureLoop()
	{
		if(loopRunning)
			return
		loopRunning = true
		lastFrameNanos = System.nanoTime()
		postOnAnimation(frameRunnable)
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
		// the loop keeps running until the movement that is still pending has been played back
		pointerId = -1
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
					ensureLoop()
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
		loopRunning = false
		pointerId = -1
		accumX = 0f
		accumY = 0f
		pendingX = 0f
		pendingY = 0f
		setState(Vector(0f, 0f))
		super.onDetachedFromWindow()
	}
}
