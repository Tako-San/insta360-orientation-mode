package com.arashivision.sdk.demo.ui.capture

import android.content.Context
import com.arashivision.orientation.OrientationProcessor
import com.arashivision.orientation.Quaternion
import com.elvishew.xlog.Logger
import com.elvishew.xlog.XLog

/**
 * Thin wiring: SensorSource → RotationMatrixMath → OrientationProcessor → applyOrientation.
 * All the math now lives in the pure-JVM OrientationProcessor (:lib), the native calls in
 * RotationMatrixMath. This class only handles the wiring and lifecycle.
 *
 * The constructor keeps compatibility: context/getDisplayRotation/applyOrientation.
 * The optional ports are replaced in tests.
 */
class GyroOrientationController(
    context: Context,
    private val getDisplayRotation: () -> Int,
    private val applyOrientation: (yawDeg: Float, pitchDeg: Float) -> Unit,
    private val sensorSource: SensorSource = AndroidSensorSource(context),
    private val rotationMath: RotationMatrixMath = AndroidRotationMatrixMath(),
    private val processor: OrientationProcessor = OrientationProcessor()
) {
    // lazy: XLog is initialized in Application; in pure JVM tests it is not needed and
    // must not crash the constructor (the logger is created only on the first logging call).
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

    // --- API kept for compatibility with the player/VR ---
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
