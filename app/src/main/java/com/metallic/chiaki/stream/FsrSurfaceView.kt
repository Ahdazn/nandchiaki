// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Drop-in replacement for the SurfaceView that shows the stream.
 *
 * In [VideoEnhanceMode.OFF] the decoder renders directly into this view's surface, exactly like a plain SurfaceView.
 * Otherwise the decoder renders into an intermediate surface and the video is drawn through FSR / sharpening shaders.
 * The surface the decoder should render into is reported via [videoSurfaceChangedCallback].
 */
class FsrSurfaceView @JvmOverloads constructor(
	context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback
{
	companion object
	{
		private const val TAG = "FsrSurfaceView"
	}

	/** Surface the video decoder should render into, or null if there is none (anymore) */
	var videoSurfaceChangedCallback: ((Surface?) -> Unit)? = null
		set(value)
		{
			field = value
			reconfigure()
		}

	/** Called (on the main thread) if the enhancement could not be started and the plain path is used instead */
	var onEnhanceError: ((String) -> Unit)? = null

	var enhanceMode = VideoEnhanceMode.OFF
		private set

	var strength = 0.8f
		private set

	private var videoWidth = 1280
	private var videoHeight = 720

	private var holderSurface: Surface? = null
	private var reportedSurface: Surface? = null
	private var renderer: FsrRenderer? = null

	init
	{
		holder.addCallback(this)
	}

	fun setVideoSize(width: Int, height: Int)
	{
		if(width > 0 && height > 0)
		{
			videoWidth = width
			videoHeight = height
		}
	}

	fun setEnhancement(mode: VideoEnhanceMode, strength: Float)
	{
		this.enhanceMode = mode
		this.strength = strength.coerceIn(0f, 1f)
		reconfigure()
	}

	override fun surfaceCreated(holder: SurfaceHolder) {}

	override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int)
	{
		holderSurface = holder.surface
		reconfigure()
	}

	override fun surfaceDestroyed(holder: SurfaceHolder)
	{
		holderSurface = null
		val r = renderer
		renderer = null
		r?.detachWindow()
		reportedSurface = null
		videoSurfaceChangedCallback?.invoke(null)
		r?.release()
	}

	private fun reconfigure()
	{
		val surface = holderSurface ?: return
		if(enhanceMode == VideoEnhanceMode.OFF)
		{
			usePlainSurface()
			return
		}
		val r = renderer
		if(r == null)
			startRenderer(surface)
		else
		{
			r.mode = enhanceMode
			r.strength = strength
		}
	}

	private fun startRenderer(surface: Surface)
	{
		renderer = FsrRenderer(
			surface, videoWidth, videoHeight, enhanceMode, strength,
			onVideoSurface = { s ->
				post {
					if(renderer != null)
					{
						reportedSurface = s
						videoSurfaceChangedCallback?.invoke(s)
					}
				}
			},
			onError = { message ->
				post {
					Log.e(TAG, "enhancement failed: $message")
					enhanceMode = VideoEnhanceMode.OFF
					usePlainSurface()
					onEnhanceError?.invoke(message)
				}
			})
	}

	/** Lets the decoder render directly into this view's surface again */
	private fun usePlainSurface()
	{
		val r = renderer
		renderer = null
		// the renderer has to let go of the window before the decoder can render into it
		r?.detachWindow()
		val s = holderSurface
		if(s != null && reportedSurface !== s)
		{
			reportedSurface = s
			videoSurfaceChangedCallback?.invoke(s)
		}
		r?.release()
	}
}
