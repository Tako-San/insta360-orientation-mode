package com.arashivision.orientation

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Quaternion (w, x, y, z) — pure JVM math, without Android dependencies.
 */
data class Quaternion(
    val w: Float,  // scalar
    val x: Float,  // i
    val y: Float,  // j
    val z: Float   // k
) {
    fun magnitude(): Float = sqrt(w * w + x * x + y * y + z * z)
    fun normalize(): Quaternion {
        val mag = magnitude()
        return if (mag > 0) {
            Quaternion(w / mag, x / mag, y / mag, z / mag)
        } else {
            Quaternion(1f, 0f, 0f, 0f)
        }
    }
    fun copy(): Quaternion = Quaternion(w, x, y, z)

    // conjugate(w + xi + yj + zk) = w - xi - yj - zk
    fun conjugate(): Quaternion = Quaternion(w, -x, -y, -z)

    // (a, b) * (c, d) = (ac - b·d, ad + bc + b × d)
    fun multiply(other: Quaternion): Quaternion {
        val w = this.w * other.w - this.x * other.x - this.y * other.y - this.z * other.z
        val x = this.w * other.x + this.x * other.w + this.y * other.z - this.z * other.y
        val y = this.w * other.y - this.x * other.z + this.y * other.w + this.z * other.x
        val z = this.w * other.z + this.x * other.y - this.y * other.x + this.z * other.w
        return Quaternion(w, x, y, z).normalize()
    }

    fun dot(other: Quaternion): Float =
        this.w * other.w + this.x * other.x + this.y * other.y + this.z * other.z

    /**
     * ZYX convention
     * return (yaw, pitch, roll) in deg
     *
     * standard transformation:
     * - yaw   : atan2(2(wz + xy), 1 - 2(y² + z²))
     * - pitch : asin(2(wy - xz))
     * - roll  : atan2(2(wx + yz), 1 - 2(x² + y²))
     */
    fun toEulerAngles(
        previousYaw: Float? = null,
        previousPitch: Float? = null,
        previousRoll: Float? = null
    ): Triple<Float, Float, Float> {
        val q = this.normalize()

        val sinPitch = (2.0f * (q.w * q.y - q.z * q.x)).coerceIn(-1f, 1f)

        var pitch = if (sinPitch >= 1.0f) {
            90f
        } else if (sinPitch <= -1.0f) {
            -90f
        } else {
            Math.toDegrees(asin(sinPitch.toDouble())).toFloat()
        }

        var yaw = Math.toDegrees(
            atan2(
                2.0 * (q.w * q.z + q.x * q.y).toDouble(),
                1.0 - 2.0 * (q.y * q.y + q.z * q.z).toDouble()
            )
        ).toFloat()

        var roll = Math.toDegrees(
            atan2(
                2.0 * (q.w * q.x + q.y * q.z).toDouble(),
                1.0 - 2.0 * (q.x * q.x + q.y * q.y).toDouble()
            )
        ).toFloat()

        if (previousYaw != null) yaw = unwrapToNearest(yaw, previousYaw)
        if (previousPitch != null) pitch = unwrapToNearest(pitch, previousPitch)
        if (previousRoll != null) roll = unwrapToNearest(roll, previousRoll)

        return Triple(yaw, pitch, roll)
    }

    private fun wrap180(a: Float): Float {
        var x = (a + 180f) % 360f
        if (x < 0f) x += 360f
        return x - 180f
    }

    private fun unwrapToNearest(angle: Float, reference: Float): Float {
        var a = wrap180(angle)
        val diff = a - reference
        if (diff > 180f) a -= 360f
        if (diff < -180f) a += 360f
        return a
    }

    companion object {
        /**
         * Convert a 3x3 rotation matrix to a quaternion
         *
         * Source: "Quaternion from Rotation Matrix" by S.W. Shepperd
         */
        fun fromRotationMatrix(mat: FloatArray): Quaternion {
            val trace = mat[0] + mat[4] + mat[8]

            val result: Quaternion = when {
                trace > 0 -> {
                    val s = 0.5f / sqrt(trace + 1.0f)
                    val w = 0.25f / s
                    val x = (mat[7] - mat[5]) * s
                    val y = (mat[2] - mat[6]) * s
                    val z = (mat[3] - mat[1]) * s
                    Quaternion(w, x, y, z)
                }
                // m00 is the largest diagonal element
                mat[0] > mat[4] && mat[0] > mat[8] -> {
                    val s = 2.0f * sqrt(1.0f + mat[0] - mat[4] - mat[8])
                    val w = (mat[7] - mat[5]) / s  // (m21 - m12) / s
                    val x = 0.25f * s
                    val y = (mat[1] + mat[3]) / s  // (m01 + m10) / s
                    val z = (mat[2] + mat[6]) / s  // (m02 + m20) / s
                    Quaternion(w, x, y, z)
                }
                // m11 is the largest diagonal element
                mat[4] > mat[8] -> {
                    val s = 2.0f * sqrt(1.0f + mat[4] - mat[0] - mat[8])
                    val w = (mat[2] - mat[6]) / s  // (m02 - m20) / s
                    val x = (mat[1] + mat[3]) / s  // (m01 + m10) / s
                    val y = 0.25f * s
                    val z = (mat[5] + mat[7]) / s  // (m12 + m21) / s
                    Quaternion(w, x, y, z)
                }
                // m22 is the largest diagonal element
                else -> {
                    val s = 2.0f * sqrt(1.0f + mat[8] - mat[0] - mat[4])
                    val w = (mat[3] - mat[1]) / s  // (m10 - m01) / s
                    val x = (mat[2] + mat[6]) / s  // (m02 + m20) / s
                    val y = (mat[5] + mat[7]) / s  // (m12 + m21) / s
                    val z = 0.25f * s
                    Quaternion(w, x, y, z)
                }
            }

            return result.normalize()
        }

        /**
         * Spherical linear interpolation (SLERP)
         * Interpolates between two quaternions at a constant angular velocity
         *
         * @param q1 the start quaternion
         * @param q2 the end quaternion
         * @param t the interpolation parameter [0, 1]: 0 = q1, 1 = q2
         * @return the interpolated quaternion
         */
        fun slerp(q1: Quaternion, q2: Quaternion, t: Float): Quaternion {
            val a = q1.normalize()
            val b = q2.normalize()

            var dotProduct = a.dot(b)

            val q2Final = if (dotProduct < 0.0f) {
                dotProduct = -dotProduct
                Quaternion(-b.w, -b.x, -b.y, -b.z)
            } else {
                b
            }

            dotProduct = dotProduct.coerceIn(-1.0f, 1.0f)

            val theta0 = acos(dotProduct.toDouble()).toFloat()
            val theta = theta0 * t

            val q3 = (q2Final - (a * dotProduct)).normalize()

            // SLERP: q(t) = q1 * cos(θ) + q3 * sin(θ)
            return (a * cos(theta.toDouble()).toFloat() + q3 * sin(theta.toDouble()).toFloat()).normalize()
        }

        private operator fun Quaternion.minus(other: Quaternion): Quaternion =
            Quaternion(this.w - other.w, this.x - other.x, this.y - other.y, this.z - other.z)

        private operator fun Quaternion.times(scalar: Float): Quaternion =
            Quaternion(this.w * scalar, this.x * scalar, this.y * scalar, this.z * scalar)

        private operator fun Quaternion.plus(other: Quaternion): Quaternion =
            Quaternion(this.w + other.w, this.x + other.x, this.y + other.y, this.z + other.z)
    }
}
