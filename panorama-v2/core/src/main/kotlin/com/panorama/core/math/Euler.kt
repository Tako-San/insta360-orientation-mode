package com.panorama.core.math

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import kotlin.math.asin
import kotlin.math.atan2

/** Single source of truth for the Euler <-> quaternion convention used across :core
 *  (Task 1.4/1.5/1.7). We do NOT rely on kotlin-math's toEulerAngles/fromEuler ordering;
 *  instead we compose yaw (about +Y) then pitch (about +X) and recover by rotating the
 *  forward vector (0,0,-1). Angles are in degrees, matching Quaternion.fromAxisAngle. */

private val YAW_AXIS = Float3(0f, 1f, 0f)
private val PITCH_AXIS = Float3(1f, 0f, 0f)
private val FORWARD = Float3(0f, 0f, -1f)

/** Build a quaternion from yaw then pitch (intrinsic Y * X), angles in degrees. */
fun quatFromYawPitch(yaw: Float, pitch: Float): Quaternion =
    Quaternion.fromAxisAngle(YAW_AXIS, yaw) * Quaternion.fromAxisAngle(PITCH_AXIS, pitch)

/** Recover (yaw, pitch) in degrees from a quaternion by rotating the forward vector.
 *  yaw = atan2(-x, -z), pitch = asin(y); inverse of [quatFromYawPitch] for pitch in (-90, 90). */
fun yawPitchOf(q: Quaternion): Pair<Float, Float> {
    val f = q * FORWARD
    val yaw = Math.toDegrees(atan2(-f.x, -f.z).toDouble()).toFloat()
    val pitch = Math.toDegrees(asin(f.y.coerceIn(-1f, 1f).toDouble())).toFloat()
    return yaw to pitch
}
