package com.panorama.core.orientation

import com.panorama.core.math.GazeState
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.dot
import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min

private fun angleDeg(a: Quaternion, b: Quaternion): Float =
    Math.toDegrees(2.0 * acos(min(abs(dot(a, b)).toDouble(), 1.0))).toFloat()

/** Pins the motion-to-photon lead: zero velocity is a no-op, and constant velocity leads the gaze
 *  forward in proportion to leadTime (so a 2x lead time is ~2x the angular advance). */
class GazePredictorTest : FunSpec({

    test("zero velocity predicts identity (no change)") {
        val gaze = GazeState(
            quaternion = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), 30f),
            yawDeg = 30f,
            pitchDeg = 0f,
            angularVelocityDegPerSec = 0f,
            angularAxis = Float3(0f, 1f, 0f),
        )
        val predicted = GazePredictor.predict(gaze, 30f)
        assert(abs(dot(predicted.quaternion, gaze.quaternion)) > 0.999f)
    }

    test("constant velocity leads forward proportionally to leadTime") {
        val gaze = GazeState(
            quaternion = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), 30f),
            yawDeg = 30f,
            pitchDeg = 0f,
            angularVelocityDegPerSec = 90f,
            angularAxis = Float3(0f, 1f, 0f),
        )
        val a30 = angleDeg(gaze.quaternion, GazePredictor.predict(gaze, 30f).quaternion)
        val a60 = angleDeg(gaze.quaternion, GazePredictor.predict(gaze, 60f).quaternion)
        // 90 deg/s * 0.030 s = 2.7 deg; 90 deg/s * 0.060 s = 5.4 deg.
        assert(abs(a30 - 2.7f) < 0.3f) { "a30=$a30 expected ~2.7" }
        assert(abs(a60 - 5.4f) < 0.3f) { "a60=$a60 expected ~5.4" }
        assert(a60 > a30) { "a60=$a60 must exceed a30=$a30" }
        assert(abs(a60 - 2f * a30) < 0.3f) { "a60=$a60 must be ~2x a30=$a30" }
    }
})
