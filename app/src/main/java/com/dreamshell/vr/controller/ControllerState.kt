package com.dreamshell.vr.controller

import kotlin.math.PI
import kotlin.math.sqrt

/**
 * One decoded 20-byte notification from the Daydream View controller.
 *
 * Bit layout ported from the open-source daydream-controller.js (Web Bluetooth)
 * reverse-engineering of the controller's GATT protocol:
 *
 *   time:9  seq:5  ori:3x13  acc:3x13  gyro:3x13  touchX:8  touchY:8  buttons:5
 *
 * Orientation is an axis-angle rotation vector in radians (direction = axis,
 * length = angle). Touch is 0..1 on both axes; (0,0) means "not touching".
 */
data class ControllerState(
    val time: Int = 0,
    val seq: Int = 0,
    val oriX: Float = 0f, val oriY: Float = 0f, val oriZ: Float = 0f,
    val accX: Float = 0f, val accY: Float = 0f, val accZ: Float = 0f,
    val gyroX: Float = 0f, val gyroY: Float = 0f, val gyroZ: Float = 0f,
    val touchX: Float = 0f, val touchY: Float = 0f,
    val isClickDown: Boolean = false,
    val isAppDown: Boolean = false,
    val isHomeDown: Boolean = false,
    val isVolPlusDown: Boolean = false,
    val isVolMinusDown: Boolean = false,
) {
    val isTouching: Boolean get() = touchX != 0f || touchY != 0f

    /** Orientation as a unit quaternion (x, y, z, w). */
    fun orientationQuat(): FloatArray {
        val angle = sqrt(oriX * oriX + oriY * oriY + oriZ * oriZ)
        if (angle < 1e-6f) return floatArrayOf(0f, 0f, 0f, 1f)
        val s = kotlin.math.sin(angle / 2f) / angle
        return floatArrayOf(oriX * s, oriY * s, oriZ * s, kotlin.math.cos(angle / 2f))
    }

    companion object {
        private const val ORI_SCALE = (2.0 * PI / 4095.0).toFloat()
        private const val ACC_SCALE = (8.0 * 9.8 / 4095.0).toFloat()
        private const val GYRO_SCALE = (2048.0 / 180.0 * PI / 4095.0).toFloat()

        /** Sign-extend a 13-bit field. */
        private fun s13(v: Int): Int = (v shl 19) shr 19

        fun decode(data: ByteArray): ControllerState? {
            if (data.size < 20) return null
            fun b(i: Int) = data[i].toInt() and 0xFF

            val time = (b(0) shl 1) or ((b(1) and 0x80) shr 7)
            val seq = (b(1) and 0x7C) shr 2

            val xOri = s13(((b(1) and 0x03) shl 11) or (b(2) shl 3) or ((b(3) and 0xE0) shr 5))
            val yOri = s13(((b(3) and 0x1F) shl 8) or b(4))
            val zOri = s13((b(5) shl 5) or ((b(6) and 0xF8) shr 3))

            val xAcc = s13(((b(6) and 0x07) shl 10) or (b(7) shl 2) or ((b(8) and 0xC0) shr 6))
            val yAcc = s13(((b(8) and 0x3F) shl 7) or ((b(9) and 0xFE) shr 1))
            val zAcc = s13(((b(9) and 0x01) shl 12) or (b(10) shl 4) or ((b(11) and 0xF0) shr 4))

            val xGyro = s13(((b(11) and 0x0F) shl 9) or (b(12) shl 1) or ((b(13) and 0x80) shr 7))
            val yGyro = s13(((b(13) and 0x7F) shl 6) or ((b(14) and 0xFC) shr 2))
            val zGyro = s13(((b(14) and 0x03) shl 11) or (b(15) shl 3) or ((b(16) and 0xE0) shr 5))

            val xTouch = (((b(16) and 0x1F) shl 3) or ((b(17) and 0xE0) shr 5)) / 255f
            val yTouch = (((b(17) and 0x1F) shl 3) or ((b(18) and 0xE0) shr 5)) / 255f

            val btn = b(18)
            return ControllerState(
                time = time, seq = seq,
                oriX = xOri * ORI_SCALE, oriY = yOri * ORI_SCALE, oriZ = zOri * ORI_SCALE,
                accX = xAcc * ACC_SCALE, accY = yAcc * ACC_SCALE, accZ = zAcc * ACC_SCALE,
                gyroX = xGyro * GYRO_SCALE, gyroY = yGyro * GYRO_SCALE, gyroZ = zGyro * GYRO_SCALE,
                touchX = xTouch, touchY = yTouch,
                isClickDown = btn and 0x01 != 0,
                isHomeDown = btn and 0x02 != 0,
                isAppDown = btn and 0x04 != 0,
                isVolMinusDown = btn and 0x08 != 0,
                isVolPlusDown = btn and 0x10 != 0,
            )
        }
    }
}
