package com.dreamshell.vr.gl

import com.dreamshell.vr.VerticalAlignment
import com.dreamshell.vr.VrSettings
import kotlin.math.atan
import kotlin.math.min
import kotlin.math.tan

/**
 * Per-eye field of view and the screen-to-eye-image mapping for a viewer on a screen of
 * known physical size (landscape, metres).
 *
 * Follows the Cardboard SDK's lens_distortion.cc and polynomial_radial_distortion.cc
 * (Apache License 2.0), by way of CardboardXR's C# port of them:
 *  - each eye's view extends from its lens axis to the screen edges, widened by the
 *    lens distortion and clamped to the lens's own limit, so the frustum is asymmetric;
 *  - the eyes sit half the lens separation either side of centre;
 *  - a screen point at tan-angle p from the lens axis is seen at p(1 + k1 r² + k2 r⁴ + k3 r⁶).
 *
 * Pure Kotlin, so it is unit-tested on the JVM.
 */
class LensModel(
    s: VrSettings,
    val screenWidthM: Float,
    val screenHeightM: Float,
) {
    private val k = s.distortionCoefficients
    val toLensM = s.screenToLensMm / 1000f
    val interLensM = s.interLensMm / 1000f

    /** Lens optical centre height above the bottom of the screen, metres. */
    val lensYM: Float = when (s.verticalAlignment) {
        VerticalAlignment.BOTTOM -> s.trayToLensMm / 1000f - BORDER_M
        VerticalAlignment.TOP -> screenHeightM - s.trayToLensMm / 1000f - BORDER_M
        VerticalAlignment.CENTER -> screenHeightM / 2f
    } + s.lensOffsetYMm / 1000f

    /** Left eye's tangents of its view edges from the lens axis: (left, right, bottom, top). */
    val leftFovTan: FloatArray
    /** Right eye mirrors left and right. */
    val rightFovTan: FloatArray

    init {
        val limit = tan(Math.toRadians(s.maxFovDeg.toDouble())).toFloat()
        val outer = (screenWidthM - interLensM) / 2f
        val inner = interLensM / 2f
        val bottom = lensYM
        val top = screenHeightM - lensYM
        fun edge(d: Float) = min(distortRadius(maxOf(d, 0f) / toLensM), limit)
        leftFovTan = floatArrayOf(edge(outer), edge(inner), edge(bottom), edge(top))
        rightFovTan = floatArrayOf(leftFovTan[1], leftFovTan[0], leftFovTan[2], leftFovTan[3])
    }

    fun fovTan(eye: Int) = if (eye == 0) leftFovTan else rightFovTan

    /** Field of view per side in degrees, for display. */
    fun fovDeg(eye: Int) = fovTan(eye).map { Math.toDegrees(atan(it).toDouble()).toFloat() }

    /** Eye (lens) centre x on the screen, metres from the left edge. */
    fun eyeCenterXM(eye: Int) =
        if (eye == 0) (screenWidthM - interLensM) / 2f else (screenWidthM + interLensM) / 2f

    /** Eye position offset in head space, metres. */
    fun eyeOffsetM(eye: Int) = (if (eye == 0) -0.5f else 0.5f) * interLensM

    fun distortionFactor(r2: Float): Float {
        var rf = 1f
        var f = 1f
        for (c in k) { rf *= r2; f += c * rf }
        return f
    }

    fun distortRadius(r: Float) = r * distortionFactor(r * r)

    /** Inverse of [distortRadius] by the secant method, like the SDK. */
    fun undistortRadius(r: Float): Float {
        if (r < 1e-7f) return 0f
        var r0 = r / 2f
        var r1 = r / 3f
        var dr0 = r - distortRadius(r0)
        var i = 0
        while (i < 100 && kotlin.math.abs(r1 - r0) > 1e-5f) {
            val dr1 = r - distortRadius(r1)
            val r2 = r1 - dr1 * ((r1 - r0) / (dr1 - dr0))
            r0 = r1; r1 = r2; dr0 = dr1
            i++
        }
        return r1
    }

    /**
     * Eye-image UV (0..1, origin bottom left) seen through the lens at a full-screen UV
     * (0..1, origin bottom left). Same maths as the warp shader; used by tests.
     */
    fun screenUvToTextureUv(eye: Int, u: Float, v: Float): FloatArray {
        val px = (u * screenWidthM - eyeCenterXM(eye)) / toLensM
        val py = (v * screenHeightM - lensYM) / toLensM
        val f = distortionFactor(px * px + py * py)
        val t = fovTan(eye)
        return floatArrayOf((px * f + t[0]) / (t[0] + t[1]), (py * f + t[2]) / (t[2] + t[3]))
    }

    companion object {
        /** Assumed bezel between the tray and the lit screen, as in the Cardboard SDK. */
        const val BORDER_M = 0.003f
    }
}
