package com.panorama.android.sensor

import android.hardware.SensorManager
import android.view.Surface
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.normalize
import kotlin.math.sqrt

/** Site B (spec section 6.2): the single place the raw rotation-vector sensor sample is mapped
 *  into a device-display-aligned orientation. v1 scattered display-rotation handling across the
 *  gyro controller and the renderer; here it lives in one object so a screen-orientation bug has
 *  exactly one place to fix. Signs that belong to the *view* still live in Site A
 *  ([com.panorama.core.calibration.AxisConvention]); this stage only re-bases the sensor frame
 *  onto the current display rotation, nothing else.
 *
 *  The math runs through the platform [SensorManager] (getRotationMatrixFromVector +
 *  remapCoordinateSystem) rather than a hand-rolled remap, then converts the remapped rotation
 *  matrix to a kotlin-math [Quaternion] via Shepperd's method. */
object RemapConfig {

    /** Convert a [TYPE_ROTATION_VECTOR][android.hardware.Sensor.TYPE_ROTATION_VECTOR] sample into a
     *  normalized orientation quaternion, remapped for the given [displayRotation]
     *  (one of [Surface.ROTATION_0]/`_90`/`_180`/`_270`).
     *
     *  @param values the rotation-vector sensor values (3, 4 or 5 elements, as delivered by the OS).
     *  @return unit [Quaternion] in kotlin-math field order (x, y, z, w). */
    fun fromRotationVector(values: FloatArray, displayRotation: Int): Quaternion {
        val rotMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotMatrix, values)

        val (axisX, axisY) = remapAxes(displayRotation)
        val remapped = FloatArray(9)
        SensorManager.remapCoordinateSystem(rotMatrix, axisX, axisY, remapped)

        return matrixToQuaternion(remapped)
    }

    /** Display-rotation -> (axisX, axisY) for [SensorManager.remapCoordinateSystem].
     *  ROTATION_0 is the identity remap (standard portrait convention): a zero rotation vector
     *  maps to the identity orientation. Each 90-degree step rotates the device frame about Z. */
    private fun remapAxes(displayRotation: Int): Pair<Int, Int> = when (displayRotation) {
        Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
        else -> SensorManager.AXIS_X to SensorManager.AXIS_Y // ROTATION_0
    }

    /** Row-major 3x3 rotation matrix -> unit quaternion (Shepperd's method: pick the largest
     *  pivot among the four candidates for numerical stability). [m] is laid out as
     *  m[row*3 + col], matching [SensorManager.getRotationMatrixFromVector]. */
    private fun matrixToQuaternion(m: FloatArray): Quaternion {
        val m00 = m[0]; val m01 = m[1]; val m02 = m[2]
        val m10 = m[3]; val m11 = m[4]; val m12 = m[5]
        val m20 = m[6]; val m21 = m[7]; val m22 = m[8]
        val trace = m00 + m11 + m22

        val x: Float
        val y: Float
        val z: Float
        val w: Float
        when {
            trace > 0f -> {
                val s = sqrt(trace + 1f) * 2f // s = 4*w
                w = 0.25f * s
                x = (m21 - m12) / s
                y = (m02 - m20) / s
                z = (m10 - m01) / s
            }
            m00 > m11 && m00 > m22 -> {
                val s = sqrt(1f + m00 - m11 - m22) * 2f // s = 4*x
                w = (m21 - m12) / s
                x = 0.25f * s
                y = (m01 + m10) / s
                z = (m02 + m20) / s
            }
            m11 > m22 -> {
                val s = sqrt(1f + m11 - m00 - m22) * 2f // s = 4*y
                w = (m02 - m20) / s
                x = (m01 + m10) / s
                y = 0.25f * s
                z = (m12 + m21) / s
            }
            else -> {
                val s = sqrt(1f + m22 - m00 - m11) * 2f // s = 4*z
                w = (m10 - m01) / s
                x = (m02 + m20) / s
                y = (m12 + m21) / s
                z = 0.25f * s
            }
        }
        return normalize(Quaternion(x, y, z, w))
    }
}
