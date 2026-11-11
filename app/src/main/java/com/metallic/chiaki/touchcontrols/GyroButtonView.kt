// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * On-screen button that controls gyro aiming. It is only drawn and only reacts to touches
 * if the gyro mode needs a button ([GyroMode.HOLD] or [GyroMode.TOGGLE]).
 */
class GyroButtonView @JvmOverloads constructor(
	context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr)
{
	var mode: GyroMode = GyroMode.HOLD
		set(value)
		{
			field = value
			invalidate()
		}

	/** Whether gyro aiming is currently active (highlights the button) */
	var active: Boolean = false
		set(value)
		{
			field = value
			invalidate()
		}

	var pressedCallback: ((Boolean) -> Unit)? = null

	private val haptics = ButtonHaptics(context)
	private val density = resources.displayMetrics.density

	private var pointerId = -1

	private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

	private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = 2f * density
		color = Color.argb(200, 255, 255, 255)
	}

	private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.WHITE
		textAlign = Paint.Align.CENTER
		isFakeBoldText = true
	}

	private val usable: Boolean get() = mode == GyroMode.HOLD || mode == GyroMode.TOGGLE

	override fun onDraw(canvas: Canvas)
	{
		super.onDraw(canvas)
		if(!usable)
			return
		val cx = width / 2f
		val cy = height / 2f
		val radius = min(width, height) / 2f - strokePaint.strokeWidth
		fillPaint.color = if(active || pointerId >= 0) Color.argb(210, 255, 160, 0) else Color.argb(120, 20, 20, 20)
		canvas.drawCircle(cx, cy, radius, fillPaint)
		canvas.drawCircle(cx, cy, radius, strokePaint)
		textPaint.textSize = radius * 0.5f
		canvas.drawText("GYRO", cx, cy + textPaint.textSize * 0.35f, textPaint)
	}

	override fun onTouchEvent(event: MotionEvent): Boolean
	{
		if(!usable)
			return false
		when(event.actionMasked)
		{
			MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
			{
				if(pointerId < 0)
				{
					pointerId = event.getPointerId(event.actionIndex)
					haptics.trigger()
					invalidate()
					pressedCallback?.invoke(true)
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

	private fun release()
	{
		if(pointerId < 0)
			return
		pointerId = -1
		invalidate()
		pressedCallback?.invoke(false)
	}

	override fun onDetachedFromWindow()
	{
		release()
		super.onDetachedFromWindow()
	}
}
