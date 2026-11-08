package com.dreamshell.vr.controller

/** Minimal quaternion maths on (x, y, z, w) float arrays. */
object Quat {
    fun conj(q: FloatArray) = floatArrayOf(-q[0], -q[1], -q[2], q[3])

    fun mul(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
    )

    /** Rotate vector v by unit quaternion q. */
    fun rotate(q: FloatArray, v: FloatArray): FloatArray {
        val p = mul(mul(q, floatArrayOf(v[0], v[1], v[2], 0f)), conj(q))
        return floatArrayOf(p[0], p[1], p[2])
    }
}
