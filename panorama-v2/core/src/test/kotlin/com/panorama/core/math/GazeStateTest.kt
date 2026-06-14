package com.panorama.core.math

import dev.romainguy.kotlin.math.Quaternion
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class GazeStateTest : FunSpec({
    test("identity gaze has zero yaw pitch and velocity") {
        val g = GazeState(Quaternion(), 0f, 0f, 0f)
        g.yawDeg shouldBe 0f
        g.pitchDeg shouldBe 0f
        g.angularVelocityDegPerSec shouldBe 0f
    }
})
