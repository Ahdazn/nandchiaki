// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.children
import com.metallic.chiaki.R

class ButtonView @JvmOverloads constructor(
	context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr)
{
	private val haptics = ButtonHaptics(context)

	var buttonPressed = false
		private set(value)
		{
			val diff = field != value
			field = value
			if(diff)
			{
				if(value)
					haptics.trigger()
				invalidate()
				buttonPressedCallback?.let { it(field) }
			}
		}

	var buttonPressedCallback: ((Boolean) -> Unit)? = null

	private val drawableIdle: Drawable?
	private val drawablePressed: Drawable?

	init
	{
		context.theme.obtainStyledAttributes(attrs, R.styleable.ButtonView, 0, 0).apply {
			drawableIdle = getDrawable(R.styleable.ButtonView_drawableIdle)
			drawablePressed = getDrawable(R.styleable.ButtonView_drawablePressed)
			recycle()
		}

		isClickable = true
	}

	override fun onDraw(canvas: Canvas)
	{
		super.onDraw(canvas)
		val drawable = if(buttonPressed) drawablePressed else drawableIdle
		drawable?.setBounds(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
		drawable?.draw(canvas)
	}

	/**
	 * If this button overlaps with others in the same layout,
	 * let the one whose center is closest to the touch handle it.
	 *
	 * All math happens in the parent's coordinate space, so buttons that were moved or scaled
	 * by the layout editor are hit-tested correctly, and hidden buttons are ignored.
	 */
	private fun bestFittingTouchView(x: Float, y: Float): View
	{
		val p = toParentSpace(this, x, y)
		return (parent as? ViewGroup)?.children?.filter {
			it is ButtonView && it.visibility == View.VISIBLE && containsParentPoint(it, p[0], p[1])
		}?.sortedBy {
			val c = toParentSpace(it, it.width / 2f, it.height / 2f)
			(p[0] - c[0]) * (p[0] - c[0]) + (p[1] - c[1]) * (p[1] - c[1])
		}?.firstOrNull() ?: this
	}

	private fun toParentSpace(v: View, x: Float, y: Float): FloatArray
	{
		val pts = floatArrayOf(x, y)
		v.matrix.mapPoints(pts)
		pts[0] += v.x
		pts[1] += v.y
		return pts
	}

	private fun containsParentPoint(v: View, px: Float, py: Float): Boolean
	{
		val inverse = Matrix()
		if(!v.matrix.invert(inverse))
			return false
		val pts = floatArrayOf(px - v.x, py - v.y)
		inverse.mapPoints(pts)
		return pts[0] >= 0f && pts[0] < v.width && pts[1] >= 0f && pts[1] < v.height
	}

	override fun onTouchEvent(event: MotionEvent): Boolean
	{
		when(event.actionMasked)
		{
			MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
				if(bestFittingTouchView(event.getX(event.actionIndex), event.getY(event.actionIndex)) != this)
					return false
				buttonPressed = true
			}
			MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
				buttonPressed = false
			}
		}
		return true
	}
}