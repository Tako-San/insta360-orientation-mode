package com.arashivision.orientation

import kotlin.math.abs

/**
 * Adaptive smoothing of orientation angles (yaw/pitch in degrees).
 *
 * Pure JVM logic without Android dependencies — tested on the JVM. Suppresses sensor
 * jitter at rest (small delta → strong smoothing) and barely smooths fast
 * turns (large delta → alpha→1, no lag). Yaw is handled accounting for
 * the wrap across ±180°.
 *
 * @param alphaMin the smoothing coefficient at rest (strong smoothing)
 * @param alphaMax the coefficient during fast motion (no lag)
 * @param speedFullDeg the delta (deg/tick) at which alpha reaches alphaMax
 */
class OrientationSmoothing(
    private val alphaMin: Float = 0.15f,
    private val alphaMax: Float = 1.0f,
    private val speedFullDeg: Float = 2.5f
) {
    private var smoothedYaw = 0f
    private var smoothedPitch = 0f
    private var initialized = false

    /** Reset the state — the next [update] will take the input angles as-is. */
    fun reset() {
        initialized = false
    }

    /**
     * Feed raw angles, get smoothed ones. The first call (or after [reset])
     * returns the input unchanged and initializes the state.
     */
    fun update(rawYawDeg: Float, rawPitchDeg: Float): TargetOrientation {
        if (!initialized) {
            smoothedYaw = rawYawDeg
            smoothedPitch = rawPitchDeg
            initialized = true
            return TargetOrientation(smoothedYaw, smoothedPitch)
        }

        var yawDelta = rawYawDeg - smoothedYaw
        while (yawDelta > 180f) yawDelta -= 360f
        while (yawDelta < -180f) yawDelta += 360f
        val pitchDelta = rawPitchDeg - smoothedPitch

        smoothedYaw += yawDelta * adaptiveAlpha(yawDelta)
        smoothedPitch += pitchDelta * adaptiveAlpha(pitchDelta)
        return TargetOrientation(smoothedYaw, smoothedPitch)
    }

    /**
     * The smoothing coefficient as a function of the angle change rate (deg/tick).
     * Linear interpolation of alpha from [alphaMin] (rest) to [alphaMax] (fast motion)
     * as the speed grows from 0 to [speedFullDeg].
     */
    fun adaptiveAlpha(deltaDeg: Float): Float {
        val t = (abs(deltaDeg) / speedFullDeg).coerceIn(0f, 1f)
        return alphaMin + (alphaMax - alphaMin) * t
    }
}
