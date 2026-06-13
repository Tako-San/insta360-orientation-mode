package com.arashivision.orientation.panorama

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Minimal field-of-view helper for deciding whether a spherical target is visible.
 *
 * It uses the same yaw/pitch convention as [EquirectangularProjection]: yaw grows to the right,
 * pitch grows upward, and deltas are evaluated in screen terms (positive yaw delta = target is to
 * the right of the current gaze, positive pitch delta = target is above the current gaze).
 */
object PanoramaFovMath {

    fun resolveTarget(
        gaze: PanoramaDirection,
        target: PanoramaDirection,
        horizontalFovRad: Double,
        verticalFovRad: Double
    ): TargetFovState {
        require(horizontalFovRad > 0.0 && horizontalFovRad <= FULL_TURN_RAD) {
            "horizontalFovRad must be in (0, 2*PI]"
        }
        require(verticalFovRad > 0.0 && verticalFovRad <= PI) {
            "verticalFovRad must be in (0, PI]"
        }

        val yawDeltaRad = wrapToMinusPiPlusPi(target.yawRad - gaze.yawRad)
        val pitchDeltaRad = target.pitchRad - gaze.pitchRad
        val insideFov = abs(yawDeltaRad) <= horizontalFovRad / 2.0 &&
            abs(pitchDeltaRad) <= verticalFovRad / 2.0

        return TargetFovState(
            isInsideFov = insideFov,
            yawDeltaRad = yawDeltaRad,
            pitchDeltaRad = pitchDeltaRad,
            // Canvas coordinates have +Y downward, so an upward pitch delta becomes negative Y.
            arrowAngleRad = if (insideFov) null else atan2(-pitchDeltaRad, yawDeltaRad)
        )
    }

    /**
     * Quaternion-based variant: computes the target direction in the viewer's local
     * reference frame rather than subtracting Euler angles.
     *
     * This is robust against different ways the gaze quaternion may have been constructed
     * (e.g. from an Android rotation-vector sensor whose Euler conventions may differ from
     * the equirectangular +X-forward convention).
     *
     * Algorithm:
     * 1. Rotate the target's world-space vector by the conjugate of the gaze orientation
     *    to obtain the target direction in the viewer's local frame (+X=forward, +Y=right, +Z=up).
     * 2. Extract local yaw/pitch from the resulting 3D vector.
     * 3. Compute FOV membership and arrow angle from the local deltas.
     */
    fun resolveTargetQuat(
        gaze: PanoramaDirection,
        target: PanoramaDirection,
        horizontalFovRad: Double,
        verticalFovRad: Double
    ): TargetFovState {
        require(horizontalFovRad > 0.0 && horizontalFovRad <= FULL_TURN_RAD) {
            "horizontalFovRad must be in (0, 2*PI]"
        }
        require(verticalFovRad > 0.0 && verticalFovRad <= PI) {
            "verticalFovRad must be in (0, PI]"
        }

        // Rotate target's world-space vector by the inverse (conjugate) of the gaze
        // orientation to get the target direction in the viewer's local reference frame.
        val localTarget = gaze.orientation.conjugate().rotate(target.unitVector)

        // Extract local yaw (horizontal) and pitch (vertical) deltas.
        // localYawRad: atan2(localY, localX) — positive when target is to the right.
        // localPitchRad: atan2(localZ, sqrt(localX^2 + localY^2)) — positive when target is above.
        val localYawRad = atan2(localTarget.y, localTarget.x)
        val horizontalLen = sqrt(localTarget.x * localTarget.x + localTarget.y * localTarget.y)
        val localPitchRad = atan2(localTarget.z, horizontalLen)

        val insideFov = abs(localYawRad) <= horizontalFovRad / 2.0 &&
            abs(localPitchRad) <= verticalFovRad / 2.0

        return TargetFovState(
            isInsideFov = insideFov,
            yawDeltaRad = localYawRad,
            pitchDeltaRad = localPitchRad,
            // Canvas coordinates have +Y downward, so an upward pitch delta becomes negative Y.
            arrowAngleRad = if (insideFov) null else atan2(-localPitchRad, localYawRad)
        )
    }

    private fun wrapToMinusPiPlusPi(angleRad: Double): Double {
        var result = angleRad
        while (result <= -PI) result += FULL_TURN_RAD
        while (result > PI) result -= FULL_TURN_RAD
        return result
    }

    private const val FULL_TURN_RAD = 6.283185307179586
}

data class TargetFovState(
    val isInsideFov: Boolean,
    val yawDeltaRad: Double,
    val pitchDeltaRad: Double,
    /** Screen-space arrow angle: 0 points right, -PI/2 points up, +PI/2 points down. */
    val arrowAngleRad: Double?
)
