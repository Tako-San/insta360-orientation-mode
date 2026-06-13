package com.arashivision.orientation.panorama

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Equirectangular panorama projection utilities.
 *
 * Coordinate system fixed for the offline panoramic player:
 * - Source pixels use image coordinates: origin is the top-left corner, +x goes right, +y goes down.
 * - Pixel coordinates are continuous edge-based coordinates in [0, width] x [0, height]. Therefore
 *   (width / 2, height / 2) is the optical center of the equirectangular frame.
 * - Normalized coordinates are x / width and y / height in [0, 1].
 * - yaw = 0 at the horizontal center, yaw = -PI at the left edge, yaw = +PI at the right edge.
 * - pitch = 0 at the vertical center, pitch = +PI/2 at the top edge, pitch = -PI/2 at the bottom edge.
 * - 3D vectors use a right-handed world where +X is forward, +Y is right, and +Z is up.
 *
 * With this convention, positive yaw turns the gaze to the right, and positive pitch looks up.
 */
object EquirectangularProjection {

    fun fromPixel(x: Double, y: Double, width: Int, height: Int): PanoramaDirection {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
        require(x in 0.0..width.toDouble()) { "x must be in [0, width]" }
        require(y in 0.0..height.toDouble()) { "y must be in [0, height]" }

        return fromNormalized(x / width.toDouble(), y / height.toDouble())
    }

    fun fromNormalized(x: Double, y: Double): PanoramaDirection {
        require(x in 0.0..1.0) { "normalized x must be in [0, 1]" }
        require(y in 0.0..1.0) { "normalized y must be in [0, 1]" }

        val yawRad = (x - 0.5) * FULL_TURN_RAD
        val pitchRad = (0.5 - y) * PI
        return fromYawPitch(yawRad, pitchRad)
    }

    fun fromYawPitch(yawRad: Double, pitchRad: Double): PanoramaDirection {
        require(yawRad.isFinite()) { "yawRad must be finite" }
        require(pitchRad.isFinite()) { "pitchRad must be finite" }
        require(pitchRad in -HALF_TURN_RAD..HALF_TURN_RAD) { "pitchRad must be in [-PI/2, PI/2]" }

        val unitVector = UnitVector3.fromYawPitch(yawRad, pitchRad)
        return PanoramaDirection(
            yawRad = yawRad,
            pitchRad = pitchRad,
            unitVector = unitVector,
            orientation = UnitQuaternion.fromYawPitch(yawRad, pitchRad)
        )
    }

    private const val FULL_TURN_RAD = 6.283185307179586
    private const val HALF_TURN_RAD = 1.5707963267948966
}

data class PanoramaDirection(
    val yawRad: Double,
    val pitchRad: Double,
    val unitVector: UnitVector3,
    /**
     * Quaternion-compatible direction representation in the same +X forward, +Y right, +Z up
     * coordinate system. Applying this quaternion to the forward vector (1, 0, 0) yields
     * [unitVector]. Components are stored as Android/common math friendly x, y, z, w.
     */
    val orientation: UnitQuaternion
) {
    val yawDeg: Double = Math.toDegrees(yawRad)
    val pitchDeg: Double = Math.toDegrees(pitchRad)
}

data class UnitVector3(
    val x: Double,
    val y: Double,
    val z: Double
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "vector components must be finite" }
    }

    companion object {
        fun fromYawPitch(yawRad: Double, pitchRad: Double): UnitVector3 {
            val cosPitch = cos(pitchRad)
            return UnitVector3(
                x = cosPitch * cos(yawRad),
                y = cosPitch * sin(yawRad),
                z = sin(pitchRad)
            ).normalized()
        }
    }

    fun normalized(): UnitVector3 {
        val length = sqrt(x * x + y * y + z * z)
        require(length > 0.0) { "cannot normalize a zero-length vector" }
        return UnitVector3(x / length, y / length, z / length)
    }
}

data class UnitQuaternion(
    val x: Double,
    val y: Double,
    val z: Double,
    val w: Double
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite() && w.isFinite()) {
            "quaternion components must be finite"
        }
    }

    companion object {
        val IDENTITY = UnitQuaternion(x = 0.0, y = 0.0, z = 0.0, w = 1.0)

        /**
         * Builds orientation as yaw around +Z followed by pitch around local -Y.
         * This maps the forward vector (+X) to the same direction as [UnitVector3.fromYawPitch].
         */
        fun fromYawPitch(yawRad: Double, pitchRad: Double): UnitQuaternion {
            val yaw = fromAxisAngle(axisX = 0.0, axisY = 0.0, axisZ = 1.0, angleRad = yawRad)
            val pitch = fromAxisAngle(axisX = 0.0, axisY = -1.0, axisZ = 0.0, angleRad = pitchRad)
            return (yaw * pitch).normalized()
        }

        private fun fromAxisAngle(axisX: Double, axisY: Double, axisZ: Double, angleRad: Double): UnitQuaternion {
            val halfAngle = angleRad / 2.0
            val sinHalf = sin(halfAngle)
            return UnitQuaternion(
                x = axisX * sinHalf,
                y = axisY * sinHalf,
                z = axisZ * sinHalf,
                w = cos(halfAngle)
            ).normalized()
        }
    }

    operator fun times(other: UnitQuaternion): UnitQuaternion = UnitQuaternion(
        w = w * other.w - x * other.x - y * other.y - z * other.z,
        x = w * other.x + x * other.w + y * other.z - z * other.y,
        y = w * other.y - x * other.z + y * other.w + z * other.x,
        z = w * other.z + x * other.y - y * other.x + z * other.w
    )

    fun rotate(vector: UnitVector3): UnitVector3 {
        val vectorQuaternion = UnitQuaternion(vector.x, vector.y, vector.z, 0.0)
        val rotated = this * vectorQuaternion * conjugate()
        return UnitVector3(rotated.x, rotated.y, rotated.z).normalized()
    }

    fun conjugate(): UnitQuaternion = UnitQuaternion(-x, -y, -z, w)

    fun normalized(): UnitQuaternion {
        val length = sqrt(x * x + y * y + z * z + w * w)
        require(length > 0.0) { "cannot normalize a zero-length quaternion" }
        return UnitQuaternion(x / length, y / length, z / length, w / length)
    }
}
