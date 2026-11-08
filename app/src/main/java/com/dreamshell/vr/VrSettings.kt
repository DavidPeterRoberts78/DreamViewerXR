package com.dreamshell.vr

import android.content.Context
import com.dreamshell.vr.controller.PointerMapper

/**
 * Physical description of the headset, in the same terms as the Cardboard SDK's viewer
 * profile (DeviceParams), plus comfort and controller options.
 */
data class VrSettings(
    // ---- Viewer (lenses) ----
    val viewer: ViewerPreset = ViewerPreset.DAYDREAM_2017,
    /** Phone screen to lens plane, millimetres. Sets how strongly screen distance maps to angle. */
    val screenToLensMm: Float = ViewerPreset.DAYDREAM_2017.screenToLensMm,
    /** Distance between the two lens centres, millimetres. Also used as the eye separation. */
    val interLensMm: Float = ViewerPreset.DAYDREAM_2017.interLensMm,
    val verticalAlignment: VerticalAlignment = ViewerPreset.DAYDREAM_2017.verticalAlignment,
    /** Lens centre height from the tray edge, millimetres (bottom/top alignment only). */
    val trayToLensMm: Float = ViewerPreset.DAYDREAM_2017.trayToLensMm,
    /** Fine vertical nudge of the lens centres, millimetres (+ = up). */
    val lensOffsetYMm: Float = 0f,
    /** Radial distortion p' = p(1 + k1 r^2 + k2 r^4 + k3 r^6), r in tan-angle units. */
    val k1: Float = ViewerPreset.DAYDREAM_2017.k1,
    val k2: Float = ViewerPreset.DAYDREAM_2017.k2,
    val k3: Float = ViewerPreset.DAYDREAM_2017.k3,
    /** The lenses' own field-of-view limit per side, degrees. */
    val maxFovDeg: Float = ViewerPreset.DAYDREAM_2017.maxFovDeg,
    val distortionEnabled: Boolean = true,

    // ---- Comfort ----
    /** How wide the virtual screen appears, in degrees of your view. */
    val screenWidthDeg: Float = 62f,
    /** Virtual screen distance, metres. Changes depth, not apparent size. */
    val screenDistanceM: Float = 2.5f,
    /** Use reverse landscape if the image is upside down in your headset. */
    val reverseLandscape: Boolean = false,

    // ---- Controller ----
    /** true = point with the controller, false = touchpad moves the cursor. */
    val airPointer: Boolean = true,
    val pointerAxis: PointerMapper.Axis = PointerMapper.Axis.Z,
    val pointerInvertX: Boolean = false,
    val pointerInvertY: Boolean = false,
    val invertScroll: Boolean = false,
    /** Touchpad sensitivity multiplier (default 0.75 = 75%). */
    val touchpadSensitivity: Float = 0.75f,

    // ---- Safety ----
    val disclaimerAccepted: Boolean = false,
) {
    val distortionCoefficients get() = floatArrayOf(k1, k2, k3)

    /** Same settings with the lens values replaced by a preset's. */
    fun withViewer(p: ViewerPreset) = copy(
        viewer = p,
        screenToLensMm = p.screenToLensMm, interLensMm = p.interLensMm,
        verticalAlignment = p.verticalAlignment, trayToLensMm = p.trayToLensMm,
        lensOffsetYMm = 0f, k1 = p.k1, k2 = p.k2, k3 = p.k3, maxFovDeg = p.maxFovDeg,
    )

    fun applyTo(m: PointerMapper) {
        m.axis = pointerAxis
        m.invertX = pointerInvertX
        m.invertY = pointerInvertY
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("version", VERSION)
            .putString("viewer", viewer.name)
            .putFloat("screenToLens", screenToLensMm)
            .putFloat("interLens", interLensMm)
            .putString("vAlign", verticalAlignment.name)
            .putFloat("trayToLens", trayToLensMm)
            .putFloat("lensY", lensOffsetYMm)
            .putFloat("k1", k1).putFloat("k2", k2).putFloat("k3", k3)
            .putFloat("maxFov", maxFovDeg)
            .putBoolean("distort", distortionEnabled)
            .putFloat("screenDeg", screenWidthDeg)
            .putFloat("screenDist", screenDistanceM)
            .putBoolean("reverseLandscape", reverseLandscape)
            .putBoolean("airPointer", airPointer)
            .putString("pointerAxis", pointerAxis.name)
            .putBoolean("invX", pointerInvertX)
            .putBoolean("invY", pointerInvertY)
            .putBoolean("invScroll", invertScroll)
            .putFloat("touchpadSens", touchpadSensitivity)
            .putBoolean("disclaimerAccepted", disclaimerAccepted)
            .apply()
    }

    companion object {
        private const val PREFS = "vr_settings"
        /** Bumped when the lens model changed; older saved lens values are discarded. */
        private const val VERSION = 2

        fun load(ctx: Context): VrSettings {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = VrSettings()
            if (p.getInt("version", 1) < VERSION) {
                // Keep comfort/orientation choices, reset everything lens-related.
                return d.copy(
                    screenWidthDeg = p.getFloat("screenDeg", d.screenWidthDeg),
                    screenDistanceM = p.getFloat("screenDist", d.screenDistanceM),
                    reverseLandscape = p.getBoolean("reverseLandscape", d.reverseLandscape),
                    airPointer = p.getBoolean("airPointer", d.airPointer),
                )
            }
            return VrSettings(
                viewer = enumOr(p.getString("viewer", null), d.viewer),
                screenToLensMm = p.getFloat("screenToLens", d.screenToLensMm),
                interLensMm = p.getFloat("interLens", d.interLensMm),
                verticalAlignment = enumOr(p.getString("vAlign", null), d.verticalAlignment),
                trayToLensMm = p.getFloat("trayToLens", d.trayToLensMm),
                lensOffsetYMm = p.getFloat("lensY", d.lensOffsetYMm),
                k1 = p.getFloat("k1", d.k1), k2 = p.getFloat("k2", d.k2), k3 = p.getFloat("k3", d.k3),
                maxFovDeg = p.getFloat("maxFov", d.maxFovDeg),
                distortionEnabled = p.getBoolean("distort", d.distortionEnabled),
                screenWidthDeg = p.getFloat("screenDeg", d.screenWidthDeg),
                screenDistanceM = p.getFloat("screenDist", d.screenDistanceM),
                reverseLandscape = p.getBoolean("reverseLandscape", d.reverseLandscape),
                airPointer = p.getBoolean("airPointer", d.airPointer),
                pointerAxis = enumOr(p.getString("pointerAxis", null), d.pointerAxis),
                pointerInvertX = p.getBoolean("invX", d.pointerInvertX),
                pointerInvertY = p.getBoolean("invY", d.pointerInvertY),
                invertScroll = p.getBoolean("invScroll", d.invertScroll),
                touchpadSensitivity = p.getFloat("touchpadSens", 0.75f),
                disclaimerAccepted = p.getBoolean("disclaimerAccepted", false),
            )
        }

        private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
            name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: fallback
    }
}

enum class VerticalAlignment { BOTTOM, CENTER, TOP }

/**
 * Starting lens values. The distortion coefficients are Google's own for each headset
 * (quoted in googlevr/cardboard issue #17). The lens spacing, screen-to-lens distance and
 * vertical alignment aren't published, so they are typical values for these lenses;
 * fine-tune them with the calibration grid.
 */
enum class ViewerPreset(
    val label: String,
    val screenToLensMm: Float,
    val interLensMm: Float,
    val verticalAlignment: VerticalAlignment,
    val trayToLensMm: Float,
    val k1: Float, val k2: Float, val k3: Float,
    val maxFovDeg: Float,
) {
    DAYDREAM_2017("Daydream View (2017)", 39.3f, 63.9f, VerticalAlignment.CENTER, 35f,
        0.4331f, -0.0856f, 1.4535f, 60f),
    DAYDREAM_2016("Daydream View (2016)", 39.3f, 63.9f, VerticalAlignment.CENTER, 35f,
        0.385f, 0.593f, 0f, 60f),
    CARDBOARD_V1("Cardboard v1", 42f, 60f, VerticalAlignment.BOTTOM, 35f,
        0.441f, 0.156f, 0f, 40f),
}
