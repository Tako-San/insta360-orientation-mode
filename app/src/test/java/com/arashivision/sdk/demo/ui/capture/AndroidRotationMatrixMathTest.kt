package com.arashivision.sdk.demo.ui.capture

import android.app.Application
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric runs the REAL native Android math (getRotationMatrixFromVector,
 * remapCoordinateSystem, getOrientation). The exact angles depend on the remap convention, so
 * we check the PROPERTIES of the wrapper (determinism, ranges, sensitivity to input), not
 * guessed values.
 *
 * Robolectric 4.13 supports SDK ≤ 34 (the project targetSdk=35) → we pin 34.
 * application = Application::class — an empty Application instead of InstaApp, so that Robolectric
 * does not call InstaMediaSDK.init() with the native c++_shared (which is not present on the JVM).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidRotationMatrixMathTest {
    private val math = AndroidRotationMatrixMath()

    private fun quat(axisX: Float, axisY: Float, axisZ: Float, deg: Double): FloatArray {
        val half = Math.toRadians(deg) / 2.0
        val s = Math.sin(half).toFloat()
        return floatArrayOf(axisX * s, axisY * s, axisZ * s, Math.cos(half).toFloat())
    }

    @Test
    fun `produces finite angles within valid ranges`() {
        val so = math.fromRotationVector(quat(0f, 0f, 1f, 30.0), Surface.ROTATION_0)
        assertTrue("yaw in [-180,180]", so.rawYawDeg in -180f..180f)
        assertTrue("pitch in [-180,180]", so.rawPitchDeg in -180f..180f)
        assertTrue("roll in [-180,180]", so.rawRollDeg in -180f..180f)
        // the quaternion is normalized
        assertEquals(1f, so.quaternion.magnitude(), 1e-3f)
    }

    @Test
    fun `is deterministic for the same input`() {
        val v = quat(0f, 0f, 1f, 45.0)
        val a = math.fromRotationVector(v.copyOf(), Surface.ROTATION_0)
        val b = math.fromRotationVector(v.copyOf(), Surface.ROTATION_0)
        assertEquals(a.rawYawDeg, b.rawYawDeg, 1e-4f)
        assertEquals(a.rawPitchDeg, b.rawPitchDeg, 1e-4f)
    }

    @Test
    fun `different tilt rotations yield different pitch`() {
        // Tilt about X is the live pitch axis after the remap (probe A confirmed this:
        // this is exactly where the motion "leaks"). Different tilt angles → different pitch.
        val a = math.fromRotationVector(quat(1f, 0f, 0f, 20.0), Surface.ROTATION_0)
        val b = math.fromRotationVector(quat(1f, 0f, 0f, 60.0), Surface.ROTATION_0)
        assertNotEquals(a.rawPitchDeg, b.rawPitchDeg)
    }

    @Test
    fun `landscape rotation selects a different pitch component than portrait`() {
        // In ROTATION_90 pitch is taken from out[2], in ROTATION_0 from out[1]; for a tilt
        // about X this yields different pitch values (we verify that the component selection works).
        val tilt = quat(1f, 0f, 0f, 40.0)
        val portrait = math.fromRotationVector(tilt.copyOf(), Surface.ROTATION_0)
        val landscape = math.fromRotationVector(tilt.copyOf(), Surface.ROTATION_90)
        assertNotEquals(portrait.rawPitchDeg, landscape.rawPitchDeg)
    }
}
