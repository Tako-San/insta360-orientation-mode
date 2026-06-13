package com.arashivision.orientation

/**
 * Чистая (JVM) оркестрация ориентации офлайн-плеера: берёт калибровочно-относительные
 * углы взгляда (gaze) из гиро, инвертирует знаки под направление media3 и сглаживает.
 * Возвращает итоговые yaw/pitch (градусы), которые Activity подаёт И в сферу (через
 * OrientationApplier), И в направление взгляда для стрелки — из одного источника, чтобы
 * сфера и стрелка были синхронны.
 *
 * Знаки инвертированы: media3 onScrollChange крутит сферу противоположно повороту
 * телефона (по часовой → картинка против), и pitch перевёрнут (подтверждено зондом A).
 *
 * @param smoothing адаптивное сглаживание (давит дрожь на покое, не тормозит повороты)
 */
class PlayerOrientationCoordinator(
    private val smoothing: OrientationSmoothing = OrientationSmoothing()
) {
    /**
     * @param rawGazeYawDeg gaze yaw из гиро (getGazeYawDeg)
     * @param rawGazePitchDeg gaze pitch из гиро (getGazePitchDeg)
     * @return сглаженные инвертированные углы для сферы и стрелки
     */
    fun coordinate(rawGazeYawDeg: Float, rawGazePitchDeg: Float): TargetOrientation {
        val invertedYaw = -rawGazeYawDeg
        val invertedPitch = -rawGazePitchDeg
        return smoothing.update(invertedYaw, invertedPitch)
    }

    /** Сбросить сглаживание (например, при пересоздании плеера). */
    fun reset() = smoothing.reset()
}
