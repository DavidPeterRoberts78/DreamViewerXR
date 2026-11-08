package com.dreamshell.vr.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PointerMapperTest {

    private fun axisAngle(x: Float, y: Float, z: Float, deg: Float): FloatArray {
        val a = Math.toRadians(deg.toDouble()).toFloat()
        val s = sin(a / 2)
        return floatArrayOf(x * s, y * s, z * s, cos(a / 2))
    }

    private val id = floatArrayOf(0f, 0f, 0f, 1f)

    @Test fun firstReadingBecomesCentre() {
        val m = PointerMapper()
        val q = axisAngle(0.3f, 0.9f, 0.3f, 70f)
        val uv = m.screenUv(q, 2f, 1f, 2f)!!
        assertEquals(0.5f, uv[0], 1e-5f); assertEquals(0.5f, uv[1], 1e-5f)
    }

    @Test fun yawLeftMovesLeftAndPitchUpMovesUp() {
        val m = PointerMapper().apply { setHome(id) }
        val left = m.screenUv(axisAngle(0f, 1f, 0f, 10f), 2f, 1f, 2f)!!
        assertTrue(left[0] < 0.5f); assertEquals(0.5f, left[1], 1e-5f)
        val up = m.screenUv(axisAngle(1f, 0f, 0f, 10f), 2f, 1f, 2f)!!
        assertTrue(up[1] < 0.5f); assertEquals(0.5f, up[0], 1e-5f)
    }

    @Test fun rayHitsScreenWhereGeometrySays() {
        val m = PointerMapper().apply { setHome(id) }
        // 10 degrees left at 2 m: x = -2 tan(10°) on a 2 m wide screen.
        val uv = m.screenUv(axisAngle(0f, 1f, 0f, 10f), 2f, 1f, 2f)!!
        val expected = 0.5f - 2f * Math.tan(Math.toRadians(10.0)).toFloat() / 2f
        assertEquals(expected, uv[0], 1e-4f)
    }

    @Test fun invertFlags() {
        val m = PointerMapper().apply { setHome(id); invertX = true; invertY = true }
        val left = m.screenUv(axisAngle(0f, 1f, 0f, 10f), 2f, 1f, 2f)!!
        assertTrue(left[0] > 0.5f)
        val up = m.screenUv(axisAngle(1f, 0f, 0f, 10f), 2f, 1f, 2f)!!
        assertTrue(up[1] > 0.5f)
    }

    @Test fun homeIsRelative() {
        val m = PointerMapper()
        val home = axisAngle(0f, 1f, 0f, 40f)
        m.setHome(home)
        val uv = m.screenUv(home, 2f, 1f, 2f)!!
        assertEquals(0.5f, uv[0], 1e-5f); assertEquals(0.5f, uv[1], 1e-5f)
    }

    @Test fun pointingBehindGivesNothing() {
        val m = PointerMapper().apply { setHome(id) }
        assertNull(m.screenUv(axisAngle(0f, 1f, 0f, 120f), 2f, 1f, 2f))
    }

    @Test fun everyAxisChoiceCentresAtHome() {
        for (a in PointerMapper.Axis.entries) {
            val m = PointerMapper().apply { axis = a; setHome(id) }
            val uv = m.screenUv(id, 2f, 1f, 2f)!!
            assertEquals(0.5f, uv[0], 1e-5f); assertEquals(0.5f, uv[1], 1e-5f)
        }
    }
}
