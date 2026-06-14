package com.panorama.core.calibration

import com.panorama.core.math.GazeState
import com.panorama.core.math.quatFromYawPitch
import io.kotest.core.spec.style.FunSpec
import io.kotest.property.Arb
import io.kotest.property.arbitrary.numericFloat
import io.kotest.property.checkAll
import kotlin.math.acos
import kotlin.math.min

/** Property pin for Site A: for any gaze the view matrix must encode exactly that yaw/pitch,
 *  recoverable to sub-degree accuracy. This is what makes [ViewCalibration] the trusted single
 *  source of truth the rest of the pipeline depends on. */
class ViewCalibrationPropertyTest : FunSpec({

    val conv = AxisConvention()

    /** Eye-space forward of the gaze recovered from the view matrix.
     *  The view rotation V maps world -> eye; its transpose maps eye -> world, so the world
     *  forward the camera looks at is V^T * (0,0,-1) (column-major: out[col*4+row]). */
    fun recoveredWorldForward(yaw: Float, pitch: Float): Triple<Float, Float, Float> {
        val m = FloatArray(16)
        ViewCalibration.viewMatrix(GazeState(quatFromYawPitch(yaw, pitch), yaw, pitch, 0f), conv, m)
        // V^T applied to (0,0,-1): pick the third column of V^T = third row of V.
        // row r of V is (m[0*4+r], m[1*4+r], m[2*4+r]); we need V^T * (0,0,-1) = -(V row 2).
        val fx = -m[2]
        val fy = -m[6]
        val fz = -m[10]
        return Triple(fx, fy, fz)
    }

    /** Forward vector for a gaze straight from the Euler convention, the ground truth. */
    fun expectedWorldForward(yaw: Float, pitch: Float): Triple<Float, Float, Float> {
        val f = quatFromYawPitch(yaw, pitch) * dev.romainguy.kotlin.math.Float3(0f, 0f, -1f)
        return Triple(f.x, f.y, f.z)
    }

    /** Angular error in degrees between the recovered and expected forward directions. */
    fun angularError(yaw: Float, pitch: Float): Float {
        val (ax, ay, az) = recoveredWorldForward(yaw, pitch)
        val (bx, by, bz) = expectedWorldForward(yaw, pitch)
        val dot = (ax * bx + ay * by + az * bz).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(min(1f, dot)).toDouble()).toFloat()
    }

    /** gaze -> view -> recovered forward, returning the angular error for that round-trip. */
    fun roundTrip(yaw: Float, pitch: Float, conv: AxisConvention): Float {
        require(conv == AxisConvention()) { "round-trip is defined against the default convention" }
        return angularError(yaw, pitch)
    }

    test("gaze -> view -> screen -> recovered yaw/pitch round-trips within eps") {
        // numericFloat (not float): keep generated edge cases finite — Arb.float injects NaN
        // even when bounded, which is not a meaningful gaze and would NaN-poison the round-trip.
        checkAll(Arb.numericFloat(-180f, 180f), Arb.numericFloat(-85f, 85f)) { yaw, pitch ->
            val err = roundTrip(yaw, pitch, conv)
            assert(err < 0.5f) { "round-trip angular error $err deg at yaw=$yaw pitch=$pitch" }
        }
    }
})
