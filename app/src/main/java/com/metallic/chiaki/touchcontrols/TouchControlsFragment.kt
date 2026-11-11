// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.metallic.chiaki.databinding.FragmentControlsBinding
import com.metallic.chiaki.lib.ControllerState
import io.reactivex.Observable
import io.reactivex.rxkotlin.Observables.combineLatest
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.Subject

abstract class TouchControlsFragment : Fragment()
{
	protected var ownControllerState = ControllerState()
		set(value)
		{
			val diff = field != value
			field = value
			if(diff)
				ownControllerStateSubject.onNext(ownControllerState)
		}

	protected val ownControllerStateSubject: Subject<ControllerState>
			= BehaviorSubject.create<ControllerState>().also { it.onNext(ownControllerState) }

	// to delay attaching to the touchpadView until it's available
	protected val controllerStateProxy: Subject<Observable<ControllerState>>
			= BehaviorSubject.create<Observable<ControllerState>>().also { it.onNext(ownControllerStateSubject) }
	val controllerState: Observable<ControllerState> get() =
		controllerStateProxy.flatMap { it }

	var onScreenControlsEnabled: LiveData<Boolean>? = null

	/** Whether the user is currently customizing the layout of the on-screen controls. */
	open val isLayoutEditing: Boolean get() = false
	open fun startLayoutEdit() {}
	open fun stopLayoutEdit() {}
}

class DefaultTouchControlsFragment : TouchControlsFragment()
{
	private var _binding: FragmentControlsBinding? = null
	private val binding get() = _binding!!

	private var layoutEditor: ControlsLayoutEditor? = null
	private var gyroAim: GyroAim? = null

	// the right stick can be driven by three sources at once, their output is summed
	private var rightStickVec = Vector(0f, 0f)
	private var lookVec = Vector(0f, 0f)
	private var gyroVec = Vector(0f, 0f)

	private fun updateRightStick()
	{
		val x = (rightStickVec.x + lookVec.x + gyroVec.x).coerceIn(-1f, 1f)
		val y = (rightStickVec.y + lookVec.y + gyroVec.y).coerceIn(-1f, 1f)
		ownControllerState = ownControllerState.copy().apply {
			rightX = (Short.MAX_VALUE * x).toInt().toShort()
			rightY = (Short.MAX_VALUE * y).toInt().toShort()
		}
	}

