package com.arashivision.orientation

data class TargetOrientation(val yawDeg: Float, val pitchDeg: Float)

/**
 * Чистое преобразование Euler-углов в целевые (yaw, pitch) для плеера:
 * масштаб по чувствительности, инверсия осей, клампинг.
 */
fun computeTargetOrientation(
    eulerYawDeg: Float,
    eulerPitchDeg: Float,
    sensivity: Float,
    invertYaw: Boolean,
    invertPitch: Boolean
): TargetOrientation {
    val yawFactor = 0.04f
    val pitchFactor = 0.02f
    val maxYaw = 360f
    val maxPitch = 270f

    val targetYaw = eulerYawDeg * (yawFactor * sensivity) * if (invertYaw) -1f else 1f
    val targetPitch = eulerPitchDeg * (pitchFactor * sensivity) * if (invertPitch) -1f else 1f

    return TargetOrientation(
        yawDeg = targetYaw.coerceIn(-maxYaw, maxYaw),
        pitchDeg = targetPitch.coerceIn(-maxPitch, maxPitch)
    )
}
