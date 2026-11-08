package com.dreamshell.vr.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/** Builds packets bit-by-bit from the documented layout and checks the decoder reads them back. */
class ControllerStateTest {

    private class BitWriter {
        val bytes = ByteArray(20)
        private var pos = 0
        fun put(value: Int, bits: Int) {
            for (i in bits - 1 downTo 0) {
                if ((value shr i) and 1 == 1) {
                    bytes[pos / 8] = (bytes[pos / 8].toInt() or (0x80 ushr (pos % 8))).toByte()
                }
                pos++
            }
        }
    }

    private fun packet(
        time: Int = 0, seq: Int = 0,
        ori: IntArray = intArrayOf(0, 0, 0), acc: IntArray = intArrayOf(0, 0, 0), gyro: IntArray = intArrayOf(0, 0, 0),
        touchX: Int = 0, touchY: Int = 0, buttons: Int = 0,
    ): ByteArray {
        val w = BitWriter()
        w.put(time, 9); w.put(seq, 5)
        ori.forEach { w.put(it and 0x1FFF, 13) }
        acc.forEach { w.put(it and 0x1FFF, 13) }
        gyro.forEach { w.put(it and 0x1FFF, 13) }
        w.put(touchX, 8); w.put(touchY, 8)
        w.put(buttons, 5)
        return w.bytes
    }

    @Test fun shortPacketIsRejected() = assertNull(ControllerState.decode(ByteArray(10)))

    @Test fun decodesHeaderFields() {
        val s = ControllerState.decode(packet(time = 0x1A5, seq = 0x13))!!
        assertEquals(0x1A5, s.time)
        assertEquals(0x13, s.seq)
    }

    @Test fun decodesSignedOrientationIncludingLowBit() {
        // 4095 = 2*pi; odd values exercise the low bits that straddle a byte boundary.
        val s = ControllerState.decode(packet(ori = intArrayOf(1023, -2047, 4095)))!!
        val k = (2 * PI / 4095).toFloat()
        assertEquals(1023 * k, s.oriX, 1e-5f)
        assertEquals(-2047 * k, s.oriY, 1e-5f)
        assertEquals(4095 * k, s.oriZ, 1e-5f)
    }

    @Test fun decodesAccelAndGyro() {
        val s = ControllerState.decode(packet(acc = intArrayOf(-1, 2, 4095), gyro = intArrayOf(7, -4096, 1)))!!
        val a = (8 * 9.8 / 4095).toFloat()
        val g = (2048.0 / 180 * PI / 4095).toFloat()
        assertEquals(-1 * a, s.accX, 1e-5f); assertEquals(2 * a, s.accY, 1e-5f); assertEquals(4095 * a, s.accZ, 1e-4f)
        assertEquals(7 * g, s.gyroX, 1e-5f); assertEquals(-4096 * g, s.gyroY, 1e-4f); assertEquals(1 * g, s.gyroZ, 1e-5f)
    }

    @Test fun decodesTouchAndButtons() {
        val s = ControllerState.decode(packet(touchX = 255, touchY = 128, buttons = 0b10101))!!
        assertEquals(1f, s.touchX, 1e-5f)
        assertEquals(128 / 255f, s.touchY, 1e-5f)
        assertTrue(s.isTouching)
        assertTrue(s.isClickDown)   // bit 0x01
        assertFalse(s.isHomeDown)   // 0x02
        assertTrue(s.isAppDown)     // 0x04
        assertFalse(s.isVolMinusDown) // 0x08
        assertTrue(s.isVolPlusDown) // 0x10
    }

    @Test fun axisAngleBecomesUnitQuaternion() {
        val k = (2 * PI / 4095).toFloat()
        val q = ControllerState.decode(packet(ori = intArrayOf(0, (PI / 2 / k).toInt(), 0)))!!.orientationQuat()
        val len = q.fold(0f) { acc, v -> acc + v * v }
        assertEquals(1f, len, 1e-4f)
        assertEquals(kotlin.math.sin(PI / 4).toFloat(), q[1], 1e-3f)
    }
}
