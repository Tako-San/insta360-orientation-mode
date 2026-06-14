package com.panorama.core.fov

import com.panorama.core.calibration.AxisConvention
import com.panorama.core.calibration.ViewCalibration
import com.panorama.core.detection.Detection
import com.panorama.core.math.GazeState

/** The on-screen arrow decision for the current frame: whether to draw a guidance arrow and, if
 *  so, the screen-space angle it should point at. */
data class ArrowState(val visible: Boolean, val angleRad: Float?)

/** Turns the frame's detections + current gaze into an [ArrowState]. Each detection's equirect
 *  centre is mapped to a world direction by [ViewCalibration.detectionDirToWorld] (Site A, the one
 *  place axis signs live), then [PanoramaFov] decides in/out of view. The first detection that is
 *  outside the FOV wins the arrow; if every detection is already on screen, no arrow is shown.
 *
 *  Routing both the calibration and the FOV test through the SAME [AxisConvention] is what the
 *  cross-path trip-wire guards: a centre detection maps to forward and must read as inside. */
object ArrowResolver {

    fun resolve(
        detections: List<Detection>,
        gaze: GazeState,
        hFovRad: Float,
        vFovRad: Float,
        conv: AxisConvention,
    ): ArrowState {
        for (d in detections) {
            val world = ViewCalibration.detectionDirToWorld(d.centerNorm, conv)
            if (!PanoramaFov.isInsideFov(gaze, world, hFovRad, vFovRad)) {
                return ArrowState(visible = true, angleRad = PanoramaFov.arrowAngle(gaze, world))
            }
        }
        return ArrowState(visible = false, angleRad = null)
    }
}
