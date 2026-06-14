package com.panorama.core.math

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion

/** Immutable orientation snapshot handed from the sensor thread to the GL thread.
 *  angularAxis + angularVelocityDegPerSec are the smoothed rotation rate that GazePredictor
 *  (Task 1.7) extrapolates along. angularAxis defaults to +Y so 4-arg construction stays valid. */
data class GazeState(
    val quaternion: Quaternion,
    val yawDeg: Float,
    val pitchDeg: Float,
    val angularVelocityDegPerSec: Float,
    val angularAxis: Float3 = Float3(0f, 1f, 0f),
)
