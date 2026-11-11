// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.pow

enum class VideoEnhanceMode(val label: String)
{
	OFF("Mati"),
	SHARPEN("Tajamkan saja (RCAS)"),
	FSR("FSR 1.0 (EASU + RCAS)")
}

/**
 * Renders the decoded video through an OpenGL ES 3.0 pipeline into [window]:
 *
 * The video decoder writes into [videoSurface] (backed by a SurfaceTexture), which is then drawn through
 * - FSR mode:     copy -> EASU (AMD FidelityFX Super Resolution 1.0 upscaler) -> RCAS (sharpening) -> screen
 * - SHARPEN mode: copy (bilinear upscale) -> RCAS -> screen
 *
 * All GL work happens on a private thread. EASU and RCAS are ports of the MIT-licensed
 * FidelityFX FSR 1.0 reference implementation, using texelFetch so that only OpenGL ES 3.0 is needed.
 */
internal class FsrRenderer(
	private val window: Surface,
	private val videoWidth: Int,
	private val videoHeight: Int,
	@Volatile var mode: VideoEnhanceMode,
	@Volatile var strength: Float,
	private val onVideoSurface: (Surface) -> Unit,
	private val onError: (String) -> Unit)
{
	companion object
	{
		private const val TAG = "FsrRenderer"
		private const val EGL_OPENGL_ES3_BIT = 0x0040
		private const val WINDOW_RETRIES = 40
		private const val WINDOW_RETRY_DELAY_MS = 50L

		private val VERTEX_SHADER = """
			#version 300 es
			uniform mat4 uSTMatrix;
			out vec2 vTex;
			void main() {
				vec2 p = vec2(((gl_VertexID & 1) == 0) ? -1.0 : 1.0, ((gl_VertexID & 2) == 0) ? -1.0 : 1.0);
				gl_Position = vec4(p, 0.0, 1.0);
				vTex = (uSTMatrix * vec4(p * 0.5 + 0.5, 0.0, 1.0)).xy;
			}
		""".trimIndent()

		private val COPY_FRAGMENT_SHADER = """
			#version 300 es
			#extension GL_OES_EGL_image_external_essl3 : require
			precision highp float;
			uniform samplerExternalOES uTex;
			in vec2 vTex;
			out vec4 outColor;
			void main() {
				outColor = vec4(texture(uTex, vTex).rgb, 1.0);
			}
		""".trimIndent()

		// FidelityFX FSR 1.0 EASU (edge-adaptive spatial upsampling), 12-tap kernel
		private val EASU_FRAGMENT_SHADER = """
			#version 300 es
			precision highp float;
			precision highp int;
			uniform sampler2D uTex;
			uniform vec2 uIn;
			uniform vec2 uOut;
			out vec4 outColor;

			vec3 F(ivec2 p) {
				return texelFetch(uTex, clamp(p, ivec2(0), ivec2(uIn) - 1), 0).rgb;
			}

			float lum(vec3 c) {
				return c.b * 0.5 + (c.r * 0.5 + c.g);
			}

			void tap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {
				vec2 v;
				v.x = (off.x * dir.x) + (off.y * dir.y);
				v.y = (off.x * (-dir.y)) + (off.y * dir.x);
				v *= len;
				float d2 = v.x * v.x + v.y * v.y;
				d2 = min(d2, clp);
				float wB = 2.0 / 5.0 * d2 - 1.0;
				float wA = lob * d2 - 1.0;
				wB *= wB;
				wA *= wA;
				wB = 25.0 / 16.0 * wB - (25.0 / 16.0 - 1.0);
				float w = wB * wA;
				aC += c * w;
				aW += w;
			}

			void setF(inout vec2 dir, inout float len, float w, float lA, float lB, float lC, float lD, float lE) {
				float lenX = max(abs(lD - lC), abs(lC - lB));
				float dirX = lD - lB;
				dir.x += dirX * w;
				lenX = clamp(abs(dirX) / max(lenX, 1e-6), 0.0, 1.0);
				lenX *= lenX;
				len += lenX * w;
				float lenY = max(abs(lE - lC), abs(lC - lA));
				float dirY = lE - lA;
				dir.y += dirY * w;
				lenY = clamp(abs(dirY) / max(lenY, 1e-6), 0.0, 1.0);
				lenY *= lenY;
				len += lenY * w;
			}

			void main() {
				vec2 ip = floor(gl_FragCoord.xy);
				vec2 sc = uIn / uOut;
				vec2 pp = ip * sc + (0.5 * sc - 0.5);
				vec2 fp = floor(pp);
				pp -= fp;
				ivec2 f0 = ivec2(fp);

				//    b c
				//  e f g h
				//  i j k l
				//    n o
				vec3 b = F(f0 + ivec2(0, -1));
				vec3 c = F(f0 + ivec2(1, -1));
				vec3 e = F(f0 + ivec2(-1, 0));
				vec3 f = F(f0);
				vec3 g = F(f0 + ivec2(1, 0));
				vec3 h = F(f0 + ivec2(2, 0));
				vec3 i = F(f0 + ivec2(-1, 1));
				vec3 j = F(f0 + ivec2(0, 1));
				vec3 k = F(f0 + ivec2(1, 1));
				vec3 l = F(f0 + ivec2(2, 1));
				vec3 n = F(f0 + ivec2(0, 2));
				vec3 o = F(f0 + ivec2(1, 2));

				float bL = lum(b);
				float cL = lum(c);
				float eL = lum(e);
				float fL = lum(f);
				float gL = lum(g);
				float hL = lum(h);
				float iL = lum(i);
				float jL = lum(j);
				float kL = lum(k);
				float lL = lum(l);
				float nL = lum(n);
				float oL = lum(o);

				vec2 dir = vec2(0.0);
				float len = 0.0;
				setF(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);
				setF(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);
				setF(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);
				setF(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);

				vec2 dir2 = dir * dir;
				float dirR = dir2.x + dir2.y;
				bool zro = dirR < (1.0 / 32768.0);
				dirR = inversesqrt(max(dirR, 1e-12));
				dirR = zro ? 1.0 : dirR;
				dir.x = zro ? 1.0 : dir.x;
				dir *= vec2(dirR);

				len = len * 0.5;
				len *= len;
				float stretch = (dir.x * dir.x + dir.y * dir.y) / max(abs(dir.x), abs(dir.y));
				vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
				float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
				float clp = 1.0 / lob;

				vec3 min4 = min(min(f, g), min(j, k));
				vec3 max4 = max(max(f, g), max(j, k));

				vec3 aC = vec3(0.0);
				float aW = 0.0;
				tap(aC, aW, vec2( 0.0, -1.0) - pp, dir, len2, lob, clp, b);
				tap(aC, aW, vec2( 1.0, -1.0) - pp, dir, len2, lob, clp, c);
				tap(aC, aW, vec2(-1.0,  1.0) - pp, dir, len2, lob, clp, i);
				tap(aC, aW, vec2( 0.0,  1.0) - pp, dir, len2, lob, clp, j);
				tap(aC, aW, vec2( 0.0,  0.0) - pp, dir, len2, lob, clp, f);
				tap(aC, aW, vec2(-1.0,  0.0) - pp, dir, len2, lob, clp, e);
				tap(aC, aW, vec2( 1.0,  1.0) - pp, dir, len2, lob, clp, k);
				tap(aC, aW, vec2( 2.0,  1.0) - pp, dir, len2, lob, clp, l);
				tap(aC, aW, vec2( 2.0,  0.0) - pp, dir, len2, lob, clp, h);
				tap(aC, aW, vec2( 1.0,  0.0) - pp, dir, len2, lob, clp, g);
				tap(aC, aW, vec2( 1.0,  2.0) - pp, dir, len2, lob, clp, o);
				tap(aC, aW, vec2( 0.0,  2.0) - pp, dir, len2, lob, clp, n);

				outColor = vec4(min(max4, max(min4, aC / aW)), 1.0);
			}
		""".trimIndent()

		// FidelityFX FSR 1.0 RCAS (robust contrast adaptive sharpening) with noise removal
		private val RCAS_FRAGMENT_SHADER = """
			#version 300 es
			precision highp float;
			precision highp int;
			uniform sampler2D uTex;
			uniform float uCon;
			out vec4 outColor;

			vec3 F(ivec2 p) {
				return texelFetch(uTex, clamp(p, ivec2(0), textureSize(uTex, 0) - 1), 0).rgb;
			}

			float lum(vec3 c) {
				return c.b * 0.5 + (c.r * 0.5 + c.g);
			}

			void main() {
				ivec2 ip = ivec2(gl_FragCoord.xy);
				//    b
				//  d e f
				//    h
				vec3 b = F(ip + ivec2(0, -1));
				vec3 d = F(ip + ivec2(-1, 0));
				vec3 e = F(ip);
				vec3 f = F(ip + ivec2(1, 0));
				vec3 h = F(ip + ivec2(0, 1));

				float bL = lum(b);
				float dL = lum(d);
				float eL = lum(e);
				float fL = lum(f);
				float hL = lum(h);

				float nz = 0.25 * bL + 0.25 * dL + 0.25 * fL + 0.25 * hL - eL;
				float rng = max(max(bL, dL), max(eL, max(fL, hL))) - min(min(bL, dL), min(eL, min(fL, hL)));
				nz = clamp(abs(nz) / max(rng, 1e-5), 0.0, 1.0);
				nz = -0.5 * nz + 1.0;

				vec3 mn4 = min(min(b, d), min(f, h));
				vec3 mx4 = max(max(b, d), max(f, h));

				vec3 hitMin = mn4 / max(4.0 * mx4, vec3(1e-5));
				vec3 hitMax = (1.0 - mx4) / min(4.0 * mn4 - 4.0, vec3(-1e-5));
				vec3 lobeRGB = max(-hitMin, hitMax);
				float lobe = max(-0.1875, min(max(lobeRGB.r, max(lobeRGB.g, lobeRGB.b)), 0.0)) * uCon;
				lobe *= nz;

				float rcpL = 1.0 / (4.0 * lobe + 1.0);
				vec3 c = ((b + d + h + f) * lobe + e) * rcpL;
				outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
			}
		""".trimIndent()
	}

	private val thread = HandlerThread("FsrRenderer").apply { start() }
	private val handler = Handler(thread.looper)

	private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
	private var context: EGLContext = EGL14.EGL_NO_CONTEXT
	private var config: EGLConfig? = null
	private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
	private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE

	private var oesTexture = 0
	private var surfaceTexture: SurfaceTexture? = null
	private var videoSurface: Surface? = null

	private var copyProgram = 0
	private var easuProgram = 0
	private var rcasProgram = 0

	private val fbos = IntArray(2)
	private val textures = IntArray(2)
	private val targetWidth = IntArray(2)
	private val targetHeight = IntArray(2)

	private val stMatrix = FloatArray(16)

	private var detached = false
	private var released = false

	init
	{
		handler.post { setup() }
	}

	private fun setup()
	{
		try
		{
			display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
			check(display != EGL14.EGL_NO_DISPLAY) { "tidak ada display EGL" }
			val version = IntArray(2)
			check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize gagal" }

			val attribs = intArrayOf(
				EGL14.EGL_RED_SIZE, 8,
				EGL14.EGL_GREEN_SIZE, 8,
				EGL14.EGL_BLUE_SIZE, 8,
				EGL14.EGL_ALPHA_SIZE, 8,
				EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
				EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
				EGL14.EGL_NONE)
			val configs = arrayOfNulls<EGLConfig>(1)
			val numConfigs = IntArray(1)
			check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) { "tidak ada konfigurasi EGL" }
			config = configs[0]

			context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
				intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
			check(context != EGL14.EGL_NO_CONTEXT) { "OpenGL ES 3.0 tidak tersedia" }

			pbuffer = EGL14.eglCreatePbufferSurface(display, config,
				intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
			check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "eglMakeCurrent gagal" }

			val tex = IntArray(1)
			GLES30.glGenTextures(1, tex, 0)
			oesTexture = tex[0]
			GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
			GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
			GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
			GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
			GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

			copyProgram = buildProgram(VERTEX_SHADER, COPY_FRAGMENT_SHADER)
			easuProgram = buildProgram(VERTEX_SHADER, EASU_FRAGMENT_SHADER)
			rcasProgram = buildProgram(VERTEX_SHADER, RCAS_FRAGMENT_SHADER)

			val st = SurfaceTexture(oesTexture)
			st.setDefaultBufferSize(videoWidth, videoHeight)
			st.setOnFrameAvailableListener(SurfaceTexture.OnFrameAvailableListener { drawFrame() }, handler)
			surfaceTexture = st
			val surface = Surface(st)
			videoSurface = surface

			// from now on the decoder renders into our surface instead of directly to the screen
			onVideoSurface(surface)
			attachWindow(WINDOW_RETRIES)
		}
		catch(e: Exception)
		{
			Log.e(TAG, "setup failed", e)
			onError(e.message ?: e.toString())
		}
	}

	/**
	 * The window can only be attached once the decoder has let go of it, which may take a moment
	 * after the decoder's output was moved to [videoSurface], so this retries a few times.
	 */
	private fun attachWindow(retriesLeft: Int)
	{
		if(released || detached)
			return
		val s = EGL14.eglCreateWindowSurface(display, config, window, intArrayOf(EGL14.EGL_NONE), 0)
		if(s == null || s == EGL14.EGL_NO_SURFACE)
		{
			if(retriesLeft > 0)
				handler.postDelayed({ attachWindow(retriesLeft - 1) }, WINDOW_RETRY_DELAY_MS)
			else
				onError("tidak bisa membuat permukaan EGL")
			return
		}
		windowSurface = s
		EGL14.eglMakeCurrent(display, s, s, context)
		EGL14.eglSwapInterval(display, 0)
	}

	private fun drawFrame()
	{
		val st = surfaceTexture ?: return
		if(released)
			return
		// always consume the frame, otherwise the decoder's buffer queue would stall
		try
		{
			st.updateTexImage()
		}
		catch(e: RuntimeException)
		{
			return
		}
		if(detached || windowSurface == EGL14.EGL_NO_SURFACE)
			return
		st.getTransformMatrix(stMatrix)

		val size = IntArray(1)
		EGL14.eglQuerySurface(display, windowSurface, EGL14.EGL_WIDTH, size, 0)
		val w = size[0]
		EGL14.eglQuerySurface(display, windowSurface, EGL14.EGL_HEIGHT, size, 0)
		val h = size[0]
		if(w <= 0 || h <= 0)
			return

		render(w, h)
		EGL14.eglSwapBuffers(display, windowSurface)
	}

	private fun render(w: Int, h: Int)
	{
		val m = mode
		val fsr = m == VideoEnhanceMode.FSR
		ensureTarget(0, if(fsr) videoWidth else w, if(fsr) videoHeight else h)
		if(fsr)
			ensureTarget(1, w, h)

		// pass 1: video -> texture (video size for FSR, otherwise already scaled to the screen size)
		GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbos[0])
		GLES30.glViewport(0, 0, targetWidth[0], targetHeight[0])
		GLES30.glUseProgram(copyProgram)
		GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
		GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
		GLES30.glUniform1i(GLES30.glGetUniformLocation(copyProgram, "uTex"), 0)
		GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(copyProgram, "uSTMatrix"), 1, false, stMatrix, 0)
		GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

		var finalTexture = textures[0]

		// pass 2 (FSR only): EASU upscale to the screen size
		if(fsr)
		{
			GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbos[1])
			GLES30.glViewport(0, 0, w, h)
			GLES30.glUseProgram(easuProgram)
			GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
			GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[0])
			GLES30.glUniform1i(GLES30.glGetUniformLocation(easuProgram, "uTex"), 0)
			GLES30.glUniform2f(GLES30.glGetUniformLocation(easuProgram, "uIn"), videoWidth.toFloat(), videoHeight.toFloat())
			GLES30.glUniform2f(GLES30.glGetUniformLocation(easuProgram, "uOut"), w.toFloat(), h.toFloat())
			GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
			finalTexture = textures[1]
		}

		// last pass: RCAS sharpening to the screen. strength 0..1 maps to 2..0 "stops" like in the FSR reference
		val stops = 2.0f * (1.0f - strength.coerceIn(0f, 1f))
		GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
		GLES30.glViewport(0, 0, w, h)
		GLES30.glUseProgram(rcasProgram)
		GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
		GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, finalTexture)
		GLES30.glUniform1i(GLES30.glGetUniformLocation(rcasProgram, "uTex"), 0)
		GLES30.glUniform1f(GLES30.glGetUniformLocation(rcasProgram, "uCon"), 2.0f.pow(-stops))
		GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
	}

	private fun ensureTarget(index: Int, w: Int, h: Int)
	{
		if(textures[index] != 0 && targetWidth[index] == w && targetHeight[index] == h)
			return
		val id = IntArray(1)
		if(textures[index] == 0)
		{
			GLES30.glGenTextures(1, id, 0)
			textures[index] = id[0]
			GLES30.glGenFramebuffers(1, id, 0)
			fbos[index] = id[0]
		}
		GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[index])
		GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
		GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
		GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
		GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
		GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
		GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbos[index])
		GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, textures[index], 0)
		targetWidth[index] = w
		targetHeight[index] = h
	}

	private fun buildProgram(vertexSource: String, fragmentSource: String): Int
	{
		val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
		val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
		val program = GLES30.glCreateProgram()
		GLES30.glAttachShader(program, vs)
		GLES30.glAttachShader(program, fs)
		GLES30.glLinkProgram(program)
		val status = IntArray(1)
		GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
		GLES30.glDeleteShader(vs)
		GLES30.glDeleteShader(fs)
		if(status[0] == 0)
		{
			val log = GLES30.glGetProgramInfoLog(program)
			GLES30.glDeleteProgram(program)
			throw IllegalStateException("link shader gagal: $log")
		}
		return program
	}

	private fun compileShader(type: Int, source: String): Int
	{
		val shader = GLES30.glCreateShader(type)
		GLES30.glShaderSource(shader, source)
		GLES30.glCompileShader(shader)
		val status = IntArray(1)
		GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
		if(status[0] == 0)
		{
			val log = GLES30.glGetShaderInfoLog(shader)
			GLES30.glDeleteShader(shader)
			throw IllegalStateException("kompilasi shader gagal: $log")
		}
		return shader
	}

	/** Stops drawing to the window and releases it, so that the decoder can render to it directly again. Blocks briefly. */
	fun detachWindow()
	{
		val latch = CountDownLatch(1)
		handler.post {
			detached = true
			if(windowSurface != EGL14.EGL_NO_SURFACE)
			{
				EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
				EGL14.eglDestroySurface(display, windowSurface)
				windowSurface = EGL14.EGL_NO_SURFACE
			}
			latch.countDown()
		}
		latch.await(1, TimeUnit.SECONDS)
	}

	/** Frees everything. Must not be used afterwards. */
	fun release()
	{
		handler.post {
			released = true
			try
			{
				cleanup()
			}
			catch(e: Exception)
			{
				Log.e(TAG, "cleanup failed", e)
			}
			thread.quitSafely()
		}
	}

	private fun cleanup()
	{
		surfaceTexture?.setOnFrameAvailableListener(null)
		if(display != EGL14.EGL_NO_DISPLAY && context != EGL14.EGL_NO_CONTEXT && pbuffer != EGL14.EGL_NO_SURFACE)
		{
			EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
			if(copyProgram != 0) GLES30.glDeleteProgram(copyProgram)
			if(easuProgram != 0) GLES30.glDeleteProgram(easuProgram)
			if(rcasProgram != 0) GLES30.glDeleteProgram(rcasProgram)
			GLES30.glDeleteTextures(2, textures, 0)
			GLES30.glDeleteFramebuffers(2, fbos, 0)
			if(oesTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(oesTexture), 0)
		}
		videoSurface?.release()
		surfaceTexture?.release()
		videoSurface = null
		surfaceTexture = null
		if(display != EGL14.EGL_NO_DISPLAY)
		{
			EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
			if(windowSurface != EGL14.EGL_NO_SURFACE)
				EGL14.eglDestroySurface(display, windowSurface)
			if(pbuffer != EGL14.EGL_NO_SURFACE)
				EGL14.eglDestroySurface(display, pbuffer)
			if(context != EGL14.EGL_NO_CONTEXT)
				EGL14.eglDestroyContext(display, context)
			EGL14.eglTerminate(display)
		}
		windowSurface = EGL14.EGL_NO_SURFACE
		pbuffer = EGL14.EGL_NO_SURFACE
		context = EGL14.EGL_NO_CONTEXT
		display = EGL14.EGL_NO_DISPLAY
	}
}
