package com.arashivision.orientation.detection

import com.arashivision.orientation.panorama.EquirectangularProjection
import com.arashivision.orientation.panorama.PanoramaDirection
import com.arashivision.orientation.panorama.PanoramaFovMath

/**
 * Состояние стрелки-указателя на цель: видна ли и под каким экранным углом (рад).
 * visible=false — все цели в поле зрения (или целей нет), стрелку прячем.
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
 * Чистая (JVM) логика выбора стрелки: по списку детекций кадра и текущему взгляду
 * находит ПЕРВУЮ цель вне поля зрения и возвращает экранный угол стрелки к ней.
 * Если все цели в FOV или детекций нет — [ArrowState.HIDDEN].
 *
 * Извлечено из LocalSphericalPlayerActivity.updateDirectionArrow ради тестируемости.
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
