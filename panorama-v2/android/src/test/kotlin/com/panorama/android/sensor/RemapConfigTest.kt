package com.panorama.android.sensor

import android.view.Surface
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.normalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemapConfigTest {

    /** Length of the [x,y,z,w] vector — a valid rotation quaternion is unit-length. */
    private fun norm(q: Quaternion): Float =
        kotlin.math.sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w)

    @Test
    fun `flat device facing north yields near-identity gaze for ROTATION_0`() {
        // Identity rotation vector: zero rotation about an arbitrary axis.
        val values = floatArrayOf(0f, 0f, 0f)
        val q = RemapConfig.fromRotationVector(values, Surface.ROTATION_0)

        // Up to sign (q and -q are the same rotation): |dot| with identity is ~1.
        assertTrue("expected near-identity, got $q", abs(dot(q, Quaternion())) > 0.99f)
    }

    @Test
    fun `result is a normalized non-NaN quaternion for all display rotations`() {
        val values = floatArrayOf(0.1f, 0.2f, 0.3f)
        for (rotation in intArrayOf(
            Surface.ROTATION_0,
            Surface.ROTATION_90,
            Surface.ROTATION_180,
            Surface.ROTATION_270,
        )) {
            val q = RemapConfig.fromRotationVector(values, rotation)
            assertTrue("NaN for rotation=$rotation: $q", q.x.isFinite() && q.y.isFinite() && q.z.isFinite() && q.w.isFinite())
            assertEquals("not unit-length for rotation=$rotation: $q", 1f, norm(q), 1e-3f)
        }
    }

    @Test
    fun `landscape ROTATION_90 remap differs from ROTATION_0 for a non-trivial rotation`() {
        // A non-trivial tilt so the display-rotation remap actually changes the axes.
        val values = floatArrayOf(0.1f, 0.2f, 0.3f)
        val q0 = normalize(RemapConfig.fromRotationVector(values, Surface.ROTATION_0))
        val q90 = normalize(RemapConfig.fromRotationVector(values, Surface.ROTATION_90))

        // Different remap axes => a genuinely different rotation (|dot| well below 1).
        val similarity = abs(dot(q0, q90))
        assertTrue("ROTATION_90 should differ from ROTATION_0, |dot|=$similarity", similarity < 0.99f)
    }
}
