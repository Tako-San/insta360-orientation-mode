package com.arashivision.sdk.demo.ui.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface
import com.arashivision.orientation.Quaternion
import com.arashivision.orientation.computeTargetOrientation
import com.elvishew.xlog.Logger
import com.elvishew.xlog.XLog

/**
 * Контроллер работы с гироскопом на основе кватернионов.
 *
 * Отвечает за:
 * - связь с сенсором
 * - remap по ориентации экрана
 * - обработку кватернионов
 * - калибровку
 * - сглаживание кватернионов SLERP (Spherical Linear Interpolation)
 * - конвертирование итогового кватерниона в (yaw/pitch) для совместимости
 * - выдачу готовых yaw/pitch через applyOrientation callback
 *
 * Конструируется с:
 * - context для получения SensorManager
 * - getDisplayRotation - лямбда, возвращающая Surface.ROTATION_*
 * - applyOrientation - функция, которая будет вызвана с готовыми значениями (градусы)
 */
class GyroOrientationController(
    context: Context,
    private val getDisplayRotation: () -> Int,
    private val applyOrientation: (yawDeg: Float, pitchDeg: Float) -> Unit
) : SensorEventListener {
    private val logger: Logger = XLog.tag(GyroOrientationController::class.java.simpleName).build()
    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVectorSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    var rateLimitMs = 0L // без искусственного прореживания — отзывчивость важнее
    private var lastSensorUpdate = 0L
    var smoothingAlpha = 0.12f // коэффициент интерполяции

    var sensivity: Float = 1.2f
    var invertYaw = false
    var invertPitch = true
    private val yawFactor = 0.04f
    private val pitchFactor = 0.02f
    private val yawSensitivity: Float
        get() = yawFactor * sensivity
    private val pitchSensitivity: Float
        get() = pitchFactor * sensivity

    private var lastLogTime = 0L
    private var lastRawYawDeg = 0f
    private var lastRawPitchDeg = 0f
    private var lastRawRollDeg = 0f
    private var smoothedYaw = 0f
    private var smoothedPitch = 0f
    private var lastEulerYaw = 0f
    private var lastEulerPitch = 0f
    private var lastEulerRoll = 0f

    private var calibrationQuaternion = Quaternion(1f, 0f, 0f, 0f) // идентичный кватернион
    private var calibrated = false

    // Raw orientation values captured at calibration time — used to produce
    // calibration-relative gaze angles that always update (unlike lastEulerYaw
    // which is frozen at 0 until calibrate() is called).
    private var calibrationRawYawDeg = 0f
    private var calibrationRawPitchDeg = 0f

    var enabled = true

    private val rotMat = FloatArray(9)
    private val remapped = FloatArray(9)
    private val out = FloatArray(3)

    private var currentQuaternion = Quaternion(1f, 0f, 0f, 0f)
    private var smoothedQuaternion = Quaternion(1f, 0f, 0f, 0f)

    fun start() {
        if (!enabled) return
        rotationVectorSensor?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)
            logger.d("GyroOrientationController started (quaternion-based)")
        }
    }

    fun stop() {
        try {
            sensorManager.unregisterListener(this)
        } catch (t: Throwable) {
            logger.w("unregisterListener failed on stop: ${t.message}")
        }
        logger.d("GyroOrientationController stopped")
    }

    fun calibrate() {
        calibrationQuaternion = currentQuaternion.copy()
        calibrationRawYawDeg = lastRawYawDeg
        calibrationRawPitchDeg = lastRawPitchDeg
        lastEulerYaw = 0f
        lastEulerPitch = 0f
        lastEulerRoll = 0f
        calibrated = true
        logger.d("Gyro calibrated: rawYaw=$lastRawYawDeg rawPitch=$lastRawPitchDeg q=(${calibrationQuaternion.w}, ${calibrationQuaternion.x}, ${calibrationQuaternion.y}, ${calibrationQuaternion.z})")
    }

    fun setzOrientationEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!enabled) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastSensorUpdate < rateLimitMs) {
            updateRawFromEvent(event)
            return
        }

        lastSensorUpdate = now
        updateRawFromEvent(event)

        when (getDisplayRotation()) {
            Surface.ROTATION_0 -> SensorManager.remapCoordinateSystem(
                rotMat,
                SensorManager.AXIS_X,
                SensorManager.AXIS_Z,
                remapped
            )
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                rotMat,
                SensorManager.AXIS_Z,
                SensorManager.AXIS_MINUS_X,
                remapped
            )
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                rotMat,
                SensorManager.AXIS_MINUS_X,
                SensorManager.AXIS_MINUS_Z,
                remapped
            )
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                rotMat,
                SensorManager.AXIS_MINUS_Z,
                SensorManager.AXIS_X,
                remapped
            )
            else -> SensorManager.remapCoordinateSystem(
                rotMat,
                SensorManager.AXIS_X,
                SensorManager.AXIS_Z,
                remapped
            )
        }

        currentQuaternion = Quaternion.fromRotationMatrix(remapped)

        SensorManager.getOrientation(remapped, out)
        lastRawYawDeg = Math.toDegrees(out[0].toDouble()).toFloat()
        // In landscape (ROTATION_90/270), getOrientation values[1] (rotation around -X)
        // is actually ROLL after remap, and values[2] (rotation around Y) is the true PITCH.
        // In portrait, values[1] is the correct pitch component.
        // We store the correct pitch in lastRawPitchDeg regardless of orientation.
        val displayRot = getDisplayRotation()
        val rawPitchComponent = if (displayRot == Surface.ROTATION_90 || displayRot == Surface.ROTATION_270) {
            out[2]  // landscape: actual pitch is in values[2]
        } else {
            out[1]  // portrait: pitch is in values[1]
        }
        lastRawPitchDeg = Math.toDegrees(rawPitchComponent.toDouble()).toFloat()
        lastRawRollDeg = Math.toDegrees(out[2].toDouble()).toFloat()

        // Always extract Euler angles for gaze tracking, even before calibration.
        // When calibrated: use the relative (calibration-offset) quaternion.
        // When not calibrated: use the raw currentQuaternion directly so the angles
        // still update with device motion instead of being frozen at 0.
        if (calibrated) {
            // q_relative = q_current * inverse(q_calibration)
            val calibrationInverse = calibrationQuaternion.conjugate()
            val relativeQuaternion = currentQuaternion.multiply(calibrationInverse)

            smoothedQuaternion = Quaternion.slerp(smoothedQuaternion, relativeQuaternion, smoothingAlpha)

            val (yaw, pitch, roll) = smoothedQuaternion.toEulerAngles(
                previousYaw = lastEulerYaw,
                previousPitch = lastEulerPitch,
                previousRoll = lastEulerRoll
            )

            lastEulerYaw = yaw
            lastEulerPitch = pitch
            lastEulerRoll = roll

            val target = computeTargetOrientation(yaw, pitch, sensivity, invertYaw, invertPitch)
            smoothedYaw = target.yawDeg
            smoothedPitch = target.pitchDeg

            val thisMoment = SystemClock.elapsedRealtime()
            if (thisMoment - lastLogTime >= 500L) {
                lastLogTime = thisMoment
                logger.d(
                    "yaw=$smoothedYaw pitch=$smoothedPitch " +
                            "rawYaw=$lastRawYawDeg rawPitch=$lastRawPitchDeg rawRoll=$lastRawRollDeg " +
                            "q=${smoothedQuaternion}"
                )
                logger.d(
                    "yawRawFromQuat=$yaw pitchRawFromQuat=$pitch rollRawFromQuat=$roll " +
                            "targetYaw=${yaw * yawSensitivity} targetPitch=${pitch * pitchSensitivity}"
                )
            }
            applyOrientation(smoothedYaw, smoothedPitch)
        } else {
            // Not calibrated yet — still extract Euler angles from the raw quaternion
            // so the gaze tracking works even before the user presses calibrate.
            val (yaw, pitch, roll) = currentQuaternion.toEulerAngles(
                previousYaw = lastEulerYaw,
                previousPitch = lastEulerPitch,
                previousRoll = lastEulerRoll
            )
            lastEulerYaw = yaw
            lastEulerPitch = pitch
            lastEulerRoll = roll

            // Применяем ориентацию к плееру и ДО калибровки — иначе управление ракурсом
            // не работает, пока пользователь не нажмёт "Калибровать" (это и было причиной
            // "сломанного управления ракурсом": applyOrientation вызывался только в
            // calibrated-ветке, а tryApplyOrientation плеера обновляет currentGazeDirection
            // только из этого колбэка).
            val target = computeTargetOrientation(yaw, pitch, sensivity, invertYaw, invertPitch)
            smoothedYaw = target.yawDeg
            smoothedPitch = target.pitchDeg
            applyOrientation(smoothedYaw, smoothedPitch)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // no-op
    }

    private fun updateRawFromEvent(event: SensorEvent) {
        try {
            SensorManager.getRotationMatrixFromVector(rotMat, event.values)
        } catch (t: Throwable) {
            logger.w("getRotationMatrixFromVector failed: ${t.message}")
        }
    }

    fun getLastRawYawDeg(): Float = lastRawYawDeg
    fun getLastRawPitchDeg(): Float = lastRawPitchDeg
    fun getLastRawRollDeg(): Float = lastRawRollDeg
    fun getSmoothedYaw(): Float = smoothedYaw
    fun getSmoothedPitch(): Float = smoothedPitch

    /** Raw (unscaled) Euler yaw from the smoothed relative quaternion. Only updated after calibration. */
    fun getRawEulerYawDeg(): Float = lastEulerYaw

    /** Raw (unscaled) Euler pitch from the smoothed relative quaternion. Only updated after calibration. */
    fun getRawEulerPitchDeg(): Float = lastEulerPitch

    /**
     * Calibration-relative gaze yaw from the raw sensor orientation.
     * ALWAYS updates (unlike [getRawEulerYawDeg] which requires calibration).
     * Positive = turn right from calibration pose.
     */
    fun getGazeYawDeg(): Float {
        val raw = lastRawYawDeg
        val offset = calibrationRawYawDeg
        var relative = raw - offset
        // Wrap to [-180, 180]
        while (relative > 180f) relative -= 360f
        while (relative <= -180f) relative += 360f
        return relative
    }

    /**
     * Calibration-relative gaze pitch from the raw sensor orientation.
     * ALWAYS updates (unlike [getRawEulerPitchDeg] which requires calibration).
     *
     * Note: getOrientation pitch is positive when the device tilts forward (top-down),
     * but equirectangular pitch is positive when looking UP. We negate to match.
     */
    fun getGazePitchDeg(): Float {
        val raw = lastRawPitchDeg
        val offset = calibrationRawPitchDeg
        return -(raw - offset)  // negate: getOrientation pitch convention is opposite to equirectangular
    }

    fun getCurrentQuaternion(): Quaternion = currentQuaternion.copy()
    fun getSmoothedQuaternion(): Quaternion = smoothedQuaternion.copy()

    /** The full current quaternion (before calibration offset), suitable for debug logging. */
    fun getRawCurrentQuaternion(): Quaternion = currentQuaternion.copy()
}
