// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.roundToInt

class VideoEnhancePrefs(context: Context)
{
	private val prefs = context.applicationContext.getSharedPreferences("chiaki_video_enhance", Context.MODE_PRIVATE)

	var mode: VideoEnhanceMode
		get() = try
		{
			VideoEnhanceMode.valueOf(prefs.getString("mode", VideoEnhanceMode.OFF.name) ?: VideoEnhanceMode.OFF.name)
		}
		catch(e: IllegalArgumentException)
		{
			VideoEnhanceMode.OFF
		}
		set(value) { prefs.edit().putString("mode", value.name).apply() }

	var strength: Float
		get() = prefs.getFloat("strength", 0.8f)
		set(value) { prefs.edit().putFloat("strength", value).apply() }
}

/** Dialog to choose the video enhancement mode and the sharpening strength. Changes apply immediately. */
fun showVideoEnhanceDialog(context: Context, view: FsrSurfaceView, prefs: VideoEnhancePrefs)
{
	val density = context.resources.displayMetrics.density
	fun dp(v: Int) = (v * density).toInt()

	val modes = VideoEnhanceMode.values()
	val group = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
	modes.forEachIndexed { index, mode ->
		group.addView(RadioButton(context).apply {
			id = 1000 + index
			text = mode.label
			isChecked = mode == prefs.mode
		})
	}

	val strengthLabel = TextView(context)
	fun updateLabel() { strengthLabel.text = "Kekuatan penajam: ${(prefs.strength * 100f).roundToInt()}%" }
	updateLabel()

	val strengthBar = SeekBar(context).apply {
		max = 100
		progress = (prefs.strength * 100f).roundToInt()
	}

	fun applyEnhancement()
	{
		view.setEnhancement(prefs.mode, prefs.strength)
	}

	group.setOnCheckedChangeListener { _, checkedId ->
		prefs.mode = modes[checkedId - 1000]
		applyEnhancement()
	}

	strengthBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener
	{
		override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean)
		{
			if(!fromUser)
				return
			prefs.strength = progress / 100f
			updateLabel()
			applyEnhancement()
		}
		override fun onStartTrackingTouch(seekBar: SeekBar) {}
		override fun onStopTrackingTouch(seekBar: SeekBar) {}
	})

	val hint = TextView(context).apply {
		text = "FSR paling berguna saat resolusi stream (mis. 720p) lebih kecil dari layar. " +
			"Penajam tidak memperbaiki kotak-kotak akibat kompresi; kurangi kekuatannya kalau gambar terlihat kasar."
		textSize = 12f
		alpha = 0.7f
	}

	val content = LinearLayout(context).apply {
		orientation = LinearLayout.VERTICAL
		setPadding(dp(24), dp(8), dp(24), 0)
		addView(group)
		addView(strengthLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
		addView(strengthBar)
		addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
	}

	MaterialAlertDialogBuilder(context)
		.setTitle("Peningkat video")
		.setView(content)
		.setPositiveButton("Tutup", null)
		.show()
}
