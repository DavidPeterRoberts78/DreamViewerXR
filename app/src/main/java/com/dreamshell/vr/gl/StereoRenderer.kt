package com.dreamshell.vr.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.Surface
import com.dreamshell.vr.VrSettings
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan2
import kotlin.math.tan

/**
 * Renders a flat virtual screen in front of the viewer, once per eye, into offscreen
 * eye images, then pre-distorts each eye onto its half of the phone screen so the
 * headset lenses bend it back straight (see [LensModel]).
 *
 * The screen's pixels come from a SurfaceTexture fed by a WebView or a MediaPlayer.
 */
class StereoRenderer(
    settings: VrSettings,
    /** Physical pixels per metre of the phone display. */
    private val pxPerM: Float,
    /** Called on the GL thread when a new content Surface exists; hand it to the producer. */
    private val onContentSurface: (Surface, SurfaceTexture) -> Unit,
) : GLSurfaceView.Renderer {

    // ---- State shared with the UI thread -------------------------------------------------
    @Volatile var settings: VrSettings = settings
    /** Width / height of one eye's view of the content (half width for SBS video). */
    @Volatile var contentAspect = 16f / 9f
    @Volatile var sideBySide3d = false
    @Volatile var cursorU = 0.5f
    @Volatile var cursorV = 0.5f
    @Volatile var cursorVisible = false
    @Volatile var cursorPressed = false
    /** 0..1 shows a progress bar under the screen; negative hides it. */
    @Volatile var progress = -1f
    /** Calibration: grid wall and alignment line instead of the content screen. */
    @Volatile var calibration = false
    /** One line of status shown under the screen; empty hides it. */
    @Volatile var hudText = ""

    private val headLock = Any()
    private val headRaw = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val recenterRequested = AtomicBoolean(true)
    private val frameAvailable = AtomicBoolean(false)

    // ---- GL-thread state ------------------------------------------------------------------
    private var contentTex = 0
    private var hudTex = 0
    private var hudShown = ""
    private var hudAspect = 8f
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private val texMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private var progContent = 0
    private var progColor = 0
    private var progTex = 0
    private var progGrid = 0
    private var progWarp = 0
    private var eyes: Array<EyeTarget>? = null
    private var screenW = 0
    private var screenH = 0
    private var yawRad = 0f
    private var lens: LensModel? = null
    private var lensFor: VrSettings? = null

    private val quad = GlUtil.floatBuffer(
        -0.5f, -0.5f, 0f, 0f,
        0.5f, -0.5f, 1f, 0f,
        -0.5f, 0.5f, 0f, 1f,
        0.5f, 0.5f, 1f, 1f,
    )
    private val fullscreenQuad = GlUtil.floatBuffer(
        -1f, -1f, 0f, 0f,
        1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f,
    )

    private val proj = FloatArray(16)
    private val head = FloatArray(16)
    private val eyeView = FloatArray(16)
    private val tmp = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)

    /** Called from the sensor listener with an OpenGL-ready head view matrix. */
    fun setHeadView(m: FloatArray) = synchronized(headLock) { System.arraycopy(m, 0, headRaw, 0, 16) }

    fun recenter() = recenterRequested.set(true)

    // ---- Renderer -------------------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        progContent = GlUtil.program(VS_MODEL, FS_CONTENT)
        progColor = GlUtil.program(VS_MODEL, FS_COLOR)
        progTex = GlUtil.program(VS_MODEL, FS_TEX)
        progGrid = GlUtil.program(VS_MODEL, FS_GRID)
        progWarp = GlUtil.program(VS_WARP, FS_WARP)

        val t = IntArray(2)
        GLES20.glGenTextures(2, t, 0)
        contentTex = t[0]
        hudTex = t[1]
        hudShown = "\u0000" // force upload
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, contentTex)
        linearClamp(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hudTex)
        linearClamp(GLES20.GL_TEXTURE_2D)

        surface?.release()
        surfaceTexture?.release()
        val st = SurfaceTexture(contentTex)
        st.setDefaultBufferSize(CONTENT_W, CONTENT_H)
        st.setOnFrameAvailableListener { frameAvailable.set(true) }
        surfaceTexture = st
        val s = Surface(st)
        surface = s
        eyes = null
        onContentSurface(s, st)
    }

    private fun linearClamp(target: Int) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenW = width
        screenH = height
        lensFor = null
        eyes?.forEach { it.release() }
        // Eye images at the screen's own density over the half each eye covers.
        eyes = arrayOf(EyeTarget(width / 2, height), EyeTarget(width / 2, height))
    }

    /** Lens model for current settings; rebuilt when settings change (they can change live). */
    private fun lensModel(s: VrSettings): LensModel {
        val cached = lens
        if (cached != null && lensFor === s) return cached
        val effective = if (s.distortionEnabled) s
            // No correction: the eye image maps 1:1 onto its screen half.
            else s.copy(k1 = 0f, k2 = 0f, k3 = 0f, maxFovDeg = 89f)
        val m = LensModel(effective, screenW / pxPerM, screenH / pxPerM)
        lens = m
        lensFor = s
        return m
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        if (frameAvailable.getAndSet(false)) {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
        }
        val s = settings
        val lm = lensModel(s)
        updateHud()

        synchronized(headLock) { System.arraycopy(headRaw, 0, head, 0, 16) }
        if (recenterRequested.getAndSet(false)) {
            // Forward vector of the head in world space = -(row 2 of the view matrix).
            yawRad = atan2(-head[2], head[10])
        }
        Matrix.rotateM(head, 0, Math.toDegrees(-yawRad.toDouble()).toFloat(), 0f, 1f, 0f)

        val targets = eyes ?: return
        val eyeW = screenW / 2
        val warp = s.distortionEnabled
        for (eye in 0..1) {
            val t = lm.fovTan(eye)
            val near = 0.05f
            Matrix.frustumM(proj, 0, -t[0] * near, t[1] * near, -t[2] * near, t[3] * near, near, 100f)

            if (warp) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targets[eye].framebuffer)
                GLES20.glViewport(0, 0, targets[eye].width, targets[eye].height)
            } else {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(eye * eyeW, 0, eyeW, screenH)
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                GLES20.glScissor(eye * eyeW, 0, eyeW, screenH)
            }
            GLES20.glClearColor(0.02f, 0.02f, 0.03f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)

            // Eye sits at eyeOffset, so the world shifts the other way.
            Matrix.setIdentityM(tmp, 0)
            Matrix.translateM(tmp, 0, -lm.eyeOffsetM(eye), 0f, 0f)
            Matrix.multiplyMM(eyeView, 0, tmp, 0, head, 0)
            drawScene(eye, s)
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (warp) drawWarp(targets, lm, s, eyeW)
        if (calibration) drawAlignmentLine()
        GlUtil.checkError("frame")
    }

    // ---- Scene ----------------------------------------------------------------------------

    private fun drawScene(eye: Int, s: VrSettings) {
        val d = s.screenDistanceM
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        if (calibration) {
            // A wall of 5-degree squares wider than any lens can show.
            val half = d * tan(Math.toRadians(70.0)).toFloat()
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, 0f, 0f, -d)
            Matrix.scaleM(model, 0, 2 * half, 2 * half, 1f)
            setMvp()
            GLES20.glUseProgram(progGrid)
            uniformMvp(progGrid)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(progGrid, "uCells"), 28f)
            drawQuad(progGrid, quad)
            drawHud(d, -d * 0.28f, d * 0.9f)
            GLES20.glDisable(GLES20.GL_BLEND)
            return
        }

        val (w, h) = screenSize(s, contentAspect)

        // Content screen.
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 0f, 0f, -d)
        Matrix.scaleM(model, 0, w, h, 1f)
        setMvp()
        GLES20.glUseProgram(progContent)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, contentTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progContent, "uTex"), 0)
        uniformMvp(progContent)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progContent, "uTexMatrix"), 1, false, texMatrix, 0)
        val rect = when {
            !sideBySide3d -> floatArrayOf(0f, 0f, 1f, 1f)
            eye == 0 -> floatArrayOf(0f, 0f, 0.5f, 1f)
            else -> floatArrayOf(0.5f, 0f, 0.5f, 1f)
        }
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(progContent, "uRect"), 1, rect, 0)
        drawQuad(progContent, quad)

        // Progress bar under the screen.
        val p = progress
        if (p >= 0f) {
            val barY = -h / 2f - 0.06f
            colorQuad(0f, barY, -d, w, 0.025f, floatArrayOf(1f, 1f, 1f, 0.25f), false)
            val fw = w * p.coerceIn(0f, 1f)
            colorQuad(-w / 2f + fw / 2f, barY, -d + 0.001f, fw, 0.025f, floatArrayOf(0.35f, 0.65f, 1f, 0.95f), false)
        }

        drawHud(d, -h / 2f - 0.16f, w * 0.7f)
        drawCursor(d, w, h)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawCursor(d: Float, w: Float, h: Float) {
        if (!cursorVisible) return
        val cx = (cursorU - 0.5f) * w
        val cy = (0.5f - cursorV) * h
        val size = 0.045f * d / 2.5f
        colorQuad(cx, cy, -d + 0.01f, size * 1.7f, size * 1.7f, floatArrayOf(0f, 0f, 0f, 0.5f), true)
        val c = if (cursorPressed) floatArrayOf(0.35f, 0.65f, 1f, 1f) else floatArrayOf(1f, 1f, 1f, 1f)
        colorQuad(cx, cy, -d + 0.02f, size, size, c, true)
    }

    private fun drawHud(d: Float, y: Float, width: Float) {
        if (hudShown.isEmpty()) return
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 0f, y, -d + 0.005f)
        Matrix.scaleM(model, 0, width, width / hudAspect, 1f)
        setMvp()
        GLES20.glUseProgram(progTex)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hudTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progTex, "uTex"), 0)
        uniformMvp(progTex)
        drawQuad(progTex, quad)
    }

    /** Re-rasterise the status line when it changes. */
    private fun updateHud() {
        val text = hudText
        if (text == hudShown) return
        hudShown = text
        if (text.isEmpty()) return
        val bw = 1024
        val bh = 112
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 16, 18, 24) }
        c.drawRoundRect(RectF(0f, 0f, bw.toFloat(), bh.toFloat()), bh / 2f, bh / 2f, bg)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 52f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        var size = 52f
        while (p.measureText(text) > bw - 60 && size > 24f) { size -= 2f; p.textSize = size }
        val fm = p.fontMetrics
        // GL textures are bottom-up; draw the bitmap upside down so it reads correctly.
        c.save()
        c.scale(1f, -1f, bw / 2f, bh / 2f)
        c.drawText(text, bw / 2f, bh / 2f - (fm.ascent + fm.descent) / 2f, p)
        c.restore()
        hudAspect = bw.toFloat() / bh
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hudTex)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
    }

    private fun colorQuad(x: Float, y: Float, z: Float, w: Float, h: Float, rgba: FloatArray, circle: Boolean) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, y, z)
        Matrix.scaleM(model, 0, w, h, 1f)
        setMvp()
        GLES20.glUseProgram(progColor)
        uniformMvp(progColor)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(progColor, "uColor"), 1, rgba, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progColor, "uCircle"), if (circle) 1f else 0f)
        drawQuad(progColor, quad)
    }

    private fun uniformMvp(program: Int) =
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)

    private fun setMvp() {
        Matrix.multiplyMM(tmp, 0, eyeView, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0)
    }

    private fun drawQuad(program: Int, buf: java.nio.FloatBuffer) {
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aUv = GLES20.glGetAttribLocation(program, "aUv")
        buf.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, buf)
        GLES20.glEnableVertexAttribArray(aPos)
        if (aUv >= 0) {
            buf.position(2)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, buf)
            GLES20.glEnableVertexAttribArray(aUv)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        if (aUv >= 0) GLES20.glDisableVertexAttribArray(aUv)
        buf.position(0)
    }

    // ---- Lens pre-distortion --------------------------------------------------------------

    private fun drawWarp(targets: Array<EyeTarget>, lm: LensModel, s: VrSettings, eyeW: Int) {
        GLES20.glViewport(0, 0, screenW, screenH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(progWarp)
        val k = s.distortionCoefficients
        for (eye in 0..1) {
            GLES20.glViewport(eye * eyeW, 0, eyeW, screenH)
            val t = lm.fovTan(eye)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, targets[eye].texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(progWarp, "uEye"), 0)
            // Half-viewport UV -> full-screen UV.
            GLES20.glUniform1f(GLES20.glGetUniformLocation(progWarp, "uHalfOffset"), eye * 0.5f)
            // Screen extent and lens centre, in tan-angle units.
            GLES20.glUniform2f(GLES20.glGetUniformLocation(progWarp, "uScreenTan"),
                lm.screenWidthM / lm.toLensM, lm.screenHeightM / lm.toLensM)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(progWarp, "uLensTan"),
                lm.eyeCenterXM(eye) / lm.toLensM, lm.lensYM / lm.toLensM)
            GLES20.glUniform3f(GLES20.glGetUniformLocation(progWarp, "uK"), k[0], k[1], k[2])
            // Eye image extent: (left, right, bottom, top) tangents.
            GLES20.glUniform4f(GLES20.glGetUniformLocation(progWarp, "uTexTan"), t[0], t[1], t[2], t[3])
            drawQuad(progWarp, fullscreenQuad)
        }
    }

    /** Cardboard's alignment marker: a thin line up from the bottom centre, between the eyes. */
    private fun drawAlignmentLine() {
        val wPx = maxOf(2, (0.0005f * pxPerM).toInt())
        val hPx = minOf(screenH, (0.024f * pxPerM).toInt())
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(screenW / 2 - wPx / 2, 0, wPx, hPx)
        GLES20.glClearColor(0.71f, 0.71f, 0.71f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }

    fun release() {
        surface?.release(); surface = null
        surfaceTexture?.release(); surfaceTexture = null
    }

    companion object {
        /** Resolution the browser is laid out at, in pixels. */
        const val CONTENT_W = 1600
        const val CONTENT_H = 900

        /** Size in metres of the virtual screen for a given content aspect ratio. */
        fun screenSize(s: VrSettings, aspect: Float): Pair<Float, Float> {
            val w = 2f * s.screenDistanceM * tan(Math.toRadians(s.screenWidthDeg / 2.0)).toFloat()
            return w to w / aspect
        }

        private val VS_MODEL = """
            uniform mat4 uMvp;
            attribute vec4 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() { vUv = aUv; gl_Position = uMvp * aPos; }
        """.trimIndent()

        private val FS_CONTENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            uniform mat4 uTexMatrix;
            uniform vec4 uRect;
            varying vec2 vUv;
            void main() {
                vec2 uv = uRect.xy + vUv * uRect.zw;
                gl_FragColor = texture2D(uTex, (uTexMatrix * vec4(uv, 0.0, 1.0)).xy);
            }
        """.trimIndent()

        private val FS_TEX = """
            precision mediump float;
            uniform sampler2D uTex;
            varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uTex, vUv); }
        """.trimIndent()

        private val FS_COLOR = """
            precision mediump float;
            uniform vec4 uColor;
            uniform float uCircle;
            varying vec2 vUv;
            void main() {
                float a = uColor.a;
                if (uCircle > 0.5) {
                    float r = length(vUv - 0.5) * 2.0;
                    if (r > 1.0) discard;
                    a *= smoothstep(1.0, 0.8, r);
                }
                gl_FragColor = vec4(uColor.rgb, a);
            }
        """.trimIndent()

        // Calibration wall: thin grid lines and a blue centre cross.
        private val FS_GRID = """
            precision highp float;
            uniform float uCells;
            varying vec2 vUv;
            void main() {
                vec2 g = vUv * uCells;
                vec2 f = abs(fract(g + 0.5) - 0.5) / 0.035;
                float line = 1.0 - clamp(min(f.x, f.y), 0.0, 1.0);
                vec2 c = abs(vUv - 0.5) * uCells;
                float cross = (c.x < 0.05 || c.y < 0.05) ? 1.0 : 0.0;
                vec3 col = mix(vec3(0.06, 0.07, 0.09), vec3(0.85), line);
                col = mix(col, vec3(0.35, 0.65, 1.0), cross);
                gl_FragColor = vec4(col, 1.0);
            }
        """.trimIndent()

        private val VS_WARP = """
            attribute vec4 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() { vUv = aUv; gl_Position = aPos; }
        """.trimIndent()

        // For each physical pixel: its tan-angle from the lens axis -> where the lens makes the
        // eye see it, p(1 + k1 r^2 + k2 r^4 + k3 r^6) -> that point of the eye image.
        private val FS_WARP = """
            precision highp float;
            uniform sampler2D uEye;
            uniform float uHalfOffset;
            uniform vec2 uScreenTan;
            uniform vec2 uLensTan;
            uniform vec3 uK;
            uniform vec4 uTexTan;
            varying vec2 vUv;
            void main() {
                vec2 screenUv = vec2(uHalfOffset + vUv.x * 0.5, vUv.y);
                vec2 p = screenUv * uScreenTan - uLensTan;
                float r2 = dot(p, p);
                float f = 1.0 + r2 * (uK.x + r2 * (uK.y + r2 * uK.z));
                vec2 d = p * f;
                vec2 src = vec2((d.x + uTexTan.x) / (uTexTan.x + uTexTan.y),
                                (d.y + uTexTan.z) / (uTexTan.z + uTexTan.w));
                if (src.x < 0.0 || src.y < 0.0 || src.x > 1.0 || src.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                } else {
                    gl_FragColor = texture2D(uEye, src);
                }
            }
        """.trimIndent()
    }
}
