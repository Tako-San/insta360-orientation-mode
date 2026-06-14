package com.panorama.core.fov

import com.panorama.core.calibration.AxisConvention
import com.panorama.core.detection.Detection
import com.panorama.core.math.GazeState
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Quaternion
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Pins the arrow decision end to end: a detection at the gaze centre is inside (no arrow); a
 *  detection on the far edge of the equirect frame is behind the viewer (arrow shown). The
 *  CROSS-PATH trip-wire below exercises detectionDirToWorld (calibration) and isInsideFov (FOV)
 *  together, so a convention mismatch between them — the v1 bug — turns the test red. */
class ArrowResolverTest : FunSpec({

    val conv = AxisConvention()
    val hFov = Math.toRadians(90.0).toFloat()
    val vFov = Math.toRadians(60.0).toFloat()
    val identity = GazeState(Quaternion(), 0f, 0f, 0f)

    test("detection at gaze centre hides the arrow") {
        val detections = listOf(Detection(Float2(0.5f, 0.5f)))
        val state = ArrowResolver.resolve(detections, identity, hFov, vFov, conv)
        state.visible shouldBe false
        state.angleRad shouldBe null
    }

    test("detection on the far edge shows the arrow") {
        // centerNorm x=0.0 is yaw -180 deg: directly behind the viewer, outside any forward FOV.
        val detections = listOf(Detection(Float2(0.0f, 0.5f)))
        val state = ArrowResolver.resolve(detections, identity, hFov, vFov, conv)
        state.visible shouldBe true
        assert(state.angleRad != null) { "an off-screen detection must carry a screen angle" }
    }

    // CROSS-PATH trip-wire (spec section 6.4): "a detection at the gaze centre is ALWAYS inside fov".
    // This runs calibration (detectionDirToWorld) and FOV (isInsideFov) against ONE shared
    // convention. If their conventions drift apart, the centre detection is mis-mapped to a
    // direction that isInsideFov rejects, the arrow lights up, and THIS assertion fails — exactly
    // the v1 sign-drift bug surfacing in a single test instead of on the phone.
    test("CROSS-PATH trip-wire - centre detection is always inside fov") {
        val centre = listOf(Detection(Float2(0.5f, 0.5f)))
        ArrowResolver.resolve(centre, identity, hFov, vFov, conv).visible shouldBe false
    }
})
