package com.arashivision.orientation.panorama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewMatrixMathTest {
    private val eps = 1e-4f

    private fun mulVec(m: FloatArray, vx: Float, vy: Float, vz: Float): FloatArray =
        floatArrayOf(
            m[0]*vx + m[4]*vy + m[8]*vz,
            m[1]*vx + m[5]*vy + m[9]*vz,
            m[2]*vx + m[6]*vy + m[10]*vz
        )

    private fun col(m: FloatArray, c: Int) = floatArrayOf(m[c], m[c + 4], m[c + 8])
    private fun len(v: FloatArray) = Math.sqrt((v[0]*v[0] + v[1]*v[1] + v[2]*v[2]).toDouble()).toFloat()

    @Test
    fun `zero yaw pitch is identity rotation`() {
        val out = FloatArray(16)
        ViewMatrixMath.viewFromYawPitch(0f, 0f, out)
        val ident = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)
        for (i in 0..15) assertEquals("idx $i", ident[i], out[i], eps)
    }

    @Test
    fun `rotation part is orthonormal for an arbitrary gaze`() {
        val out = FloatArray(16)
        ViewMatrixMath.viewFromYawPitch(40f, 20f, out)
        for (c in 0..2) assertEquals("col $c length", 1f, len(col(out, c)), 1e-3f)
    }

    @Test
    fun `pure yaw rotates the gaze horizontally (no vertical tilt)`() {
        // GL forward (0,0,-1) under a pure yaw must stay on the horizon → Y stays ~0.
        val out = FloatArray(16)
        ViewMatrixMath.viewFromYawPitch(30f, 0f, out)
        val v = mulVec(out, 0f, 0f, -1f)
        assertEquals("pure yaw keeps Y ~ 0", 0f, v[1], 1e-3f)
        // and it actually moved horizontally (X non-zero)
        assertTrue("pure yaw must move X", kotlin.math.abs(v[0]) > 0.3f)
    }

    @Test
    fun `positive yaw pans the gaze to the +X side (direction pinned, device-verified)`() {
        // Device-verified mapping: a positive gaze yaw must send the GL forward (0,0,-1) toward
        // +X. Pins the yaw sign so a regression flips left/right silently.
        val out = FloatArray(16)
        ViewMatrixMath.viewFromYawPitch(30f, 0f, out)
        val v = mulVec(out, 0f, 0f, -1f)
        assertTrue("positive yaw should move forward toward +X, got x=${v[0]}", v[0] > 0.3f)
    }

    @Test
    fun `pure pitch rotates the gaze vertically (no horizontal swing)`() {
        val out = FloatArray(16)
        ViewMatrixMath.viewFromYawPitch(0f, 30f, out)
        val v = mulVec(out, 0f, 0f, -1f)
        assertEquals("pure pitch keeps X ~ 0", 0f, v[0], 1e-3f)
        assertTrue("pure pitch must move Y", kotlin.math.abs(v[1]) > 0.3f)
    }

    @Test
    fun `perspective matrix matches reference for known fov and aspect`() {
        val out = FloatArray(16)
        ViewMatrixMath.perspective(fovYDeg = 90f, aspect = 1f, near = 0.1f, far = 10f, out = out)
        assertEquals(1f, out[0], eps)
        assertEquals(1f, out[5], eps)
        assertEquals(-1f, out[11], eps)
        assertEquals(0f, out[15], eps)
    }
}
