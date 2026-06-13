package com.arashivision.orientation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationProcessorTest {
    private val eps = 1e-3f
    private fun ident() = Quaternion(1f, 0f, 0f, 0f)

    private fun so(yaw: Float, pitch: Float, roll: Float = 0f, q: Quaternion = ident()) =
        SensorOrientation(q, yaw, pitch, roll)

    @Test
    fun `gaze yaw is relative to calibration and wraps`() {
        val p = OrientationProcessor()
        p.process(so(yaw = 100f, pitch = 0f), displayRotation = 0, now = 0L)
        p.calibrate()
        p.process(so(yaw = 130f, pitch = 0f), displayRotation = 0, now = 16L)
        assertEquals(30f, p.gazeYawDeg(), eps)
    }

    @Test
    fun `gaze yaw wraps across plus minus 180`() {
        val p = OrientationProcessor()
        p.process(so(yaw = 170f, pitch = 0f), displayRotation = 0, now = 0L)
        p.calibrate()
        p.process(so(yaw = -170f, pitch = 0f), displayRotation = 0, now = 16L)
        // 170 -> -170 is +20 the short way
        assertEquals(20f, p.gazeYawDeg(), eps)
    }

    @Test
    fun `gaze pitch is negated relative to calibration`() {
        val p = OrientationProcessor()
        p.process(so(yaw = 0f, pitch = 10f), displayRotation = 0, now = 0L)
        p.calibrate()
        p.process(so(yaw = 0f, pitch = 25f), displayRotation = 0, now = 16L)
        // gaze pitch = -(raw - offset) = -(25 - 10) = -15
        assertEquals(-15f, p.gazePitchDeg(), eps)
    }

    @Test
    fun `before calibration gaze yaw equals raw (offset zero)`() {
        val p = OrientationProcessor()
        p.process(so(yaw = 42f, pitch = 0f), displayRotation = 0, now = 0L)
        assertEquals(42f, p.gazeYawDeg(), eps)
    }

    @Test
    fun `rate limit skips full processing but keeps raw fresh`() {
        val p = OrientationProcessor(rateLimitMs = 100L)
        p.process(so(yaw = 0f, pitch = 0f), displayRotation = 0, now = 0L)
        // within rate limit window: raw still updates so gaze stays live
        val handled = p.process(so(yaw = 50f, pitch = 0f), displayRotation = 0, now = 10L)
        assertTrue("second frame should be rate-limited", !handled)
        assertEquals(50f, p.gazeYawDeg(), eps) // raw refreshed even when rate-limited
    }

    @Test
    fun `process returns true when frame is fully handled`() {
        val p = OrientationProcessor(rateLimitMs = 0L)
        assertTrue(p.process(so(yaw = 0f, pitch = 0f), displayRotation = 0, now = 0L))
        assertTrue(p.process(so(yaw = 1f, pitch = 0f), displayRotation = 0, now = 1L))
    }

    @Test
    fun `calibrate resets relative euler to zero`() {
        val p = OrientationProcessor()
        val q = Quaternion(0.9f, 0f, 0.43f, 0f).normalize()
        p.process(so(yaw = 80f, pitch = 5f, q = q), displayRotation = 0, now = 0L)
        p.calibrate()
        // immediately after calibration, relative euler ~ 0
        p.process(so(yaw = 80f, pitch = 5f, q = q), displayRotation = 0, now = 16L)
        assertEquals(0f, p.rawEulerYawDeg(), 1f)
        assertEquals(0f, p.rawEulerPitchDeg(), 1f)
    }
}
