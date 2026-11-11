// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import android.content.Context
import org.json.JSONException
import org.json.JSONObject

/**
 * Stores the user-customized layout of the on-screen controls.
 *
 * Layout is kept as JSON in its own SharedPreferences file, organized in named profiles.
 * Each control stores an offset (in dp, relative to its default position), a scale, an alpha and a visibility flag.
 */
class ControlsLayoutStore(context: Context)
{
	companion object
	{
		const val DEFAULT_PROFILE = "Default"
		private const val PREFS_NAME = "chiaki_touch_layouts"
		private const val KEY_DATA = "data"
		private const val KEY_ACTIVE = "active"
		private const val KEY_PROFILES = "profiles"
		private const val KEY_LOOK = "_look"
		private const val KEY_GYRO = "_gyro"
	}

	/**
	 * @param x horizontal offset in dp from the default position
	 * @param y vertical offset in dp from the default position
	 */
	data class Item(
		val x: Float = 0f,
		val y: Float = 0f,
		val scale: Float = 1f,
		/** vertical scale; by default the same as [scale] */
		val scaleY: Float = scale,
		val alpha: Float = 1f,
		val visible: Boolean = true)

	/**
	 * Settings of the touch-look area (swipe to turn the camera), stored per profile.
	 * @param sensitivity 0.2 .. 3.0, 1.0 is the default
	 */
	data class LookSettings(
		val sensitivity: Float = 1f,
		val invertY: Boolean = false)

	/**
	 * Settings of gyro aiming (turn the device to move the right stick), stored per profile.
	 * @param sensitivity 0.2 .. 3.0, 1.0 is the default
	 */
	data class GyroSettings(
		val mode: GyroMode = GyroMode.HOLD,
		val sensitivity: Float = 1f,
		val invertX: Boolean = false,
		val invertY: Boolean = false)

	private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

	private val data: JSONObject = try
	{
		JSONObject(prefs.getString(KEY_DATA, null) ?: "{}")
	}
	catch(e: JSONException)
	{
		JSONObject()
	}

	init
	{
		if(!data.has(KEY_PROFILES))
			data.put(KEY_PROFILES, JSONObject())
		if(!profiles().has(DEFAULT_PROFILE))
			profiles().put(DEFAULT_PROFILE, JSONObject())
	}

	private fun profiles(): JSONObject = data.getJSONObject(KEY_PROFILES)

	private fun profile(): JSONObject
	{
		val name = activeProfile
		return profiles().optJSONObject(name) ?: JSONObject().also { profiles().put(name, it) }
	}

	var activeProfile: String
		get()
		{
			val name = data.optString(KEY_ACTIVE, DEFAULT_PROFILE)
			return if(profiles().has(name)) name else DEFAULT_PROFILE
		}
		set(value)
		{
			if(profiles().has(value))
			{
				data.put(KEY_ACTIVE, value)
				save()
			}
		}

	fun profileNames(): List<String>
	{
		val others = profiles().keys().asSequence().filter { it != DEFAULT_PROFILE }.sorted().toList()
		return listOf(DEFAULT_PROFILE) + others
	}

	fun getItem(key: String): Item
	{
		val o = profile().optJSONObject(key) ?: return Item()
		return Item(
			x = o.optDouble("x", 0.0).toFloat(),
			y = o.optDouble("y", 0.0).toFloat(),
			scale = o.optDouble("s", 1.0).toFloat(),
			scaleY = o.optDouble("sy", o.optDouble("s", 1.0)).toFloat(),
			alpha = o.optDouble("a", 1.0).toFloat(),
			visible = o.optBoolean("v", true))
	}

	fun setItem(key: String, item: Item)
	{
		val o = JSONObject()
		o.put("x", item.x.toDouble())
		o.put("y", item.y.toDouble())
		o.put("s", item.scale.toDouble())
		o.put("sy", item.scaleY.toDouble())
		o.put("a", item.alpha.toDouble())
		o.put("v", item.visible)
		profile().put(key, o)
		save()
	}

	fun resetItem(key: String)
	{
		profile().remove(key)
		save()
	}

	fun resetAll()
	{
		profiles().put(activeProfile, JSONObject())
		save()
	}

	/**
	 * Creates a new profile as a copy of the currently active one and activates it.
	 * @return false if the name is empty or already taken
	 */
	fun createProfile(name: String): Boolean
	{
		val trimmed = name.trim()
		if(trimmed.isEmpty() || profiles().has(trimmed))
			return false
		profiles().put(trimmed, JSONObject(profile().toString()))
		data.put(KEY_ACTIVE, trimmed)
		save()
		return true
	}

	/**
	 * Deletes the active profile and switches back to the default one.
	 * @return false if the active profile is the default one
	 */
	fun deleteActiveProfile(): Boolean
	{
		val name = activeProfile
		if(name == DEFAULT_PROFILE)
			return false
		profiles().remove(name)
		data.put(KEY_ACTIVE, DEFAULT_PROFILE)
		save()
		return true
	}

	fun getLookSettings(): LookSettings
	{
		val o = profile().optJSONObject(KEY_LOOK) ?: return LookSettings()
		return LookSettings(
			sensitivity = o.optDouble("sens", 1.0).toFloat(),
			invertY = o.optBoolean("invY", false))
	}

	fun setLookSettings(settings: LookSettings)
	{
		val o = JSONObject()
		o.put("sens", settings.sensitivity.toDouble())
		o.put("invY", settings.invertY)
		profile().put(KEY_LOOK, o)
		save()
	}

	fun getGyroSettings(): GyroSettings
	{
		val o = profile().optJSONObject(KEY_GYRO) ?: return GyroSettings()
		val mode = try
		{
			GyroMode.valueOf(o.optString("mode", GyroMode.HOLD.name))
		}
		catch(e: IllegalArgumentException)
		{
			GyroMode.HOLD
		}
		return GyroSettings(
			mode = mode,
			sensitivity = o.optDouble("sens", 1.0).toFloat(),
			invertX = o.optBoolean("invX", false),
			invertY = o.optBoolean("invY", false))
	}

	fun setGyroSettings(settings: GyroSettings)
	{
		val o = JSONObject()
		o.put("mode", settings.mode.name)
		o.put("sens", settings.sensitivity.toDouble())
		o.put("invX", settings.invertX)
		o.put("invY", settings.invertY)
		profile().put(KEY_GYRO, o)
		save()
	}

	private fun save()
	{
		prefs.edit().putString(KEY_DATA, data.toString()).apply()
	}
}
