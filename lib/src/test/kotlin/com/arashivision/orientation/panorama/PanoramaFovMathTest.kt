package com.arashivision.orientation.panorama

import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanoramaFovMathTest {

    @Test
    fun targetAtGazeDirectionIsInsideFovAndHasNoArrow() {
        val state = PanoramaFovMath.resolveTarget(
            gaze = direction(yaw = 0.0, pitch = 0.0),
            target = direction(yaw = 0.0, pitch = 0.0),
            horizontalFovRad = HALF_PI,
            verticalFovRad = HALF_PI
        )

        assertTrue(state.isInsideFov)
        assertEquals(0.0, state.yawDeltaRad, EPSILON)
        assertEquals(0.0, state.pitchDeltaRad, EPSILON)
        assertNull(state.arrowAngleRad)
    }

    @Test
    fun targetOutsideRightSidePointsArrowRight() {
        val state = PanoramaFovMath.resolveTarget(
            gaze = direction(yaw = 0.0, pitch = 0.0),
            target = direction(yaw = HALF_PI, pitch = 0.0),
            horizontalFovRad = HALF_PI,
            verticalFovRad = HALF_PI
        )

        assertFalse(state.isInsideFov)
        assertEquals(HALF_PI, state.yawDeltaRad, EPSILON)
        assertEquals(0.0, state.arrowAngleRad ?: Double.NaN, EPSILON)
    }

    @Test
    fun targetOutsideAbovePointsArrowUp() {
        val state = PanoramaFovMath.resolveTarget(
            gaze = direction(yaw = 0.0, pitch = 0.0),
            target = direction(yaw = 0.0, pitch = HALF_PI),
            horizontalFovRad = HALF_PI,
            verticalFovRad = HALF_PI
        )

        assertFalse(state.isInsideFov)
        assertEquals(-HALF_PI, state.arrowAngleRad ?: Double.NaN, EPSILON)
    }

    @Test
    fun yawDeltaWrapsAcrossPanoramaSeam() {
        val state = PanoramaFovMath.resolveTarget(
            gaze = direction(yaw = Math.toRadians(170.0), pitch = 0.0),
            target = direction(yaw = Math.toRadians(-170.0), pitch = 0.0),
            horizontalFovRad = Math.toRadians(60.0),
            verticalFovRad = HALF_PI
        )

        assertTrue(state.isInsideFov)
        assertEquals(Math.toRadians(20.0), state.yawDeltaRad, EPSILON)
    }

    // --- Quaternion-based resolveTargetQuat tests ---

    @Test
    fun quat_targetAtGazeDirectionIsInsideFovAndHasNoArrow() {
        val state = PanoramaFovMath.resolveTargetQuat(
            gaze = direction(yaw = 0.0, pitch = 0.0),
            target = direction(yaw = 0.0, pitch = 0.0),
            horizontalFovRad = HALF_PI,
            verticalFovRad = HALF_PI
        )

        assertTrue(state.isInsideFov)
        assertEquals(0.0, state.yawDeltaRad, EPSILON)
        assertEquals(0.0, state.pitchDeltaRad, EPSILON)
        assertNull(state.arrowAngleRad)
    }

    @Test
    fun quat_targetOutsideRightSidePointsArrowRight() {
        val state = PanoramaFovMath.resolveTargetQuat(
            gaze = direction(yaw = 0.0, pitch = 0.0),
            target = direction(yaw = HALF_PI, pitch = 0.0),
            horizontalFovRad = HALF_PI,
            verticalFovRad = HALF_PI
        )

        assertFalse(state.isInsideFov)
        assertEquals(HALF_PI, state.yawDeltaRad, EPSILON)
        assertEquals(0.0, state.arrowAngleRad ?: Double.NaN, EPSILON)
    }

    @Test
    fun quat_targetOutsideAbovePointsArrowUp() {
        val state = PanoramaFovMath.resolveTargetQuat(
            gaze = direction(yaw = 0.0, pitch = 0.0),
            target = direction(yaw = 0.0, pitch = HALF_PI),
            horizontalFovRad = HALF_PI,
            verticalFovRad = HALF_PI
        )

        assertFalse(state.isInsideFov)
        assertEquals(-HALF_PI, state.arrowAngleRad ?: Double.NaN, EPSILON)
    }

    @Test
    fun quat_yawDeltaWrapsAcrossPanoramaSeam() {
        val state = PanoramaFovMath.resolveTargetQuat(
            gaze = direction(yaw = Math.toRadians(170.0), pitch = 0.0),
            target = direction(yaw = Math.toRadians(-170.0), pitch = 0.0),
            horizontalFovRad = Math.toRadians(60.0),
            verticalFovRad = HALF_PI
        )

        // The shortest arc from 170° to -170° is 20° across the seam
        assertTrue(state.isInsideFov)
        assertEquals(Math.toRadians(20.0), state.yawDeltaRad, EPSILON)
    }

    @Test
    fun quat_targetBehindViewerIsOutsideFov() {
        // Gaze at (0,0), target behind near yaw=PI. Should be outside FOV with
        // a valid arrow angle — either near 0 (right turn around) or near PI (left turn).
        val state = PanoramaFovMath.resolveTargetQuat(
            gaze = direction(yaw = 0.0, pitch = 0.0),
            target = direction(yaw = Math.toRadians(179.0), pitch = 0.0),
            horizontalFovRad = HALF_PI,
            verticalFovRad = HALF_PI
        )

        assertFalse(state.isInsideFov)
        val angle = state.arrowAngleRad ?: error("Expected arrow angle")
        // For a target at yaw≈PI (179°), the shortest yaw delta is +179° → arrow points right (≈0).
        assertTrue("Arrow should point roughly right (angle ≈ 0), got angle=$angle",
            abs(angle) < 0.1)
    }

    @Test
    fun quat_targetUpAndRightPointsArrowUpRight() {
        // Target at yaw=45°, pitch=30° — should produce an up-right arrow angle.
        val state = PanoramaFovMath.resolveTargetQuat(
            gaze = direction(yaw = 0.0, pitch = 0.0),
            target = direction(yaw = Math.toRadians(45.0), pitch = Math.toRadians(30.0)),
            horizontalFovRad = Math.toRadians(30.0),  // small FOV so it's outside
            verticalFovRad = Math.toRadians(30.0)
        )

        assertFalse(state.isInsideFov)
        val angle = state.arrowAngleRad ?: error("Expected arrow angle")
        // Should be between -PI/2 (up) and 0 (right)
        assertTrue("Arrow should point up-right, got angle=$angle",
            angle < 0.0 && angle > -HALF_PI)
    }

    @Test
    fun quat_and_euler_variants_agree_for_simple_case() {
        // For a baseline gaze at (0,0), both methods should produce identical results.
        val gaze = direction(yaw = 0.0, pitch = 0.0)
        val target = direction(yaw = Math.toRadians(60.0), pitch = Math.toRadians(20.0))

        val eulerResult = PanoramaFovMath.resolveTarget(gaze, target, HALF_PI, HALF_PI)
        val quatResult = PanoramaFovMath.resolveTargetQuat(gaze, target, HALF_PI, HALF_PI)

        assertEquals(eulerResult.isInsideFov, quatResult.isInsideFov)
        assertEquals(eulerResult.yawDeltaRad, quatResult.yawDeltaRad, 1e-6)
        assertEquals(eulerResult.pitchDeltaRad, quatResult.pitchDeltaRad, 1e-6)
        if (eulerResult.arrowAngleRad != null && quatResult.arrowAngleRad != null) {
            assertEquals(eulerResult.arrowAngleRad, quatResult.arrowAngleRad, 1e-6)
        }
    }

    private fun direction(yaw: Double, pitch: Double): PanoramaDirection =
        EquirectangularProjection.fromYawPitch(yaw, pitch)

    private companion object {
        const val EPSILON = 1e-9
        const val HALF_PI = PI / 2.0
    }
}
