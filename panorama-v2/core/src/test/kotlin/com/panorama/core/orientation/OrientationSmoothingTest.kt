package com.panorama.core.orientation

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.dot
import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min

private fun angleDeg(a: Quaternion, b: Quaternion): Float =
    Math.toDegrees(2.0 * acos(min(abs(dot(a, b)).toDouble(), 1.0))).toFloat()

class OrientationSmoothingTest : FunSpec({
    test("first update returns input and zero velocity") {
        val s = OrientationSmoothing()
        val q = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), 25f)
        val out = s.update(q, dtSec = 0.016f)
        assert(abs(dot(out.quaternion, q)) > 0.999f)
        assert(out.angularVelocityDegPerSec == 0f)
    }

    test("at rest jitter is attenuated") {
        val s = OrientationSmoothing()
        val a = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), 0f)
        s.update(a, dtSec = 0.016f)
        val jitter = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), 1f)
        val out = s.update(jitter, dtSec = 0.016f)
        assert(angleDeg(a, out.quaternion) < 0.6f)
    }

    test("fast turn passes through with low lag") {
        val s = OrientationSmoothing()
        val dt = 0.016f
        // ~300 deg/s: yaw advances 300 * dt per frame
        val step = 300f * dt
        var yaw = 0f
        var out = s.update(Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), yaw), dt)
        repeat(15) {
            yaw += step
            out = s.update(Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), yaw), dt)
        }
        assert(out.angularVelocityDegPerSec > 150f)
    }
})
