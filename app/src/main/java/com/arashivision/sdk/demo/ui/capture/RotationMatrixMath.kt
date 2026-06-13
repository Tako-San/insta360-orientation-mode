package com.arashivision.sdk.demo.ui.capture

import android.hardware.SensorManager
import android.view.Surface
import com.arashivision.orientation.Quaternion
import com.arashivision.orientation.SensorOrientation

/**
 * Port over Android's native orientation functions (getRotationMatrixFromVector,
 * remapCoordinateSystem, getOrientation). Hides the only part of the gyro logic
 * that cannot run on a pure JVM. The real implementation is covered by a Robolectric test;
 * OrientationProcessor receives an already-prepared [SensorOrientation].
 */
interface RotationMatrixMath {
    /** Build the orientation from the raw rotation vector and the current display rotation. */
    fun fromRotationVector(rotationVectorValues: FloatArray, displayRotation: Int): SensorOrientation
}

/** Production implementation using native SensorManager functions. */
class AndroidRotationMatrixMath : RotationMatrixMath {
    private val rotMat = FloatArray(9)
    private val remapped = FloatArray(9)
    private val out = FloatArray(3)

    override fun fromRotationVector(rotationVectorValues: FloatArray, displayRotation: Int): SensorOrientation {
        SensorManager.getRotationMatrixFromVector(rotMat, rotationVectorValues)
        when (displayRotation) {
            Surface.ROTATION_0 -> SensorManager.remapCoordinateSystem(
                rotMat, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                rotMat, SensorManager.AXIS_Z, SensorManager.AXIS_MINUS_X, remapped)
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                rotMat, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Z, remapped)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                rotMat, SensorManager.AXIS_MINUS_Z, SensorManager.AXIS_X, remapped)
            else -> SensorManager.remapCoordinateSystem(
                rotMat, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        }
        val quaternion = Quaternion.fromRotationMatrix(remapped)
        SensorManager.getOrientation(remapped, out)
        val rawYawDeg = Math.toDegrees(out[0].toDouble()).toFloat()
        // landscape (90/270): pitch is in out[2]; portrait: out[1]
        val rawPitchComponent =
            if (displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270) out[2] else out[1]
        val rawPitchDeg = Math.toDegrees(rawPitchComponent.toDouble()).toFloat()
        val rawRollDeg = Math.toDegrees(out[2].toDouble()).toFloat()
        return SensorOrientation(quaternion, rawYawDeg, rawPitchDeg, rawRollDeg)
    }
}
