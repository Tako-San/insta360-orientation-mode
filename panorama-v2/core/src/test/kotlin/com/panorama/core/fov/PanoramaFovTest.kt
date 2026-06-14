package com.panorama.core.fov

import com.panorama.core.math.GazeState
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import io.kotest.core.spec.style.FunSpec

/** Pins the quaternion FOV test: forward is inside, behind is outside. The viewer's forward is
 *  local -Z, so a target is inside only when it falls inside the h/v cones around -Z after being
 *  rotated into the viewer frame via inverse(gaze.quaternion). */
class PanoramaFovTest : FunSpec({

    val hFov = Math.toRadians(90.0).toFloat()
    val vFov = Math.toRadians(60.0).toFloat()
    val identity = GazeState(Quaternion(), 0f, 0f, 0f)

    test("target straight ahead is inside fov") {
        PanoramaFov.isInsideFov(identity, Float3(0f, 0f, -1f), hFov, vFov).let { assert(it) }
    }

    test("target directly behind is outside fov") {
        PanoramaFov.isInsideFov(identity, Float3(0f, 0f, 1f), hFov, vFov).let { assert(!it) }
    }
})
