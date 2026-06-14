package com.panorama.core.math

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.length
import dev.romainguy.kotlin.math.normalize
import dev.romainguy.kotlin.math.slerp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeWithinPercentageOf
import kotlin.math.abs

/** Pins the kotlin-math 1.8.0 conventions :core relies on, so a silent upstream change
 *  (renamed function, flipped angle unit, different slerp parameterisation) breaks here loudly.
 *  Confirmed against the downloaded jar: fromAxisAngle takes DEGREES; top-level slerp/dot/length/
 *  normalize live in dev.romainguy.kotlin.math; q * Float3 rotates the vector. */
class KotlinMathBoundaryTest : FunSpec({
    test("slerp endpoints return the inputs") {
        val a = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), 0f)
        val b = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), 90f)
        val s0 = slerp(a, b, 0f)
        val s1 = slerp(a, b, 1f)
        assert(abs(dot(s0, a)) > 0.999f)
        assert(abs(dot(s1, b)) > 0.999f)
    }

    test("normalized quaternion has unit length") {
        val q = normalize(Quaternion(1f, 2f, 3f, 4f))
        length(q).shouldBeWithinPercentageOf(1.0f, 0.01)
    }

    test("rotating a vector by yaw quaternion is length preserving") {
        val q = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), 37f)
        val v = Float3(1f, 0f, 0f)
        val r = q * v
        length(r).shouldBeWithinPercentageOf(1.0f, 0.01)
    }

    test("euler round-trip - build from yaw,pitch then recover within eps") {
        val q = quatFromYawPitch(40f, -15f)
        val (yaw, pitch) = yawPitchOf(q)
        assert(abs(yaw - 40f) < 0.5f && abs(pitch - (-15f)) < 0.5f)
    }
})
