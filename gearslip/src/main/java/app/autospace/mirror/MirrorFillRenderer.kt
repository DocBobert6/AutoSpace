package app.autospace.mirror

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.WindowManager
import app.autospace.GearslipLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Fill video for screen sharing: the platform's AUTO_MIRROR scaling only ever
 * produces Fit video (letterboxed), so Fill needs an intermediate render step.
 * This class owns it: the virtual display renders Fit frames into a
 * SurfaceTexture, and a small GLES pass samples the CENTRAL sub-rect of
 * each frame (see [MirrorGeometry.fillUv]) stretched to the full encoder
 * Surface — a true crop at one scale on both axes, not a stretch. (The
 * Phase 10 bug sampled the whole Fit content rect instead, which
 * distorts whenever the phone and frame aspects differ.) Plus the Fill
 * exit button in the top-right corner.
 *
 * Anything failing here (no EGL, no texture, no surface) makes [create] return
 * null, and the caller falls back to direct Fit mirroring rather than showing a
 * black screen. The exit button's tap target is [MirrorGeometry.exitRegion],
 * the same function the touch relay uses, so button and target cannot drift.
 */
class MirrorFillRenderer private constructor(
    private val output: Surface,
    private val frameW: Int,
    private val frameH: Int,
    context: Context,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val thread = HandlerThread("mirror-fill").apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var textureId: Int = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var videoProgram: Int = 0
    private var colorProgram: Int = 0
    private var released = false

    /**
     * The local display transform + chrome, set from GearslipActivity
     * (the only writer) and read on the render thread. Volatile, never
     * synchronized: a torn read here only ever shows one frame at the
     * previous zoom, never a corrupt frame. Fill vs Fit changes the
     * sampling itself (see renderFrame); zoom/pan never touch the
     * phone — they only move the sampling window / quad.
     */
    @Volatile var displayFill: Boolean = true
    @Volatile var displayZoom: Float = 1f
    @Volatile var displayPanX: Float = 0f
    @Volatile var displayPanY: Float = 0f
    @Volatile var chromeVisible: Boolean = false

    fun setTransform(fill: Boolean, zoom: Float, panX: Float, panY: Float, chrome: Boolean) {
        displayFill = fill
        displayZoom = zoom.coerceIn(1f, 3f)
        displayPanX = panX
        displayPanY = panY
        chromeVisible = chrome
    }

    /** The Surface the virtual display renders its Fit frames into. */
    val inputSurface: Surface

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("no EGL display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw IllegalStateException("EGL init failed")
        val config = chooseConfig(display)
        eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("EGL context failed")
        surface = EGL14.eglCreateWindowSurface(display, config, output, intArrayOf(EGL14.EGL_NONE), 0)
        if (surface == EGL14.EGL_NO_SURFACE) throw IllegalStateException("EGL window surface failed")
        if (!EGL14.eglMakeCurrent(display, surface, surface, eglContext)) throw IllegalStateException("EGL make current failed")
        GLES20.glViewport(0, 0, frameW, frameH)
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        surfaceTexture = SurfaceTexture(textureId).also { st ->
            st.setDefaultBufferSize(frameW, frameH)
            st.setOnFrameAvailableListener({ renderFrame() }, handler)
        }
        inputSurface = Surface(surfaceTexture)
        videoProgram = buildProgram(VIDEO_VERTEX, VIDEO_FRAGMENT)
        colorProgram = buildProgram(COLOR_VERTEX, COLOR_FRAGMENT)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        // Release from the creating thread: a context current on one thread cannot
        // be made current on the render thread, and every frame re-makes it current
        // there (renderFrame's eglMakeCurrent) before drawing.
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
    }

    private fun renderFrame() {
        if (released) return
        val st = surfaceTexture ?: return
        try {
            if (!EGL14.eglMakeCurrent(display, surface, surface, eglContext)) return
            st.updateTexImage()
            val m = FloatArray(16)
            st.getTransformMatrix(m)
            val bounds = windowManager?.currentWindowMetrics?.bounds
            val phoneW = bounds?.width()?.toFloat() ?: return
            val phoneH = bounds?.height()?.toFloat() ?: return
            if (phoneW <= 0f || phoneH <= 0f) return
            // Buffer UV (v from top) -> standard texture coords (t = 0 at the bottom),
            // then through the SurfaceTexture transform, which accounts for the
            // buffer's orientation.
            //
            // Fill: a fullscreen quad sampling the zoom/pan window of the
            // Fit content rect — the frame corners' phone points (via the
            // same mapDisplay the touch relay uses, so video and taps
            // can never disagree) converted back to buffer UV. At zoom
            // 1 / pan 0 this is exactly the old fillUv rect.
            //
            // Fit: the quad itself moves — it sits at displayRect (the
            // Fit content rect, zoomed about the frame centre, panned)
            // and samples the whole Fit content rect. The buffer's
            // letterbox bars are never drawn: they stay cleared black
            // outside the quad. (The old Fit path skipped this renderer
            // entirely; routing Fit through it is what makes zoom, pan,
            // and chrome possible in the new Fit default.)
            val frameWf = frameW.toFloat()
            val frameHf = frameH.toFloat()
            val positions: FloatArray
            val std: FloatArray
            if (displayFill) {
                positions = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
                val content = MirrorGeometry.contentUv(phoneW, phoneH, frameWf, frameHf)
                fun uvOf(px: Float, py: Float): Pair<Float, Float> {
                    val u = content[0] + (px / phoneW) * (content[2] - content[0])
                    val v = content[1] + (py / phoneH) * (content[3] - content[1])
                    return u to v
                }
                val corners = listOf(
                    MirrorGeometry.mapDisplay(0f, frameHf, phoneW, phoneH, frameWf, frameHf, true, displayZoom, displayPanX, displayPanY),
                    MirrorGeometry.mapDisplay(frameWf, frameHf, phoneW, phoneH, frameWf, frameHf, true, displayZoom, displayPanX, displayPanY),
                    MirrorGeometry.mapDisplay(0f, 0f, phoneW, phoneH, frameWf, frameHf, true, displayZoom, displayPanX, displayPanY),
                    MirrorGeometry.mapDisplay(frameWf, 0f, phoneW, phoneH, frameWf, frameHf, true, displayZoom, displayPanX, displayPanY),
                )
                val fallback = MirrorGeometry.fillUv(phoneW, phoneH, frameWf, frameHf)
                val uv = corners.mapIndexed { i, pt ->
                    if (pt == null) {
                        // A corner outside the phone (cannot happen for
                        // Fill at zoom >= 1, but never draw garbage):
                        // fall back to that corner of the unzoomed rect.
                        when (i) {
                            0 -> fallback[0] to fallback[3]
                            1 -> fallback[2] to fallback[3]
                            2 -> fallback[0] to fallback[1]
                            else -> fallback[2] to fallback[1]
                        }
                    } else uvOf(pt.x, pt.y)
                }
                std = floatArrayOf(
                    uv[0].first, 1f - uv[0].second,
                    uv[1].first, 1f - uv[1].second,
                    uv[2].first, 1f - uv[2].second,
                    uv[3].first, 1f - uv[3].second,
                )
            } else {
                val r = MirrorGeometry.displayRect(phoneW, phoneH, frameWf, frameHf, false, displayZoom, displayPanX, displayPanY)
                positions = floatArrayOf(
                    ndcX(r.left), ndcY(r.bottom),
                    ndcX(r.right), ndcY(r.bottom),
                    ndcX(r.left), ndcY(r.top),
                    ndcX(r.right), ndcY(r.top),
                )
                val uv = MirrorGeometry.contentUv(phoneW, phoneH, frameWf, frameHf)
                std = floatArrayOf(
                    uv[0], 1f - uv[3],
                    uv[2], 1f - uv[3],
                    uv[0], 1f - uv[1],
                    uv[2], 1f - uv[1],
                )
            }
            val tex = FloatArray(8)
            for (i in 0 until 4) {
                val sc = std[i * 2]; val t = std[i * 2 + 1]
                tex[i * 2] = m[0] * sc + m[4] * t + m[12]
                tex[i * 2 + 1] = m[1] * sc + m[5] * t + m[13]
            }
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawVideo(positions, tex)
            if (displayFill) drawExitButton()
            drawPill()
            if (chromeVisible) drawChrome()
            EGL14.eglSwapBuffers(display, surface)
        } catch (t: Throwable) {
            GearslipLog.e("mirror fill frame failed", t)
        }
    }

    private fun drawVideo(positions: FloatArray, tex: FloatArray) {
        GLES20.glUseProgram(videoProgram)
        val pos = GLES20.glGetAttribLocation(videoProgram, "aPosition")
        val tc = GLES20.glGetAttribLocation(videoProgram, "aTexCoord")
        val texLoc = GLES20.glGetUniformLocation(videoProgram, "sTexture")
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(texLoc, 0)
        vertexAttrib(pos, floatBuffer(*positions))
        vertexAttrib(tc, floatBuffer(*tex))
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisableVertexAttribArray(tc)
    }

    private fun drawExitButton() {
        val r = MirrorGeometry.exitRegion(frameW.toFloat(), frameH.toFloat())
        val inset = (r.right - r.left) * 0.12f
        val bg = floatArrayOf(
            ndcX(r.left + inset), ndcY(r.bottom - inset),
            ndcX(r.right - inset), ndcY(r.bottom - inset),
            ndcX(r.left + inset), ndcY(r.top + inset),
            ndcX(r.right - inset), ndcY(r.top + inset),
        )
        drawColorQuad(bg, 0f, 0f, 0f, 0.55f)
        // An X from two bars, computed in pixels so the shape stays square on any frame.
        val cx = (r.left + r.right) / 2f
        val cy = (r.top + r.bottom) / 2f
        val len = (r.right - r.left) * 0.30f
        val thick = (r.right - r.left) * 0.055f
        for (angle in floatArrayOf(0.7853982f, -0.7853982f)) {
            val dx = kotlin.math.cos(angle) * len
            val dy = kotlin.math.sin(angle) * len
            val px = -kotlin.math.sin(angle) * thick
            val py = kotlin.math.cos(angle) * thick
            val quad = floatArrayOf(
                ndcX(cx - dx - px), ndcY(cy - dy - py),
                ndcX(cx + dx - px), ndcY(cy + dy - py),
                ndcX(cx - dx + px), ndcY(cy - dy + py),
                ndcX(cx + dx + px), ndcY(cy + dy + py),
            )
            drawColorQuad(quad, 1f, 1f, 1f, 0.9f)
        }
    }

    private fun drawColorQuad(ndc: FloatArray, r: Float, g: Float, b: Float, a: Float) {
        GLES20.glUseProgram(colorProgram)
        val pos = GLES20.glGetAttribLocation(colorProgram, "aPosition")
        val col = GLES20.glGetUniformLocation(colorProgram, "uColor")
        GLES20.glUniform4f(col, r, g, b, a)
        vertexAttrib(pos, floatBuffer(*ndc))
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
    }

    /**
     * Mirror chrome, drawn in GL because no Compose layer exists over
     * the mirror video (see MirrorDisplayState). The pill is always
     * drawn (it is the only way to open the chrome: a tap on the
     * mirror relays to the phone, so tap-to-toggle would flash chrome
     * on every phone tap); the button row only while chrome is open.
     * Glyphs only — GL has no text — in the player's frost style
     * (dark translucent quads, white glyphs). Hit rects are the same
     * MirrorGeometry functions the activity hit-tests with, so the
     * drawn buttons and their tap targets cannot drift apart.
     */
    private fun drawPill() {
        val r = MirrorGeometry.pillRegion(frameW.toFloat(), frameH.toFloat())
        drawQuadPx(r, 0f, 0f, 0f, 0.55f)
        val cx = (r.left + r.right) / 2f
        val cy = (r.top + r.bottom) / 2f
        val dot = (r.bottom - r.top) * 0.09f
        val gap = (r.right - r.left) * 0.14f
        for (dx in floatArrayOf(-gap, 0f, gap)) {
            drawQuadPx(FrameRect(cx + dx - dot, cy - dot, cx + dx + dot, cy + dot), 1f, 1f, 1f, 0.9f)
        }
    }

    private fun drawChrome() {
        val buttons = MirrorGeometry.chromeButtons(frameW.toFloat(), frameH.toFloat())
        buttons.forEachIndexed { i, r ->
            drawQuadPx(r, 0f, 0f, 0f, 0.55f)
            val cx = (r.left + r.right) / 2f
            val cy = (r.top + r.bottom) / 2f
            val size = r.right - r.left
            when (i) {
                0 -> if (displayFill) {
                    // Fill is active: a filled square.
                    val inset = size * 0.28f
                    drawQuadPx(FrameRect(cx - inset, cy - inset, cx + inset, cy + inset), 1f, 1f, 1f, 0.9f)
                } else {
                    // Fit is active: a hollow square (outline bars).
                    val inset = size * 0.28f
                    val t = size * 0.055f
                    drawQuadPx(FrameRect(cx - inset, cy - inset, cx + inset, cy - inset + t), 1f, 1f, 1f, 0.9f)
                    drawQuadPx(FrameRect(cx - inset, cy + inset - t, cx + inset, cy + inset), 1f, 1f, 1f, 0.9f)
                    drawQuadPx(FrameRect(cx - inset, cy - inset, cx - inset + t, cy + inset), 1f, 1f, 1f, 0.9f)
                    drawQuadPx(FrameRect(cx + inset - t, cy - inset, cx + inset, cy + inset), 1f, 1f, 1f, 0.9f)
                }
                1 -> drawBar(cx, cy, size * 0.22f, size * 0.045f, 0f, 1f, 1f, 1f, 0.9f)
                2 -> {
                    drawBar(cx, cy, size * 0.22f, size * 0.045f, 0f, 1f, 1f, 1f, 0.9f)
                    drawBar(cx, cy, size * 0.22f, size * 0.045f, 1.5707963f, 1f, 1f, 1f, 0.9f)
                }
                3 -> {
                    drawBar(cx, cy, size * 0.20f, size * 0.04f, 0.7853982f, 1f, 1f, 1f, 0.9f)
                    drawBar(cx, cy, size * 0.20f, size * 0.04f, -0.7853982f, 1f, 1f, 1f, 0.9f)
                }
            }
        }
    }

    private fun drawQuadPx(r: FrameRect, red: Float, g: Float, b: Float, a: Float) {
        drawColorQuad(
            floatArrayOf(
                ndcX(r.left), ndcY(r.bottom),
                ndcX(r.right), ndcY(r.bottom),
                ndcX(r.left), ndcY(r.top),
                ndcX(r.right), ndcY(r.top),
            ),
            red, g, b, a,
        )
    }

    private fun drawBar(cx: Float, cy: Float, len: Float, thick: Float, angle: Float, r: Float, g: Float, b: Float, a: Float) {
        val dx = kotlin.math.cos(angle) * len
        val dy = kotlin.math.sin(angle) * len
        val px = -kotlin.math.sin(angle) * thick
        val py = kotlin.math.cos(angle) * thick
        drawColorQuad(
            floatArrayOf(
                ndcX(cx - dx - px), ndcY(cy - dy - py),
                ndcX(cx + dx - px), ndcY(cy + dy - py),
                ndcX(cx - dx + px), ndcY(cy - dy + py),
                ndcX(cx + dx + px), ndcY(cy + dy + py),
            ),
            r, g, b, a,
        )
    }

    private fun ndcX(px: Float): Float = 2f * px / frameW - 1f
    private fun ndcY(py: Float): Float = 1f - 2f * py / frameH

    fun release() {
        if (released) return
        released = true
        runCatching { handler.post {
            runCatching {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
                if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display)
            }
            surfaceTexture?.release()
            inputSurface.release()
        } }
        thread.quitSafely()
    }

    companion object {
        fun create(output: Surface, frameW: Int, frameH: Int, context: Context, fill: Boolean = true): MirrorFillRenderer? {
            if (frameW <= 0 || frameH <= 0) return null
            return runCatching { MirrorFillRenderer(output, frameW, frameH, context.applicationContext) }
                .onFailure { GearslipLog.e("mirror fill renderer failed to start (falling back to Fit)", it) }
                .getOrNull()?.also { it.displayFill = fill }
        }

        private fun chooseConfig(display: EGLDisplay): EGLConfig {
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 4 /* EGL_OPENGL_ES2_BIT */,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
                throw IllegalStateException("no EGL config")
            }
            return configs[0]!!
        }

        private fun buildProgram(vertex: String, fragment: String): Int {
            val v = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
            GLES20.glShaderSource(v, vertex)
            GLES20.glCompileShader(v)
            val f = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
            GLES20.glShaderSource(f, fragment)
            GLES20.glCompileShader(f)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, v)
            GLES20.glAttachShader(p, f)
            GLES20.glLinkProgram(p)
            return p
        }

        private fun vertexAttrib(location: Int, buffer: FloatBuffer) {
            GLES20.glEnableVertexAttribArray(location)
            GLES20.glVertexAttribPointer(location, 2, GLES20.GL_FLOAT, false, 0, buffer)
        }

        private fun floatBuffer(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                .apply { put(values); position(0) }

        private const val VIDEO_VERTEX = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """
        private const val VIDEO_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
        private const val COLOR_VERTEX = """
            attribute vec2 aPosition;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """
        private const val COLOR_FRAGMENT = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """
    }
}
