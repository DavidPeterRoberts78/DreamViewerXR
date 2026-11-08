package com.dreamshell.vr.gl

import com.dreamshell.vr.VerticalAlignment
import com.dreamshell.vr.ViewerPreset
import com.dreamshell.vr.VrSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan

class LensModelTest {

    // Roughly a 6.3" 20:9 phone in landscape.
    private val w = 0.1465f
    private val h = 0.0675f

    @Test fun undistortInvertsDistort() {
        for (preset in ViewerPreset.entries) {
            val m = LensModel(VrSettings().withViewer(preset), w, h)
            for (r in listOf(0.05f, 0.3f, 0.7f, 1.0f, 1.3f)) {
                assertEquals("${preset.name} r=$r", r, m.undistortRadius(m.distortRadius(r)), 1e-4f)
            }
        }
    }

    @Test fun distortionGrowsMonotonically() {
        for (preset in ViewerPreset.entries) {
            val m = LensModel(VrSettings().withViewer(preset), w, h)
            var last = 0f
            var r = 0.01f
            while (r < 1.6f) {
                val d = m.distortRadius(r)
                assertTrue("${preset.name} not monotonic at $r", d > last)
                last = d; r += 0.01f
            }
        }
    }

    @Test fun withoutDistortionEachEyeFillsItsHalfExactly() {
        val s = VrSettings().copy(k1 = 0f, k2 = 0f, k3 = 0f, maxFovDeg = 89f)
        val m = LensModel(s, w, h)
        val l0 = m.screenUvToTextureUv(0, 0f, 0f)
        val l1 = m.screenUvToTextureUv(0, 0.5f, 1f)
        assertEquals(0f, l0[0], 1e-4f); assertEquals(0f, l0[1], 1e-4f)
        assertEquals(1f, l1[0], 1e-4f); assertEquals(1f, l1[1], 1e-4f)
        val r0 = m.screenUvToTextureUv(1, 0.5f, 0f)
        val r1 = m.screenUvToTextureUv(1, 1f, 1f)
        assertEquals(0f, r0[0], 1e-4f); assertEquals(1f, r1[0], 1e-4f)
    }

    @Test fun lensCentreLooksDownTheOpticalAxis() {
        val m = LensModel(VrSettings(), w, h)
        for (eye in 0..1) {
            val u = m.eyeCenterXM(eye) / w
            val v = m.lensYM / h
            val t = m.fovTan(eye)
            val uv = m.screenUvToTextureUv(eye, u, v)
            assertEquals(t[0] / (t[0] + t[1]), uv[0], 1e-5f)
            assertEquals(t[2] / (t[2] + t[3]), uv[1], 1e-5f)
        }
    }

    @Test fun rightEyeMirrorsLeft() {
        val m = LensModel(VrSettings(), w, h)
        assertEquals(m.leftFovTan[0], m.rightFovTan[1], 0f)
        assertEquals(m.leftFovTan[1], m.rightFovTan[0], 0f)
        assertEquals(-m.eyeOffsetM(0), m.eyeOffsetM(1), 0f)
    }

    @Test fun fieldOfViewIsClampedByTheLens() {
        val s = VrSettings()
        val m = LensModel(s, w, h)
        for (t in m.leftFovTan) {
            val deg = Math.toDegrees(atan(t).toDouble())
            assertTrue("fov $deg", deg > 20 && deg <= s.maxFovDeg + 1e-3)
        }
    }

    @Test fun bottomAlignmentPutsLensAboveTray() {
        val s = VrSettings().copy(verticalAlignment = VerticalAlignment.BOTTOM, trayToLensMm = 35f)
        assertEquals(0.035f - LensModel.BORDER_M, LensModel(s, w, h).lensYM, 1e-6f)
    }
}