	override val isLayoutEditing: Boolean get() = layoutEditor?.editing == true
	override fun startLayoutEdit() { layoutEditor?.startEditing() }
	override fun stopLayoutEdit() { layoutEditor?.stopEditing() }

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
		FragmentControlsBinding.inflate(inflater, container, false).let {
			_binding = it
			controllerStateProxy.onNext(
				combineLatest(ownControllerStateSubject, binding.touchpadView.controllerState) { a, b -> a or b }
			)
			it.root
		}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?)
	{
		super.onViewCreated(view, savedInstanceState)
		binding.dpadView.stateChangeCallback = this::dpadStateChanged
		binding.crossButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_CROSS)
		binding.moonButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_MOON)
		binding.pyramidButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_PYRAMID)
		binding.boxButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_BOX)
		binding.l1ButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_L1)
		binding.r1ButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_R1)
		binding.l3ButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_L3)
		binding.r3ButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_R3)
		binding.optionsButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_OPTIONS)
		binding.shareButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_SHARE)
		binding.psButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_PS)

		binding.l2ButtonView.buttonPressedCallback = { ownControllerState = ownControllerState.copy().apply { l2State = if(it) 255U else 0U } }
		binding.r2ButtonView.buttonPressedCallback = { ownControllerState = ownControllerState.copy().apply { r2State = if(it) 255U else 0U } }

		val quantizeStick = { f: Float ->
			(Short.MAX_VALUE * f).toInt().toShort()
		}

		binding.leftAnalogStickView.stateChangedCallback = { ownControllerState = ownControllerState.copy().apply {
			leftX = quantizeStick(it.x)
			leftY = quantizeStick(it.y)
		}}

		binding.rightAnalogStickView.stateChangedCallback = { rightStickVec = it; updateRightStick() }
		binding.touchLookView.stateChangedCallback = { lookVec = it; updateRightStick() }

		val gyro = GyroAim(requireContext())
		gyro.stateChangedCallback = { gyroVec = it; updateRightStick() }
		gyro.activeChangedCallback = { binding.gyroButtonView.active = it }
		binding.gyroButtonView.pressedCallback = { gyro.onButton(it) }
		gyroAim = gyro

		// listed in drawing order (bottom to top), which is also the priority for selecting overlapping controls
		layoutEditor = ControlsLayoutEditor(
			binding.root,
			listOf(
				ControlsLayoutEditor.Control("leftStick", "Stik kiri", binding.leftAnalogStickView),
				ControlsLayoutEditor.Control("rightStick", "Stik kanan", binding.rightAnalogStickView),
				ControlsLayoutEditor.Control("look", "Area kamera (look)", binding.touchLookView, freeResize = true),
				ControlsLayoutEditor.Control("touchpad", "Touchpad", binding.touchpadView, freeResize = true),
				ControlsLayoutEditor.Control("dpad", "D-Pad", binding.dpadView),
				ControlsLayoutEditor.Control("face", "Tombol aksi", binding.faceButtonsLayout),
				ControlsLayoutEditor.Control("l3", "L3", binding.l3ButtonView),
				ControlsLayoutEditor.Control("r3", "R3", binding.r3ButtonView),
				ControlsLayoutEditor.Control("ps", "PS", binding.psButtonView),
				ControlsLayoutEditor.Control("l2", "L2", binding.l2ButtonView),
				ControlsLayoutEditor.Control("l1", "L1", binding.l1ButtonView),
				ControlsLayoutEditor.Control("share", "Share", binding.shareButtonView),
				ControlsLayoutEditor.Control("r2", "R2", binding.r2ButtonView),
				ControlsLayoutEditor.Control("r1", "R1", binding.r1ButtonView),
				ControlsLayoutEditor.Control("options", "Options", binding.optionsButtonView),
				ControlsLayoutEditor.Control("gyro", "Tombol GYRO", binding.gyroButtonView)
			),
			ControlsLayoutStore(requireContext())
		).also {
			it.onLookSettingsChanged = { s ->
				binding.touchLookView.sensitivity = s.sensitivity
				binding.touchLookView.invertY = s.invertY
			}
			it.onGyroSettingsChanged = { s ->
				gyroAim?.settings = s
				binding.gyroButtonView.mode = s.mode
			}
			it.applyLayout()
		}

		onScreenControlsEnabled?.observe(viewLifecycleOwner, Observer {
			view.visibility = if(it) View.VISIBLE else View.GONE
		})
	}

	override fun onResume()
	{
		super.onResume()
		gyroAim?.start()
	}

	override fun onPause()
	{
		gyroAim?.stop()
		super.onPause()
	}

	override fun onDestroyView()
	{
		gyroAim?.stop()
		gyroAim = null
		layoutEditor?.stopEditing()
		layoutEditor = null
		super.onDestroyView()
	}

	private fun dpadStateChanged(direction: DPadView.Direction?)
	{
		ownControllerState = ownControllerState.copy().apply {
			buttons = ((buttons
						and ControllerState.BUTTON_DPAD_LEFT.inv()
						and ControllerState.BUTTON_DPAD_RIGHT.inv()
						and ControllerState.BUTTON_DPAD_UP.inv()
						and ControllerState.BUTTON_DPAD_DOWN.inv())
					or when(direction)
					{
						DPadView.Direction.UP -> ControllerState.BUTTON_DPAD_UP
						DPadView.Direction.DOWN -> ControllerState.BUTTON_DPAD_DOWN
						DPadView.Direction.LEFT -> ControllerState.BUTTON_DPAD_LEFT
						DPadView.Direction.RIGHT -> ControllerState.BUTTON_DPAD_RIGHT
						DPadView.Direction.LEFT_UP -> ControllerState.BUTTON_DPAD_LEFT or ControllerState.BUTTON_DPAD_UP
						DPadView.Direction.LEFT_DOWN -> ControllerState.BUTTON_DPAD_LEFT or ControllerState.BUTTON_DPAD_DOWN
						DPadView.Direction.RIGHT_UP -> ControllerState.BUTTON_DPAD_RIGHT or ControllerState.BUTTON_DPAD_UP
						DPadView.Direction.RIGHT_DOWN -> ControllerState.BUTTON_DPAD_RIGHT or ControllerState.BUTTON_DPAD_DOWN
						null -> 0U
					})
		}
	}

	private fun buttonStateChanged(buttonMask: UInt) = { pressed: Boolean ->
		ownControllerState = ownControllerState.copy().apply {
			buttons =
				if(pressed)
					buttons or buttonMask
				else
					buttons and buttonMask.inv()

		}
	}
}