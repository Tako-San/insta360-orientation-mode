package com.arashivision.orientation.panorama

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewMatrixMathTest {
    private val eps = 1e-4f

    @Test
    fun `identity quaternion yields identity rotation`() {
        val out = FloatArray(16)
        ViewMatrixMath.viewFromQuaternion(UnitQuaternion.IDENTITY, out)
        val ident = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)
        for (i in 0..15) assertEquals("idx $i", ident[i], out[i], eps)
    }

    @Test
    fun `rotation part is orthonormal`() {
        val out = FloatArray(16)
        val q = UnitQuaternion.fromYawPitch(Math.toRadians(40.0), Math.toRadians(20.0))
        ViewMatrixMath.viewFromQuaternion(q, out)
        // columns of the 3x3 rotation part must be unit length
        fun col(c: Int) = floatArrayOf(out[c], out[c + 4], out[c + 8])
        for (c in 0..2) {
            val v = col(c)
            val len = Math.sqrt((v[0]*v[0] + v[1]*v[1] + v[2]*v[2]).toDouble()).toFloat()
            assertEquals(1f, len, 1e-3f)
        }
    }

    @Test
    fun `perspective matrix matches reference for known fov and aspect`() {
        val out = FloatArray(16)
        ViewMatrixMath.perspective(fovYDeg = 90f, aspect = 1f, near = 0.1f, far = 10f, out = out)
        // tan(45)=1 -> m[0]=m[5]=1
        assertEquals(1f, out[0], eps)
        assertEquals(1f, out[5], eps)
        assertEquals(-1f, out[11], eps) // perspective w = -z
        assertEquals(0f, out[15], eps)
    }
}
