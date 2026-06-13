package com.arashivision.orientation.panorama

import kotlin.math.tan

/**
 * Pure (JVM) GL matrix builders for the panorama renderer. Column-major 4x4 (OpenGL layout).
 *
 * The view matrix is the rotation that maps world space into the camera's gaze frame: it is the
 * conjugate (inverse) of the gaze quaternion expressed as a rotation matrix. The camera stays at
 * the origin (the sphere surrounds it), so there is no translation.
 */
object ViewMatrixMath {
    fun viewFromQuaternion(q: UnitQuaternion, out: FloatArray) {
        // inverse of the gaze rotation = conjugate; build its rotation matrix (column-major)
        val c = q.conjugate()
        val x = c.x.toFloat(); val y = c.y.toFloat(); val z = c.z.toFloat(); val w = c.w.toFloat()
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        // column 0
        out[0] = 1f - 2f * (yy + zz); out[1] = 2f * (xy + wz);      out[2] = 2f * (xz - wy);      out[3] = 0f
        // column 1
        out[4] = 2f * (xy - wz);      out[5] = 1f - 2f * (xx + zz); out[6] = 2f * (yz + wx);      out[7] = 0f
        // column 2
        out[8] = 2f * (xz + wy);      out[9] = 2f * (yz - wx);      out[10] = 1f - 2f * (xx + yy); out[11] = 0f
        // column 3
        out[12] = 0f; out[13] = 0f; out[14] = 0f; out[15] = 1f
    }

    fun perspective(fovYDeg: Float, aspect: Float, near: Float, far: Float, out: FloatArray) {
        val f = 1f / tan(Math.toRadians(fovYDeg.toDouble()).toFloat() / 2f)
        for (i in 0..15) out[i] = 0f
        out[0] = f / aspect
        out[5] = f
        out[10] = (far + near) / (near - far)
        out[11] = -1f
        out[14] = (2f * far * near) / (near - far)
    }
}
