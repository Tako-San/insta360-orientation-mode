package com.panorama.android.sensor

import android.view.Surface
import com.panorama.core.math.yawPitchOf
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
import kotlin.math.sin

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

    @Test
    fun `vertical tilt survives the landscape remap as pitch (regression for the AXIS_Z bug)`() {
        // Physical "nose of the phone tilts up" = a rotation about the device X axis. After remapping
        // for any display rotation, that tilt must still read as a non-trivial PITCH through the same
        // forward-vector decomposition the renderer uses (yawPitchOf). The old ROTATION_90/270 remap
        // fed AXIS_Z into the screen-horizontal slot, which folded forward off -Z and collapsed pitch
        // to ~0 in landscape -- the "vertical control dead in landscape" device bug. This pins it.
        val tiltAboutX = rotationVectorAboutX(30f)
        for (rotation in intArrayOf(Surface.ROTATION_90, Surface.ROTATION_270)) {
            val q = RemapConfig.fromRotationVector(tiltAboutX, rotation)
            val (_, pitch) = yawPitchOf(q)
            assertTrue(
                "vertical tilt must produce real pitch in landscape rotation=$rotation, got pitch=$pitch",
                abs(pitch) > 15f,
            )
        }
    }

    /** TYPE_ROTATION_VECTOR sample (3-element) for a pure rotation about device +X, degrees. */
    private fun rotationVectorAboutX(deg: Float): FloatArray {
        val half = Math.toRadians(deg.toDouble() / 2.0)
        return floatArrayOf(sin(half).toFloat(), 0f, 0f)
    }
}
