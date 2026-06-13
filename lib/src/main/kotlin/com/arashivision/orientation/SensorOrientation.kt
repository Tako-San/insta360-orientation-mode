package com.arashivision.orientation

/**
 * Уже извлечённая ориентация устройства за один кадр сенсора — результат работы
 * RotationMatrixMath (native getRotationMatrixFromVector + remapCoordinateSystem +
 * getOrientation, с выбором оси под поворот экрана). Чистые данные для OrientationProcessor.
 *
 * @param quaternion ориентация как кватернион (из remapped-матрицы)
 * @param rawYawDeg yaw из getOrientation (градусы), азимут
 * @param rawPitchDeg pitch-компонента, уже выбранная по ориентации экрана (градусы)
 * @param rawRollDeg roll (градусы), для отладки
 */
data class SensorOrientation(
    val quaternion: Quaternion,
    val rawYawDeg: Float,
    val rawPitchDeg: Float,
    val rawRollDeg: Float
)
