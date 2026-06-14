package com.panorama.core.orientation

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.inverse
import dev.romainguy.kotlin.math.length
import dev.romainguy.kotlin.math.slerp
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.min

/** Result of one smoothing step: the filtered orientation plus the smoothed rotation rate
 *  (speed + axis) that GazePredictor (Task 1.7) extrapolates along. */
data class Smoothed(
    val quaternion: Quaternion,
    val angularVelocityDegPerSec: Float,
    val angularAxis: Float3,
)

/** Adaptive, real-dt low-pass filter for orientation.
 *
 *  alpha = 1 - exp(-dt / tau(speed)) makes the response frame-rate-independent: the same
 *  wall-clock smoothing regardless of tick rate. tau is large (heavy smoothing) when the
 *  head is nearly still and small (responsive) when turning fast, interpolated by the
 *  incoming angular speed:
 *      tau = lerp(tauStill, tauFast, clamp(speed / speedFull, 0, 1))
 *
 *  The smoothed value is slerp(prev, target, alpha). Reported angular velocity is the angle
 *  travelled from prev to the smoothed result over dt (deg/s); the axis is the rotation axis
 *  of the delta smoothed * prev^-1 (normalized vector part), falling back to +Y when the
 *  delta is ~identity. Defaults tuned only to pass the qualitative tests; final tuning is on
 *  device in Phase 4. */
class OrientationSmoothing(
    private val tauStill: Float = 0.20f,
    private val tauFast: Float = 0.02f,
    private val speedFull: Float = 90f,
) {
    private var prev: Quaternion? = null

    fun update(target: Quaternion, dtSec: Float): Smoothed {
        val previous = prev
        if (previous == null) {
            prev = target
            return Smoothed(target, 0f, DEFAULT_AXIS)
        }

        // Incoming speed (deg/s) drives the adaptive time constant.
        val incomingSpeed = if (dtSec > 0f) angleDeg(previous, target) / dtSec else 0f
        val t = (incomingSpeed / speedFull).coerceIn(0f, 1f)
        val tau = tauStill + (tauFast - tauStill) * t
        val alpha = if (tau > 0f) (1f - exp(-dtSec / tau)) else 1f

        val smoothed = slerp(previous, target, alpha)

        val velocity = if (dtSec > 0f) angleDeg(previous, smoothed) / dtSec else 0f
        val axis = rotationAxis(smoothed * inverse(previous))

        prev = smoothed
        return Smoothed(smoothed, velocity, axis)
    }

    /** Full rotation angle (degrees) between two unit quaternions: 2 * acos(|dot|). */
    private fun angleDeg(a: Quaternion, b: Quaternion): Float =
        Math.toDegrees(2.0 * acos(min(abs(dot(a, b)).toDouble(), 1.0))).toFloat()

    /** Rotation axis of a delta quaternion: normalized vector part, +Y fallback near identity. */
    private fun rotationAxis(delta: Quaternion): Float3 {
        val v = Float3(delta.x, delta.y, delta.z)
        val len = length(v)
        return if (len < 1e-6f) DEFAULT_AXIS else v / len
    }

    private companion object {
        val DEFAULT_AXIS = Float3(0f, 1f, 0f)
    }
}
