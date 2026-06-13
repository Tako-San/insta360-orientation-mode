package com.arashivision.orientation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationSmoothingTest {

    private val eps = 1e-3f

    @Test
    fun `first update returns input unchanged`() {
        val s = OrientationSmoothing()
        val r = s.update(42f, -17f)
        assertEquals(42f, r.yawDeg, eps)
        assertEquals(-17f, r.pitchDeg, eps)
    }

    @Test
    fun `small jitter on rest is heavily damped`() {
        val s = OrientationSmoothing(alphaMin = 0.15f, alphaMax = 1.0f, speedFullDeg = 2.5f)
        s.update(0f, 0f)
        // jitter of 0.2° — must barely move the smoothed value
        val r = s.update(0.2f, 0.2f)
        // alpha at a delta of 0.2: 0.15 + 0.85*(0.2/2.5) = 0.218 → shift ~0.0436°
        assertTrue("jitter must be damped, got ${r.yawDeg}", r.yawDeg < 0.06f)
        assertTrue(r.pitchDeg < 0.06f)
    }

    @Test
    fun `fast movement passes through almost fully`() {
        val s = OrientationSmoothing(alphaMin = 0.15f, alphaMax = 1.0f, speedFullDeg = 2.5f)
        s.update(0f, 0f)
        // a sharp 30° turn — delta >> speedFull → alpha=1.0 → passes through entirely
        val r = s.update(30f, 0f)
        assertEquals(30f, r.yawDeg, eps)
    }

    @Test
    fun `adaptiveAlpha clamps between min and max`() {
        val s = OrientationSmoothing(alphaMin = 0.15f, alphaMax = 1.0f, speedFullDeg = 2.5f)
        assertEquals(0.15f, s.adaptiveAlpha(0f), eps)
        assertEquals(1.0f, s.adaptiveAlpha(2.5f), eps)
        assertEquals(1.0f, s.adaptiveAlpha(100f), eps)   // above speedFull — still max
        assertEquals(1.0f, s.adaptiveAlpha(-100f), eps)  // the sign does not matter
    }

    @Test
    fun `adaptiveAlpha is linear at half speed`() {
        val s = OrientationSmoothing(alphaMin = 0.2f, alphaMax = 1.0f, speedFullDeg = 4f)
        // at a delta of 2° (half of speedFull): 0.2 + 0.8*0.5 = 0.6
        assertEquals(0.6f, s.adaptiveAlpha(2f), eps)
    }

    @Test
    fun `yaw wraps across plus minus 180 boundary`() {
        val s = OrientationSmoothing(alphaMin = 1.0f, alphaMax = 1.0f, speedFullDeg = 2.5f)
        s.update(179f, 0f)
        // the transition 179° → -179° is +2° along the shortest path, not -358°
        val r = s.update(-179f, 0f)
        // at alpha=1 the smoothed value = 179 + 2 = 181 (not wrapped outward, but the motion is correct)
        assertEquals(181f, r.yawDeg, eps)
    }

    @Test
    fun `reset reinitializes state`() {
        val s = OrientationSmoothing()
        s.update(10f, 10f)
        s.reset()
        val r = s.update(99f, 99f)
        assertEquals(99f, r.yawDeg, eps)
        assertEquals(99f, r.pitchDeg, eps)
    }

    @Test
    fun `repeated rest converges to target`() {
        val s = OrientationSmoothing(alphaMin = 0.15f, alphaMax = 1.0f, speedFullDeg = 2.5f)
        s.update(0f, 0f)
        // hold 1° — many ticks in a row, it must slowly approach 1°
        var last = 0f
        repeat(200) { last = s.update(1f, 0f).yawDeg }
        assertEquals(1f, last, 0.01f)
    }
}
