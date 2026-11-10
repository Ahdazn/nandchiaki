// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.metallic.chiaki.touchcontrols.ControlsLayoutStore.Item
import com.metallic.chiaki.touchcontrols.ControlsLayoutStore.LookSettings
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Lets the user move, resize, fade and hide the on-screen controls.
 *
 * All controls must be direct children of [root]. Customization is applied purely via
 * translation/scale/alpha/visibility, so the original constraint layout stays untouched.
 */
class ControlsLayoutEditor(
	private val root: ConstraintLayout,
	private val controls: List<Control>,
	private val store: ControlsLayoutStore)
{
	/** @param view must be a direct child of root. Later entries are considered "on top" for hit testing. */
	/** @param freeResize if true, pinching changes width and height independently; otherwise the control is scaled uniformly */
	data class Control(val key: String, val label: String, val view: View, val freeResize: Boolean = false)

	companion object
	{
		private const val MIN_SCALE = 0.5f
		private const val MAX_SCALE = 2.0f
		private const val MIN_ALPHA = 0.2f
		private const val MAX_ALPHA = 1.0f
		private const val HIDDEN_PREVIEW_ALPHA = 0.3f

		// limits for pinch resizing
		private const val PINCH_MIN_UNIFORM = 0.4f
		private const val PINCH_MAX_UNIFORM = 3.0f
		private const val PINCH_MIN_FREE = 0.2f
		private const val PINCH_MAX_FREE = 10.0f
	}

	private val context: Context = root.context
	private val density = context.resources.displayMetrics.density

	var editing = false
		private set

	var onEditModeChanged: ((Boolean) -> Unit)? = null

	/** Called whenever the touch-look settings change (also when a profile is loaded) */
	var onLookSettingsChanged: ((LookSettings) -> Unit)? = null
	private var lookSettings = LookSettings()

	private val state = HashMap<String, Item>()
	private var selected: Control? = null

	private var overlay: EditOverlay? = null
	private var panel: LinearLayout? = null
	private var panelBias = 0.5f

	private var titleText: TextView? = null
	private var visibleSwitch: SwitchMaterial? = null
	private var sizeBar: SeekBar? = null
	private var sizeLabel: TextView? = null
	private var alphaBar: SeekBar? = null
	private var alphaLabel: TextView? = null
	private var updatingPanel = false

	private var lookRow: LinearLayout? = null
	private var lookSensBar: SeekBar? = null
	private var lookSensLabel: TextView? = null
	private var lookInvertSwitch: SwitchMaterial? = null

	private fun dp(value: Int): Int = (value * density).toInt()

	private fun item(control: Control): Item = state[control.key] ?: Item()

	private fun loadState()
	{
		state.clear()
		controls.forEach { state[it.key] = store.getItem(it.key) }
		lookSettings = store.getLookSettings()
	}

	private fun persist(control: Control)
	{
		store.setItem(control.key, item(control))
	}

	/** Loads the active profile from storage and applies it to all controls. */
	fun applyLayout()
	{
		loadState()
		applyAll()
	}

	private fun applyAll()
	{
		controls.forEach { applyItem(it) }
		onLookSettingsChanged?.invoke(lookSettings)
	}

	private fun applyItem(control: Control)
	{
		val i = item(control)
		val v = control.view
		v.translationX = i.x * density
		v.translationY = i.y * density
		v.scaleX = i.scale
		v.scaleY = i.scaleY
		if(editing)
		{
			// hidden controls stay visible (dimmed) while editing, so they can be re-enabled
			v.visibility = View.VISIBLE
			v.alpha = if(i.visible) i.alpha else HIDDEN_PREVIEW_ALPHA
		}
		else
		{
			// INVISIBLE instead of GONE, so constraints referencing the view keep working
			v.visibility = if(i.visible) View.VISIBLE else View.INVISIBLE
			v.alpha = i.alpha
		}
	}

	fun startEditing()
	{
		if(editing)
			return
		editing = true
		loadState()
		selected = controls.firstOrNull()
		applyAll()

		val newOverlay = EditOverlay(context)
		overlay = newOverlay
		root.addView(newOverlay, fullParams())

		val newPanel = buildPanel()
		panel = newPanel
		root.addView(newPanel, panelParams())

		refreshPanel()
		onEditModeChanged?.invoke(true)
	}

	fun stopEditing()
	{
		if(!editing)
			return
		controls.forEach { persist(it) }
		overlay?.let { root.removeView(it) }
		panel?.let { root.removeView(it) }
		overlay = null
		panel = null
		selected = null
		editing = false
		applyAll()
		onEditModeChanged?.invoke(false)
	}

	// region layout params

	private fun fullParams() = ConstraintLayout.LayoutParams(0, 0).apply {
		topToTop = ConstraintLayout.LayoutParams.PARENT_ID
		bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
		startToStart = ConstraintLayout.LayoutParams.PARENT_ID
		endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
	}

	private fun panelParams(): ConstraintLayout.LayoutParams
	{
		val available = if(root.width > 0) root.width else context.resources.displayMetrics.widthPixels
		val width = minOf(dp(440), available - dp(16))
		return ConstraintLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
			topToTop = ConstraintLayout.LayoutParams.PARENT_ID
			bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
			startToStart = ConstraintLayout.LayoutParams.PARENT_ID
			endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
			verticalBias = panelBias
			horizontalBias = 0.5f
		}
	}

	// endregion

	// region panel

	private fun label(text: String, widthDp: Int = ViewGroup.LayoutParams.WRAP_CONTENT) = TextView(context).apply {
		this.text = text
		setTextColor(Color.WHITE)
		textSize = 13f
		layoutParams = LinearLayout.LayoutParams(
			if(widthDp > 0) dp(widthDp) else widthDp,
			ViewGroup.LayoutParams.WRAP_CONTENT)
	}

	private fun panelButton(text: String, onClick: () -> Unit) = MaterialButton(context).apply {
		this.text = text
		isAllCaps = false
		textSize = 12f
		minWidth = 0
		minimumWidth = 0
		minHeight = 0
		minimumHeight = 0
		insetTop = 0
		insetBottom = 0
		setPadding(dp(8), dp(6), dp(8), dp(6))
		layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
			marginStart = dp(2)
			marginEnd = dp(2)
		}
		setOnClickListener { onClick() }
	}

	private fun buildPanel(): LinearLayout
	{
		val title = TextView(context).apply {
			setTextColor(Color.WHITE)
			textSize = 14f
			layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
		}
		titleText = title

		val visibleSwitch = SwitchMaterial(context).apply {
			text = "Tampil"
			setTextColor(Color.WHITE)
			setOnCheckedChangeListener { _, checked ->
				if(updatingPanel)
					return@setOnCheckedChangeListener
				updateSelected { it.copy(visible = checked) }
				selected?.let { persist(it) }
			}
		}
		this.visibleSwitch = visibleSwitch

		val topRow = LinearLayout(context).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			addView(title)
			addView(visibleSwitch)
		}

		val hintLine = label("Seret untuk memindah \u00B7 cubit dengan dua jari untuk mengubah ukuran").apply {
			textSize = 11f
			alpha = 0.7f
		}

		val sizeLabel = label("Ukuran", 150)
		this.sizeLabel = sizeLabel
		val sizeBar = SeekBar(context).apply {
			max = ((MAX_SCALE - MIN_SCALE) * 100f).roundToInt()
			layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
			setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener
			{
				override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean)
				{
					if(!fromUser || updatingPanel)
						return
					updateSelected {
						// keep the width/height ratio, only the overall size changes
						val s = MIN_SCALE + progress / 100f
						val ratio = if(it.scale > 0f) it.scaleY / it.scale else 1f
						it.copy(scale = s, scaleY = s * ratio)
					}
					refreshLabels()
				}
				override fun onStartTrackingTouch(seekBar: SeekBar) {}
				override fun onStopTrackingTouch(seekBar: SeekBar) { selected?.let { persist(it) } }
			})
		}
		this.sizeBar = sizeBar
		val sizeRow = LinearLayout(context).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			addView(sizeLabel)
			addView(sizeBar)
		}

		val alphaLabel = label("Transparansi", 110)
		this.alphaLabel = alphaLabel
		val alphaBar = SeekBar(context).apply {
			max = ((MAX_ALPHA - MIN_ALPHA) * 100f).roundToInt()
			layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
			setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener
			{
				override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean)
				{
					if(!fromUser || updatingPanel)
						return
					updateSelected { it.copy(alpha = MIN_ALPHA + progress / 100f) }
					refreshLabels()
				}
				override fun onStartTrackingTouch(seekBar: SeekBar) {}
				override fun onStopTrackingTouch(seekBar: SeekBar) { selected?.let { persist(it) } }
			})
		}
		this.alphaBar = alphaBar
		val alphaRow = LinearLayout(context).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			addView(alphaLabel)
			addView(alphaBar)
		}

		val lookFillButton = panelButton("Penuhi setengah layar kanan") { fillRightHalf() }.apply {
			layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
		}
		val lookHint = label("Usap area ini untuk memutar kamera (meniru stik kanan).").apply {
			textSize = 12f
			alpha = 0.75f
		}
		val lookSensLabel = label("Sensitivitas", 110)
		this.lookSensLabel = lookSensLabel
		val lookSensBar = SeekBar(context).apply {
			max = ((TouchLookView.MAX_SENSITIVITY - TouchLookView.MIN_SENSITIVITY) * 100f).roundToInt()
			layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
			setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener
			{
				override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean)
				{
					if(!fromUser || updatingPanel)
						return
					updateLook { it.copy(sensitivity = TouchLookView.MIN_SENSITIVITY + progress / 100f) }
				}
				override fun onStartTrackingTouch(seekBar: SeekBar) {}
				override fun onStopTrackingTouch(seekBar: SeekBar) {}
			})
		}
		this.lookSensBar = lookSensBar
		val lookSensRow = LinearLayout(context).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			addView(lookSensLabel)
			addView(lookSensBar)
		}
		val lookInvert = SwitchMaterial(context).apply {
			text = "Balik sumbu vertikal"
			setTextColor(Color.WHITE)
			setOnCheckedChangeListener { _, checked ->
				if(updatingPanel)
					return@setOnCheckedChangeListener
				updateLook { it.copy(invertY = checked) }
			}
		}
		this.lookInvertSwitch = lookInvert
		val newLookRow = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			visibility = View.GONE
			addView(lookHint)
			addView(lookSensRow)
			addView(lookInvert)
			addView(lookFillButton)
		}
		this.lookRow = newLookRow

		val buttonRow = LinearLayout(context).apply {
			orientation = LinearLayout.HORIZONTAL
			setPadding(0, dp(6), 0, 0)
			addView(panelButton("Reset kontrol") { resetSelected() })
			addView(panelButton("Reset semua") { confirmResetAll() })
			addView(panelButton("Profil") { showProfileDialog() })
			addView(panelButton("\u21C5") { movePanel() })
			addView(panelButton("Selesai") { stopEditing() })
		}

		return LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			setBackgroundColor(Color.argb(230, 32, 36, 40))
			setPadding(dp(12), dp(10), dp(12), dp(10))
			isClickable = true
			addView(topRow)
			addView(hintLine)
			addView(sizeRow)
			addView(alphaRow)
			addView(newLookRow)
			addView(buttonRow)
		}
	}

	private fun refreshPanel()
	{
		val c = selected
		updatingPanel = true
		if(c == null)
		{
			titleText?.text = "Ketuk sebuah kontrol untuk memilihnya"
			lookRow?.visibility = View.GONE
		}
		else
		{
			val i = item(c)
			titleText?.text = "${c.label}  \u00B7  profil: ${store.activeProfile}"
			visibleSwitch?.isChecked = i.visible
			sizeBar?.progress = ((i.scale - MIN_SCALE) * 100f).roundToInt()
			alphaBar?.progress = ((i.alpha - MIN_ALPHA) * 100f).roundToInt()
			lookRow?.visibility = if(c.key == "look") View.VISIBLE else View.GONE
			lookSensBar?.progress = ((lookSettings.sensitivity - TouchLookView.MIN_SENSITIVITY) * 100f).roundToInt()
			lookInvertSwitch?.isChecked = lookSettings.invertY
		}
		updatingPanel = false
		refreshLabels()
		overlay?.invalidate()
	}

	private fun refreshLabels()
	{
		val c = selected ?: return
		val i = item(c)
		sizeLabel?.text = if(c.freeResize && abs(i.scale - i.scaleY) > 0.01f)
			"Ukuran ${(i.scale * 100f).roundToInt()}% \u00D7 ${(i.scaleY * 100f).roundToInt()}%"
		else
			"Ukuran ${(i.scale * 100f).roundToInt()}%"
		alphaLabel?.text = "Transparansi ${(i.alpha * 100f).roundToInt()}%"
		lookSensLabel?.text = "Sensitivitas ${(lookSettings.sensitivity * 100f).roundToInt()}%"
	}

	private fun updateSelected(transform: (Item) -> Item)
	{
		val c = selected ?: return
		state[c.key] = transform(item(c))
		applyItem(c)
		overlay?.invalidate()
	}

	private fun clampScale(control: Control, value: Float): Float =
		if(control.freeResize) value.coerceIn(PINCH_MIN_FREE, PINCH_MAX_FREE) else value.coerceIn(PINCH_MIN_UNIFORM, PINCH_MAX_UNIFORM)

	/** Stretches the selected control so that it covers the whole right half of the screen. */
	private fun fillRightHalf()
	{
		val c = selected ?: return
		val v = c.view
		if(v.width <= 0 || v.height <= 0 || root.width <= 0 || root.height <= 0)
			return
		val baseCx = v.left + v.width / 2f
		val baseCy = v.top + v.height / 2f
		val targetCx = root.width * 0.75f
		val targetCy = root.height * 0.5f
		val sx = clampScale(c, (root.width / 2f) / v.width)
		val sy = clampScale(c, root.height.toFloat() / v.height)
		state[c.key] = item(c).copy(
			x = (targetCx - baseCx) / density,
			y = (targetCy - baseCy) / density,
			scale = sx,
			scaleY = sy)
		applyItem(c)
		persist(c)
		refreshPanel()
	}

	private fun updateLook(transform: (LookSettings) -> LookSettings)
	{
		lookSettings = transform(lookSettings)
		store.setLookSettings(lookSettings)
		onLookSettingsChanged?.invoke(lookSettings)
		refreshLabels()
	}

	private fun resetSelected()
	{
		val c = selected ?: return
		store.resetItem(c.key)
		state[c.key] = Item()
		if(c.key == "look")
			updateLook { LookSettings() }
		applyItem(c)
		refreshPanel()
	}

	private fun confirmResetAll()
	{
		AlertDialog.Builder(context)
			.setTitle("Reset semua kontrol?")
			.setMessage("Semua kontrol pada profil \"${store.activeProfile}\" kembali ke posisi, ukuran, dan transparansi awal.")
			.setPositiveButton("Reset") { _, _ ->
				store.resetAll()
				loadState()
				applyAll()
				refreshPanel()
			}
			.setNegativeButton("Batal", null)
			.show()
	}

	private fun movePanel()
	{
		panelBias = when(panelBias)
		{
			0.5f -> 1.0f
			1.0f -> 0.0f
			else -> 0.5f
		}
		panel?.layoutParams = panelParams()
	}

	// endregion

	// region profiles

	private fun showProfileDialog()
	{
		val names = store.profileNames()
		val active = store.activeProfile
		val items = (names.map { (if(it == active) "\u25CF " else "\u25CB ") + it } + listOf("+ Profil baru\u2026", "Hapus profil ini")).toTypedArray()
		AlertDialog.Builder(context)
			.setTitle("Profil layout")
			.setItems(items) { _, which ->
				when
				{
					which < names.size -> switchProfile(names[which])
					which == names.size -> promptNewProfile()
					else -> deleteProfile()
				}
			}
			.show()
	}

	private fun switchProfile(name: String)
	{
		store.activeProfile = name
		loadState()
		applyAll()
		refreshPanel()
	}

	private fun promptNewProfile()
	{
		val input = EditText(context).apply {
			hint = "Nama profil"
			setSingleLine()
		}
		AlertDialog.Builder(context)
			.setTitle("Profil baru")
			.setMessage("Dibuat sebagai salinan dari layout yang sedang aktif.")
			.setView(input)
			.setPositiveButton("Buat") { _, _ ->
				if(store.createProfile(input.text.toString()))
				{
					loadState()
					applyAll()
					refreshPanel()
				}
				else
					Toast.makeText(context, "Nama kosong atau sudah dipakai", Toast.LENGTH_SHORT).show()
			}
			.setNegativeButton("Batal", null)
			.show()
	}

	private fun deleteProfile()
	{
		if(store.deleteActiveProfile())
		{
			loadState()
			applyAll()
			refreshPanel()
		}
		else
			Toast.makeText(context, "Profil Default tidak bisa dihapus", Toast.LENGTH_SHORT).show()
	}

	// endregion

	/** Bounds of a direct child of root in root coordinates, including scale and translation. */
	private fun boundsOf(v: View): RectF
	{
		val r = RectF(0f, 0f, v.width.toFloat(), v.height.toFloat())
		v.matrix.mapRect(r)
		r.offset(v.x, v.y)
		return r
	}

	/**
	 * Full-screen layer shown on top of the controls while editing.
	 * It swallows all touches so no button is triggered, selects the touched control and drags it.
	 */
	private inner class EditOverlay(context: Context) : View(context)
	{
		private val dimPaint = Paint().apply { color = Color.argb(70, 0, 0, 0) }

		private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			style = Paint.Style.STROKE
			strokeWidth = maxOf(dp(1), 1).toFloat()
			color = Color.argb(160, 255, 255, 255)
			pathEffect = DashPathEffect(floatArrayOf(dp(6).toFloat(), dp(4).toFloat()), 0f)
		}

		private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			style = Paint.Style.STROKE
			strokeWidth = dp(3).toFloat()
			color = Color.rgb(255, 193, 7)
		}

		private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.WHITE
			textSize = dp(11).toFloat()
			setShadowLayer(3f, 0f, 0f, Color.BLACK)
		}

		private var dragging = false
		private var lastX = 0f
		private var lastY = 0f

		/** Pinch with two fingers: resizes the selected control. Free-resize controls change width/height independently. */
		private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener()
		{
			override fun onScaleBegin(detector: ScaleGestureDetector): Boolean
			{
				dragging = false
				val hit = controls.lastOrNull { boundsOf(it.view).contains(detector.focusX, detector.focusY) }
				if(hit != null)
				{
					selected = hit
					refreshPanel()
				}
				return selected != null
			}

			override fun onScale(detector: ScaleGestureDetector): Boolean
			{
				val c = selected ?: return false
				val i = item(c)
				var sx = i.scale
				var sy = i.scaleY
				if(c.freeResize)
				{
					// the span along each axis is only meaningful if the fingers are far enough apart on that axis
					val minSpan = dp(24).toFloat()
					if(detector.previousSpanX > minSpan && detector.currentSpanX > minSpan)
						sx *= detector.currentSpanX / detector.previousSpanX
					if(detector.previousSpanY > minSpan && detector.currentSpanY > minSpan)
						sy *= detector.currentSpanY / detector.previousSpanY
				}
				else
				{
					sx *= detector.scaleFactor
					sy *= detector.scaleFactor
				}
				state[c.key] = i.copy(scale = clampScale(c, sx), scaleY = clampScale(c, sy))
				applyItem(c)
				refreshPanel()
				return true
			}

			override fun onScaleEnd(detector: ScaleGestureDetector)
			{
				selected?.let { persist(it) }
			}
		}).apply { isQuickScaleEnabled = false }

		override fun onDraw(canvas: Canvas)
		{
			canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
			controls.forEach { c ->
				val r = boundsOf(c.view)
				canvas.drawRect(r, if(c === selected) selectedPaint else outlinePaint)
				val textY = if(r.top < dp(14)) r.top + dp(12) else r.top - dp(3)
				canvas.drawText(c.label, r.left + dp(2), textY, textPaint)
			}
		}

		override fun onTouchEvent(event: MotionEvent): Boolean
		{
			scaleDetector.onTouchEvent(event)
			if(scaleDetector.isInProgress)
				return true

			when(event.actionMasked)
			{
				MotionEvent.ACTION_DOWN ->
				{
					val hit = controls.lastOrNull { boundsOf(it.view).contains(event.x, event.y) }
					if(hit != null)
					{
						selected = hit
						refreshPanel()
					}
					dragging = hit != null
					lastX = event.rawX
					lastY = event.rawY
				}
				MotionEvent.ACTION_MOVE ->
				{
					if(dragging)
					{
						val c = selected ?: return true
						val i = item(c)
						val dx = event.rawX - lastX
						val dy = event.rawY - lastY
						lastX = event.rawX
						lastY = event.rawY

						// keep the center of the control on screen
						val baseCx = c.view.left + c.view.width / 2f
						val baseCy = c.view.top + c.view.height / 2f
						val newCx = (baseCx + i.x * density + dx).coerceIn(0f, root.width.toFloat())
						val newCy = (baseCy + i.y * density + dy).coerceIn(0f, root.height.toFloat())
						state[c.key] = i.copy(x = (newCx - baseCx) / density, y = (newCy - baseCy) / density)
						applyItem(c)
						invalidate()
					}
				}
				MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
				{
					if(dragging)
						selected?.let { persist(it) }
					dragging = false
				}
			}
			return true
		}
	}
}
