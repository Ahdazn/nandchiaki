// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import com.metallic.chiaki.session.StreamSession
import java.util.Locale

/**
 * Live performance overlay: shows decoded FPS, video bitrate, packet loss and device temperature / thermal state.
 * The numbers are measured once per second from counters in the native video decoder and packet statistics.
 *
 * @param info static first line, e.g. resolution and codec
 * @param extra extra text for the second line, evaluated on every update, e.g. the enhancement mode
 */
class StreamStatsOverlay(
	private val context: Context,
	private val textView: TextView,
	private val session: StreamSession,
	private val info: String,
	private val extra: () -> String)
{
	companion object
	{
		private const val INTERVAL_MS = 1000L
	}

	private val handler = Handler(Looper.getMainLooper())
	private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

	private var running = false
	private var lastStats: LongArray? = null
	private var lastTime = 0L

	val visible: Boolean get() = running

	private val poll = object : Runnable
	{
		override fun run()
		{
			if(!running)
				return
			update()
			handler.postDelayed(this, INTERVAL_MS)
		}
	}

	fun start()
	{
		if(running)
			return
		running = true
		lastStats = null
		textView.text = "Statistik…"
		textView.visibility = View.VISIBLE
		handler.post(poll)
	}

	fun stop()
	{
		running = false
		handler.removeCallbacks(poll)
		textView.visibility = View.GONE
	}

	/** @return whether the overlay is visible afterwards */
	fun toggle(): Boolean
	{
		if(running)
			stop()
		else
			start()
		return running
	}

	private fun thermalLabel(status: Int): String = when(status)
	{
		PowerManager.THERMAL_STATUS_NONE -> "normal"
		PowerManager.THERMAL_STATUS_LIGHT -> "ringan"
		PowerManager.THERMAL_STATUS_MODERATE -> "sedang"
		PowerManager.THERMAL_STATUS_SEVERE -> "berat"
		PowerManager.THERMAL_STATUS_CRITICAL -> "kritis"
		PowerManager.THERMAL_STATUS_EMERGENCY -> "darurat"
		PowerManager.THERMAL_STATUS_SHUTDOWN -> "mati"
		else -> "?"
	}

	private fun batteryTemperature(): String
	{
		val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
		val raw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
		return if(raw == Int.MIN_VALUE) "?" else String.format(Locale.US, "%.1f\u00B0C", raw / 10f)
	}

	private fun update()
	{
		val now = SystemClock.elapsedRealtime()
		val stats = session.getStats()
		val lines = ArrayList<String>()
		lines.add(info)
		val extraText = extra()
		if(extraText.isNotEmpty())
			lines.add(extraText)

		val previous = lastStats
		if(stats != null && stats.size >= 4 && previous != null && previous.size >= 4 && now > lastTime)
		{
			val dt = (now - lastTime) / 1000f
			val fps = (stats[0] - previous[0]) / dt
			val mbps = (stats[1] - previous[1]) * 8f / dt / 1_000_000f
			val dReceived = stats[2] - previous[2]
			val dLost = stats[3] - previous[3]
			val lossPercent = if(dReceived + dLost > 0) dLost * 100f / (dReceived + dLost) else 0f
			lines.add(String.format(Locale.US, "FPS     : %.1f", fps))
			lines.add(String.format(Locale.US, "Bitrate : %.1f Mbps", mbps))
			lines.add(String.format(Locale.US, "Paket hilang: %.1f%% (total %d)", lossPercent, stats[3]))
		}
		else
			lines.add(if(stats == null) "Menunggu koneksi…" else "Mengukur…")

		lines.add("Baterai : ${batteryTemperature()}  Thermal: ${thermalLabel(powerManager.currentThermalStatus)}")

		textView.text = lines.joinToString("\n")
		lastStats = stats
		lastTime = now
	}
}
