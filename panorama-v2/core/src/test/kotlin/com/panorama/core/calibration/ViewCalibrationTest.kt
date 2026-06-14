package com.panorama.core.calibration

import com.panorama.core.math.GazeState
import com.panorama.core.math.quatFromYawPitch
import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs

/** Invariants + a NEGATIVE guard that proves the suite actually polices the yaw sign:
 *  flipping yawSign MUST reverse the screen-direction inequality. If it does not, the test
 *  is asserting nothing and a future sign regression would slip through (the v1 pain). */
class ViewCalibrationTest : FunSpec({

    val defaultConv = AxisConvention()

    /** Apply the column-major view rotation (out[col*4 + row]) to a world vector, return eye-space. */
    fun applyView(m: FloatArray, x: Float, y: Float, z: Float): Triple<Float, Float, Float> {
        val ex = m[0] * x + m[4] * y + m[8] * z
        val ey = m[1] * x + m[5] * y + m[9] * z
        val ez = m[2] * x + m[6] * y + m[10] * z
        return Triple(ex, ey, ez)
    }

    fun gaze0(): GazeState = GazeState(quatFromYawPitch(0f, 0f), 0f, 0f, 0f)
    fun gazeYaw(yaw: Float): GazeState = GazeState(quatFromYawPitch(yaw, 0f), yaw, 0f, 0f)

    /** Screen-x of the fixed world forward point (0,0,-1) after the view rotation. */
    fun projectScreenX(yaw: Float, conv: AxisConvention): Float {
        val m = FloatArray(16)
        ViewCalibration.viewMatrix(gazeYaw(yaw), conv, m)
        return applyView(m, 0f, 0f, -1f).first
    }

    test("identity gaze yields identity view (sign-free builder invariant)") {
        val m = FloatArray(16)
        ViewCalibration.viewMatrix(gaze0(), defaultConv, m)
        val (x, y, z) = applyView(m, 0f, 0f, -1f)
        assert(abs(x - 0f) < 1e-5f && abs(y - 0f) < 1e-5f && abs(z - (-1f)) < 1e-5f) {
            "identity view must leave forward (0,0,-1) unchanged, got ($x,$y,$z)"
        }
    }

    test("increasing yaw moves a fixed world point in a consistent screen direction") {
        // With the default convention (view = inverse of gaze) yawing +10 deg shifts the fixed
        // world forward point in the +screenX direction. The exact sign is pinned here.
        val s0 = projectScreenX(0f, defaultConv)
        val s10 = projectScreenX(10f, defaultConv)
        assert(s10 > s0) { "expected screenX(yaw=10) > screenX(yaw=0), got s0=$s0 s10=$s10" }
    }

    test("NEGATIVE - a wrong yawSign breaks yaw monotonicity") {
        val flipped = defaultConv.copy(yawSign = -defaultConv.yawSign)
        val s0 = projectScreenX(0f, flipped)
        val s10 = projectScreenX(10f, flipped)
        // Reversed sign must reverse the inequality, proving the suite truly guards the sign.
        assert(s10 < s0) { "flipping yawSign must reverse direction, got s0=$s0 s10=$s10" }
    }
})
