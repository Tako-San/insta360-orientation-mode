package com.arashivision.orientation.panorama

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Pure (JVM) GL matrix builders for the panorama renderer. Column-major 4x4 (OpenGL layout).
 *
 * The view matrix rotates the world opposite to the gaze so the camera (fixed at the origin,
 * surrounded by the sphere) looks where the user looks. We build it directly from gaze
 * yaw/pitch in the OpenGL eye basis (+X right, +Y up, -Z forward): yaw rotates about +Y,
 * pitch about +X. This mirrors the convention media3's onScrollChange used (and which probe A
 * verified as correct), so the same yaw/pitch produce the same on-screen heading — no
 * coordinate-system guesswork.
 */
object ViewMatrixMath {
    /**
     * View matrix from gaze yaw/pitch in degrees. yaw about +Y (look left/right), pitch about
     * +X (look up/down). View = Rx(pitch) · Ry(yaw) — the inverse rotations are baked by the
     * sign of the angles, calibrated on device.
     */
    fun viewFromYawPitch(yawDeg: Float, pitchDeg: Float, out: FloatArray) {
        // yaw negated: turning the phone right must pan the view right (the world rotates the
        // opposite way around the camera). Pinned here on device, not scattered in callers.
        val yaw = Math.toRadians(-yawDeg.toDouble())
        val pitch = Math.toRadians(pitchDeg.toDouble())
        val cy = cos(yaw).toFloat(); val sy = sin(yaw).toFloat()
        val cp = cos(pitch).toFloat(); val sp = sin(pitch).toFloat()

        // Ry(yaw) row-major:
        //   [ cy 0 sy ]
        //   [ 0  1 0  ]
        //   [-sy 0 cy ]
        // Rx(pitch) row-major:
        //   [1 0   0  ]
        //   [0 cp -sp ]
        //   [0 sp  cp ]
        // m = Rx · Ry  (row-major)
        val m = floatArrayOf(
            cy,            0f,   sy,
            sp * sy,       cp,  -sp * cy,
            -cp * sy,      sp,   cp * cy
        )
        // column-major 4x4: out[col*4 + row] = m[row*3 + col]
        out[0] = m[0]; out[1] = m[3]; out[2] = m[6]; out[3] = 0f
        out[4] = m[1]; out[5] = m[4]; out[6] = m[7]; out[7] = 0f
        out[8] = m[2]; out[9] = m[5]; out[10] = m[8]; out[11] = 0f
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
