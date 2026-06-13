package com.arashivision.sdk.demo.ui.capture

import android.content.Context
import com.arashivision.orientation.OrientationProcessor
import com.arashivision.orientation.Quaternion
import com.elvishew.xlog.Logger
import com.elvishew.xlog.XLog

/**
 * Тонкая обвязка: SensorSource → RotationMatrixMath → OrientationProcessor → applyOrientation.
 * Вся математика теперь в pure-JVM OrientationProcessor (:lib), нативные вызовы — в
 * RotationMatrixMath. Здесь только связка и lifecycle.
 *
 * Конструктор сохраняет совместимость: context/getDisplayRotation/applyOrientation.
 * Опциональные порты подменяются в тестах.
 */
class GyroOrientationController(
    context: Context,
    private val getDisplayRotation: () -> Int,
    private val applyOrientation: (yawDeg: Float, pitchDeg: Float) -> Unit,
    private val sensorSource: SensorSource = AndroidSensorSource(context),
    private val rotationMath: RotationMatrixMath = AndroidRotationMatrixMath(),
    private val processor: OrientationProcessor = OrientationProcessor()
) {
    // lazy: XLog инициализируется в Application; в чистых JVM-тестах он не нужен и
    // не должен ронять конструктор (логгер создаётся только при первом логировании).
    private val logger: Logger by lazy { XLog.tag(GyroOrientationController::class.java.simpleName).build() }
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    var enabled: Boolean = true
    var sensivity: Float
        get() = processor.sensivity
        set(v) { processor.sensivity = v }
    var invertYaw: Boolean
        get() = processor.invertYaw
        set(v) { processor.invertYaw = v }
    var invertPitch: Boolean
        get() = processor.invertPitch
        set(v) { processor.invertPitch = v }

    fun start() {
        if (!enabled) return
        sensorSource.start { values -> onValues(values) }
        logger.d("GyroOrientationController started (ports-based)")
    }

    fun stop() {
        sensorSource.stop()
        logger.d("GyroOrientationController stopped")
    }

    fun calibrate() {
        processor.calibrate()
        logger.d("Gyro calibrated")
    }

    fun setzOrientationEnabled(enabled: Boolean) { this.enabled = enabled }

    private fun onValues(values: FloatArray) {
        if (!enabled) return
        val orientation = rotationMath.fromRotationVector(values, getDisplayRotation())
        processor.process(orientation, getDisplayRotation(), nowMs())
        applyOrientation(processor.smoothedYawDeg(), processor.smoothedPitchDeg())
    }

    // --- API, сохранённый для совместимости с плеером/VR ---
    fun getRawEulerYawDeg(): Float = processor.rawEulerYawDeg()
    fun getRawEulerPitchDeg(): Float = processor.rawEulerPitchDeg()
    fun getSmoothedYaw(): Float = processor.smoothedYawDeg()
    fun getSmoothedPitch(): Float = processor.smoothedPitchDeg()
    fun getGazeYawDeg(): Float = processor.gazeYawDeg()
    fun getGazePitchDeg(): Float = processor.gazePitchDeg()
    fun getCurrentQuaternion(): Quaternion = processor.currentQuaternion()
    fun getSmoothedQuaternion(): Quaternion = processor.smoothedQuaternion()
    fun getRawCurrentQuaternion(): Quaternion = processor.currentQuaternion()
}
