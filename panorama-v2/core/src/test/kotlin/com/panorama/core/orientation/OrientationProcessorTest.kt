package com.panorama.core.orientation

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.dot
import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs

class OrientationProcessorTest : FunSpec({
    test("before calibration relative equals current") {
        val p = OrientationProcessor()
        val q = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), 30f)
        val rel = p.relativeTo(q)
        assert(abs(dot(rel, q)) > 0.999f)
    }

    test("after calibrating at q, relativeTo(q) is identity") {
        val p = OrientationProcessor()
        val q = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), 30f)
        p.calibrate(q)
        val rel = p.relativeTo(q)
        assert(abs(dot(rel, Quaternion())) > 0.999f)
    }
})
