package com.arashivision.orientation.detection

import com.arashivision.orientation.panorama.EquirectangularProjection
import com.arashivision.orientation.panorama.PanoramaDirection
import com.arashivision.orientation.panorama.PanoramaFovMath

/**
 * State of the arrow pointing at a target: whether it is visible and at what screen angle (rad).
 * visible=false — all targets are within the field of view (or there are none), hide the arrow.
 */
data class ArrowState(
    val visible: Boolean,
    val angleRad: Double?
) {
    companion object {
        val HIDDEN = ArrowState(visible = false, angleRad = null)
    }
}

/**
 * Pure (JVM) arrow-selection logic: given the frame's detection list and the current gaze,
 * finds the FIRST target outside the field of view and returns the screen angle of the arrow to it.
 * If all targets are within the FOV or there are no detections — [ArrowState.HIDDEN].
 *
 * Extracted from LocalSphericalPlayerActivity.updateDirectionArrow for testability.
 */
object DetectionArrowResolver {
    fun resolve(
        objects: List<VideoDetectedObject>,
        gaze: PanoramaDirection,
        horizontalFovRad: Double,
        verticalFovRad: Double
    ): ArrowState {
        val firstOutside = objects.firstNotNullOfOrNull { detection ->
            val targetDirection = EquirectangularProjection.fromNormalized(
                x = detection.centerNorm.x.coerceIn(0.0, 1.0),
                y = detection.centerNorm.y.coerceIn(0.0, 1.0)
            )
            val result = PanoramaFovMath.resolveTargetQuat(
                gaze = gaze,
                target = targetDirection,
                horizontalFovRad = horizontalFovRad,
                verticalFovRad = verticalFovRad
            )
            result.takeUnless { it.isInsideFov }
        }
        val angle = firstOutside?.arrowAngleRad
        return if (angle == null) ArrowState.HIDDEN else ArrowState(visible = true, angleRad = angle)
    }
}
