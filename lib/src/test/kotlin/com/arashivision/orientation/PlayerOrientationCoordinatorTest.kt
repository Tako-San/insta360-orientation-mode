package com.arashivision.orientation

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerOrientationCoordinatorTest {
    private val eps = 1e-3f

    @Test
    fun `inverts both signs on first frame`() {
        // alphaMin=alphaMax=1 → без сглаживания, чистая инверсия
        val c = PlayerOrientationCoordinator(OrientationSmoothing(alphaMin = 1f, alphaMax = 1f))
        val r = c.coordinate(rawGazeYawDeg = 30f, rawGazePitchDeg = 10f)
        assertEquals(-30f, r.yawDeg, eps)
        assertEquals(-10f, r.pitchDeg, eps)
    }

    @Test
    fun `applies smoothing on rest jitter`() {
        val c = PlayerOrientationCoordinator(
            OrientationSmoothing(alphaMin = 0.15f, alphaMax = 1f, speedFullDeg = 2.5f)
        )
        c.coordinate(0f, 0f)                 // init
        val r = c.coordinate(0.2f, 0.2f)     // tiny jitter (inverted to -0.2)
        // должно быть сильно задемпфировано (|значение| мало)
        assert(kotlin.math.abs(r.yawDeg) < 0.06f) { "jitter not damped: ${r.yawDeg}" }
    }

    @Test
    fun `reset reinitializes smoothing`() {
        val c = PlayerOrientationCoordinator(OrientationSmoothing(alphaMin = 0.15f, alphaMax = 1f))
        c.coordinate(10f, 10f)
        c.reset()
        // alphaMin маленький, но после reset первый кадр проходит как есть (инвертированный)
        val r = c.coordinate(50f, 0f)
        assertEquals(-50f, r.yawDeg, eps)
    }

    @Test
    fun `fast movement passes through inverted`() {
        val c = PlayerOrientationCoordinator(
            OrientationSmoothing(alphaMin = 0.15f, alphaMax = 1f, speedFullDeg = 2.5f)
        )
        c.coordinate(0f, 0f)
        val r = c.coordinate(40f, 0f)  // big move → alpha≈1
        assertEquals(-40f, r.yawDeg, eps)
    }
}
