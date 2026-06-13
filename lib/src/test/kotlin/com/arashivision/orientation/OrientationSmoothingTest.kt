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
        // дрожание 0.2° — должно почти не сдвинуть сглаженное значение
        val r = s.update(0.2f, 0.2f)
        // alpha при дельте 0.2: 0.15 + 0.85*(0.2/2.5) = 0.218 → сдвиг ~0.0436°
        assertTrue("jitter must be damped, got ${r.yawDeg}", r.yawDeg < 0.06f)
        assertTrue(r.pitchDeg < 0.06f)
    }

    @Test
    fun `fast movement passes through almost fully`() {
        val s = OrientationSmoothing(alphaMin = 0.15f, alphaMax = 1.0f, speedFullDeg = 2.5f)
        s.update(0f, 0f)
        // резкий поворот на 30° — дельта >> speedFull → alpha=1.0 → проходит целиком
        val r = s.update(30f, 0f)
        assertEquals(30f, r.yawDeg, eps)
    }

    @Test
    fun `adaptiveAlpha clamps between min and max`() {
        val s = OrientationSmoothing(alphaMin = 0.15f, alphaMax = 1.0f, speedFullDeg = 2.5f)
        assertEquals(0.15f, s.adaptiveAlpha(0f), eps)
        assertEquals(1.0f, s.adaptiveAlpha(2.5f), eps)
        assertEquals(1.0f, s.adaptiveAlpha(100f), eps)   // сверх speedFull — всё равно max
        assertEquals(1.0f, s.adaptiveAlpha(-100f), eps)  // знак не важен
    }

    @Test
    fun `adaptiveAlpha is linear at half speed`() {
        val s = OrientationSmoothing(alphaMin = 0.2f, alphaMax = 1.0f, speedFullDeg = 4f)
        // при дельте 2° (половина speedFull): 0.2 + 0.8*0.5 = 0.6
        assertEquals(0.6f, s.adaptiveAlpha(2f), eps)
    }

    @Test
    fun `yaw wraps across plus minus 180 boundary`() {
        val s = OrientationSmoothing(alphaMin = 1.0f, alphaMax = 1.0f, speedFullDeg = 2.5f)
        s.update(179f, 0f)
        // переход 179° → -179° это +2° по кратчайшему пути, а не -358°
        val r = s.update(-179f, 0f)
        // при alpha=1 сглаженное = 179 + 2 = 181 (не wrap'нуто наружу, но движение верное)
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
        // держим 1° — много тиков подряд, должно медленно подойти к 1°
        var last = 0f
        repeat(200) { last = s.update(1f, 0f).yawDeg }
        assertEquals(1f, last, 0.01f)
    }
}
