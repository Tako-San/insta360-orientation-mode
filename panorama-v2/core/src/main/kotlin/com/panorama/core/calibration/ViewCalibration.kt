package com.panorama.core.calibration

import com.panorama.core.math.GazeState
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.normalize
import kotlin.math.cos
import kotlin.math.sin

/** Site A: the single :core component that turns a [GazeState] into a view matrix and maps an
 *  equirect detection back into the same world basis. ALL axis signs come from [AxisConvention];
 *  this file is the only place they are applied (spec section 6). Geometry builders elsewhere stay
 *  sign-free so signs can never drift apart.
 *
 *  Basis: OpenGL eye space, +X right, +Y up, -Z forward, matching
 *  [com.panorama.core.math.quatFromYawPitch] / [com.panorama.core.math.yawPitchOf]. */
object ViewCalibration {

    private const val DEG_TO_RAD = (Math.PI / 180.0).toFloat()

    /** Writes the column-major 4x4 view (world -> eye) rotation into [out] (length 16).
     *
     *  Signature choice — `out: FloatArray(16)` rather than a returned `Mat4`:
     *   - kotlin-math's `Mat4` is an immutable value type, so an `out: Mat4` buffer cannot be
     *     mutated in place; returning a fresh `Mat4` every frame would allocate on the GL hot path.
     *   - kotlin-math also exposes no public `Mat4 * Float4` operator, so a `Mat4` result still
     *     could not be applied to a vector without extra conversions.
     *   - FloatArray(16) column-major IS the GL uniform layout (`glUniformMatrix4fv`), so the
     *     Phase 2 renderer reuses one buffer with zero allocation. This is the no-alloc, GL-native
     *     choice the spec asks for.
     *
     *  Builds Rx(pitchSign*pitch) * Ry(yawSign*yaw). With the default convention
     *  (yawSign=-1, pitchSign=-1) this is exactly the inverse of the gaze rotation
     *  Ry(yaw)*Rx(pitch), i.e. the view matrix that rotates the world opposite to the gaze. */
    fun viewMatrix(gaze: GazeState, conv: AxisConvention, out: FloatArray) {
        // swapYawPitch is applied before the signs so the tuning knobs compose predictably
        // (swap chooses which gaze axis drives yaw vs pitch; the signs then orient each).
        val yawDeg = if (conv.swapYawPitch) gaze.pitchDeg else gaze.yawDeg
        val pitchDeg = if (conv.swapYawPitch) gaze.yawDeg else gaze.pitchDeg
        val yaw = conv.yawSign * yawDeg * DEG_TO_RAD
        val pitch = conv.pitchSign * pitchDeg * DEG_TO_RAD
        val cy = cos(yaw); val sy = sin(yaw)
        val cp = cos(pitch); val sp = sin(pitch)

        // Row-major Ry(yaw):            Row-major Rx(pitch):
        //   [ cy  0  sy ]                 [ 1   0    0  ]
        //   [  0  1   0 ]                 [ 0  cp  -sp  ]
        //   [-sy  0  cy ]                 [ 0  sp   cp  ]
        // R = Rx * Ry (row-major), then stored column-major (out[col*4 + row]).
        val r00 = cy;          val r01 = 0f;  val r02 = sy
        val r10 = sp * sy;     val r11 = cp;  val r12 = -sp * cy
        val r20 = -cp * sy;    val r21 = sp;  val r22 = cp * cy

        out[0] = r00; out[1] = r10; out[2] = r20; out[3] = 0f
        out[4] = r01; out[5] = r11; out[6] = r21; out[7] = 0f
        out[8] = r02; out[9] = r12; out[10] = r22; out[11] = 0f
        out[12] = 0f; out[13] = 0f; out[14] = 0f; out[15] = 1f
    }

    /** Maps an equirect normalized detection centre (x,y in [0,1]) to a world-space unit vector
     *  in the SAME basis as [viewMatrix].
     *
     *  centerNorm.x in [0,1] -> yaw in [-180, 180] (left edge = -180, centre = 0, right edge = +180).
     *  centerNorm.y in [0,1] -> pitch in [+90, -90] (top of image = +pitch, bottom = -pitch).
     *  The world vector is the gaze forward for that yaw/pitch, identical to
     *  quatFromYawPitch(yaw,pitch) applied to (0,0,-1):
     *      x = -sin(yaw)*cos(pitch), y = sin(pitch), z = -cos(yaw)*cos(pitch). */
    fun detectionDirToWorld(centerNorm: Float2, conv: AxisConvention): Float3 {
        val yawDeg = (centerNorm.x * 2f - 1f) * 180f
        val pitchDeg = (0.5f - centerNorm.y) * 2f * 90f
        val yaw = yawDeg * DEG_TO_RAD
        val pitch = pitchDeg * DEG_TO_RAD
        val cp = cos(pitch)
        val forwardZ = if (conv.glForwardIsMinusZ) -1f else 1f
        val dir = Float3(
            forwardZ * sin(yaw) * cp,
            sin(pitch),
            forwardZ * cos(yaw) * cp,
        )
        return normalize(dir)
    }
}
