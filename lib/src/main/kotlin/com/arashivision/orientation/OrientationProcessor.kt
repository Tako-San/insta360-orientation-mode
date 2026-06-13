package com.arashivision.orientation

/**
 * Pure (JVM, no Android) gyroscope orientation processing: calibration,
 * SLERP smoothing, relative gaze angles, and target yaw/pitch.
 *
 * The logic is ported verbatim from GyroOrientationController.onSensorChanged + the getters.
 * The input is a [SensorOrientation] (the native extraction is done by RotationMatrixMath in :app).
 *
 * @param rateLimitMs the minimum interval between full processings (ms); 0 = no throttling
 * @param smoothingAlpha the SLERP coefficient
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
     * Process a frame. Returns true if the frame was processed in full (not throttled
     * by the rate limit). Even when throttled, the raw values are updated (gaze stays live).
     */
    fun process(orientation: SensorOrientation, displayRotation: Int, now: Long): Boolean {
        // we always update raw — gaze must not freeze between full processings
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
