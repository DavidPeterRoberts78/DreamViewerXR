package com.dreamshell.vr.controller

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Turns controller orientation into a pointing direction relative to a "home" pose
 * captured when the user presses Home while pointing straight ahead.
 *
 * The raw orientation's axes aren't documented. daydream2hid moves the mouse sideways
 * with the second gyro axis (Y) and vertically with the first (X), which puts the
 * controller's long axis on Z, so Z is the default pointing axis. [Axis] and the
 * invert flags cover the other possibilities; the start screen's live test shows
 * which setting is right.
 */
class PointerMapper {

    enum class Axis { Z, Y, X }

    var axis = Axis.Z
    var invertX = false
    var invertY = false

    private var homeInv: FloatArray? = null

    val hasHome get() = homeInv != null

    fun setHome(q: FloatArray) { homeInv = Quat.conj(q) }
    fun clearHome() { homeInv = null }

    /**
     * Horizontal and vertical pointing angles in radians, relative to home (right and up
     * positive), or null when pointing more than 90 degrees away. Captures home on first use.
     */
    fun angles(q: FloatArray): FloatArray? {
        val home = homeInv ?: Quat.conj(q).also { homeInv = it }
        val rel = Quat.mul(home, q)
        val (fwd, right, up) = basis(axis)
        val d = Quat.rotate(rel, fwd)
        val z = dot(d, fwd)
        if (z <= 0.02f) return null
        var x = dot(d, right)
        var y = dot(d, up)
        if (invertX) x = -x
        if (invertY) y = -y
        return floatArrayOf(atan2(x, hypot(y, z)), atan2(y, hypot(x, z)))
    }

    /**
     * Where the pointing ray meets a flat screen of [width] x [height] metres at [distance]
     * metres, as (u, v) in 0..1 with v down. Values outside 0..1 mean off-screen.
     */
    fun screenUv(q: FloatArray, width: Float, height: Float, distance: Float): FloatArray? {
        val home = homeInv ?: Quat.conj(q).also { homeInv = it }
        val rel = Quat.mul(home, q)
        val (fwd, right, up) = basis(axis)
        val d = Quat.rotate(rel, fwd)
        val z = dot(d, fwd)
        if (z <= 0.05f) return null
        var x = dot(d, right)
        var y = dot(d, up)
        if (invertX) x = -x
        if (invertY) y = -y
        val t = distance / z
        return floatArrayOf(x * t / width + 0.5f, 0.5f - y * t / height)
    }

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private companion object {
        /** (forward, right, up) in the controller's body frame for each choice of pointing axis. */
        fun basis(a: Axis): Triple<FloatArray, FloatArray, FloatArray> = when (a) {
            Axis.Z -> Triple(floatArrayOf(0f, 0f, -1f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f))
            Axis.Y -> Triple(floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 1f))
            Axis.X -> Triple(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, -1f, 0f), floatArrayOf(0f, 0f, 1f))
        }
    }
}
