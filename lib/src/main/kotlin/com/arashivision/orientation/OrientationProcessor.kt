package com.arashivision.orientation

/**
 * Чистая (JVM, без Android) обработка ориентации гироскопа: калибровка,
 * SLERP-сглаживание, относительные углы взгляда (gaze) и целевые yaw/pitch.
 *
 * Логика перенесена дословно из GyroOrientationController.onSensorChanged + геттеров.
 * На вход подаётся [SensorOrientation] (native-извлечение делает RotationMatrixMath в :app).
 *
 * @param rateLimitMs минимальный интервал между полными обработками (мс); 0 = без прореживания
 * @param smoothingAlpha коэффициент SLERP
 */
class OrientationProcessor(
    private val rateLimitMs: Long = 0L,
    private val smoothingAlpha: Float = 0.12f,
    var sensivity: Float = 1.2f,
    var invertYaw: Boolean = false,
    var invertPitch: Boolean = true
) {
    private var lastSensorUpdate = 0L

    private var lastRawYawDeg = 0f
    private var lastRawPitchDeg = 0f
    private var lastRawRollDeg = 0f
    private var smoothedYaw = 0f
    private var smoothedPitch = 0f
    private var lastEulerYaw = 0f
    private var lastEulerPitch = 0f
    private var lastEulerRoll = 0f

    private var calibrationQuaternion = Quaternion(1f, 0f, 0f, 0f)
    private var calibrated = false
    private var calibrationRawYawDeg = 0f
    private var calibrationRawPitchDeg = 0f

    private var currentQuaternion = Quaternion(1f, 0f, 0f, 0f)
    private var smoothedQuaternion = Quaternion(1f, 0f, 0f, 0f)

    /**
     * Обработать кадр. Возвращает true, если кадр обработан полностью (не отсечён
     * rate-limit'ом). Даже при отсечении raw-значения обновляются (gaze остаётся живым).
     */
    fun process(orientation: SensorOrientation, displayRotation: Int, now: Long): Boolean {
        // raw обновляем всегда — gaze не должен застывать между полными обработками
        currentQuaternion = orientation.quaternion
        lastRawYawDeg = orientation.rawYawDeg
        lastRawPitchDeg = orientation.rawPitchDeg
        lastRawRollDeg = orientation.rawRollDeg

        if (now - lastSensorUpdate < rateLimitMs) {
            return false
        }
        lastSensorUpdate = now

        if (calibrated) {
            val calibrationInverse = calibrationQuaternion.conjugate()
            val relativeQuaternion = currentQuaternion.multiply(calibrationInverse)
            smoothedQuaternion = Quaternion.slerp(smoothedQuaternion, relativeQuaternion, smoothingAlpha)
            val (yaw, pitch, roll) = smoothedQuaternion.toEulerAngles(
                previousYaw = lastEulerYaw, previousPitch = lastEulerPitch, previousRoll = lastEulerRoll
            )
            lastEulerYaw = yaw; lastEulerPitch = pitch; lastEulerRoll = roll
            val target = computeTargetOrientation(yaw, pitch, sensivity, invertYaw, invertPitch)
            smoothedYaw = target.yawDeg; smoothedPitch = target.pitchDeg
        } else {
            val (yaw, pitch, roll) = currentQuaternion.toEulerAngles(
                previousYaw = lastEulerYaw, previousPitch = lastEulerPitch, previousRoll = lastEulerRoll
            )
            lastEulerYaw = yaw; lastEulerPitch = pitch; lastEulerRoll = roll
            val target = computeTargetOrientation(yaw, pitch, sensivity, invertYaw, invertPitch)
            smoothedYaw = target.yawDeg; smoothedPitch = target.pitchDeg
        }
        return true
    }

    fun calibrate() {
        calibrationQuaternion = currentQuaternion.copy()
        calibrationRawYawDeg = lastRawYawDeg
        calibrationRawPitchDeg = lastRawPitchDeg
        lastEulerYaw = 0f; lastEulerPitch = 0f; lastEulerRoll = 0f
        calibrated = true
    }

    fun gazeYawDeg(): Float {
        var relative = lastRawYawDeg - calibrationRawYawDeg
        while (relative > 180f) relative -= 360f
        while (relative <= -180f) relative += 360f
        return relative
    }

    fun gazePitchDeg(): Float = -(lastRawPitchDeg - calibrationRawPitchDeg)

    fun rawEulerYawDeg(): Float = lastEulerYaw
    fun rawEulerPitchDeg(): Float = lastEulerPitch
    fun smoothedYawDeg(): Float = smoothedYaw
    fun smoothedPitchDeg(): Float = smoothedPitch
    fun currentQuaternion(): Quaternion = currentQuaternion.copy()
    fun smoothedQuaternion(): Quaternion = smoothedQuaternion.copy()
}
