// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.sign

enum class GyroMode(val label: String)
{
	OFF("Mati"),
	ALWAYS("Selalu aktif"),
	HOLD("Tahan tombol GYRO"),
	TOGGLE("Saklar tombol GYRO")
}

/**
 * Gyro aiming: turning the device moves the (emulated) right stick, e.g. to control the camera.
 *
 * Output is delivered through [stateChangedCallback] as a stick vector in -1..1 (positive y is down),
 * the same convention as the other on-screen stick views.
 */
class GyroAim(private val context: Context) : SensorEventListener
{
	companion object
	{
		const val MIN_SENSITIVITY = 0.2f
		const val MAX_SENSITIVITY = 3.0f

		/** Turn rate (rad/s) that gives full stick deflection at sensitivity 1.0 (about 115 degrees per second) */
		private const val FULL_RATE_RAD_PER_SECOND = 2.0f

		/** Turn rates below this are treated as sensor noise / hand tremor */
		private const val DEADZONE_RAD_PER_SECOND = 0.02f

		private const val SMOOTHING = 0.5f
		private const val SAMPLING_PERIOD_US = 8000
	}

	var settings = ControlsLayoutStore.GyroSettings()
		set(value)
		{
			if(field.mode != value.mode)
			{
				buttonDown = false
				toggled = false
			}
			field = value
			refreshActive()
		}

	var stateChangedCallback: ((Vector) -> Unit)? = null
	var activeChangedCallback: ((Boolean) -> Unit)? = null

	var active = false
		private set

	private var buttonDown = false
	private var toggled = false
	private var running = false
	private var smoothX = 0f
	private var smoothY = 0f
	private var state = Vector(0f, 0f)

	private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
	private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
	private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

	val isAvailable: Boolean get() = gyroscope != null

	fun start()
	{
		if(running)
			return
		val sensor = gyroscope ?: return
		sensorManager.registerListener(this, sensor, SAMPLING_PERIOD_US)
		running = true
	}

	fun stop()
	{
		if(running)
			sensorManager.unregisterListener(this)
		running = false
		buttonDown = false
		toggled = false
		refreshActive()
		resetOutput()
	}

	/** To be called when the GYRO button is pressed or released */
	fun onButton(pressed: Boolean)
	{
		when(settings.mode)
		{
			GyroMode.HOLD -> buttonDown = pressed
			GyroMode.TOGGLE -> if(pressed) toggled = !toggled
			else -> {}
		}
		refreshActive()
	}

	private fun refreshActive()
	{
		val newActive = when(settings.mode)
		{
			GyroMode.OFF -> false
			GyroMode.ALWAYS -> true
			GyroMode.HOLD -> buttonDown
			GyroMode.TOGGLE -> toggled
		}
		if(newActive != active)
		{
			active = newActive
			activeChangedCallback?.invoke(newActive)
			if(!newActive)
				resetOutput()
		}
	}

	private fun resetOutput()
	{
		smoothX = 0f
		smoothY = 0f
		setState(Vector(0f, 0f))
	}

	private fun setState(newState: Vector)
	{
		if(newState != state)
		{
			state = newState
			stateChangedCallback?.invoke(newState)
		}
	}

	@Suppress("DEPRECATION")
	private fun displayRotation(): Int = windowManager.defaultDisplay.rotation

	private fun deadzone(rate: Float): Float =
		if(abs(rate) < DEADZONE_RAD_PER_SECOND) 0f else rate - sign(rate) * DEADZONE_RAD_PER_SECOND

	override fun onSensorChanged(event: SensorEvent)
	{
		if(event.sensor.type != Sensor.TYPE_GYROSCOPE || !active)
			return

		// angular velocity around the device axes (rad/s): x to the right, y up (in the natural orientation)
		val wx = event.values[0]
		val wy = event.values[1]

		// the same, around the axes of the current screen: x to the right of the screen, y towards its top
		val (sx, sy) = when(displayRotation())
		{
			Surface.ROTATION_90 -> Pair(wy, -wx)
			Surface.ROTATION_180 -> Pair(-wx, -wy)
			Surface.ROTATION_270 -> Pair(-wy, wx)
			else -> Pair(wx, wy)
		}

		// The device acts as a window into the game: turning it to the left (positive rotation around the
		// vertical screen axis) turns the camera to the left, tilting its top away from you looks up.
		val fullRate = FULL_RATE_RAD_PER_SECOND / settings.sensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
		var targetX = (-deadzone(sy) / fullRate).coerceIn(-1f, 1f)
		var targetY = (-deadzone(sx) / fullRate).coerceIn(-1f, 1f)
		if(settings.invertX)
			targetX = -targetX
		if(settings.invertY)
			targetY = -targetY

		smoothX += SMOOTHING * (targetX - smoothX)
		smoothY += SMOOTHING * (targetY - smoothY)
		setState(Vector(
			if(abs(smoothX) < 0.002f) 0f else smoothX,
			if(abs(smoothY) < 0.002f) 0f else smoothY))
	}

	override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
}
