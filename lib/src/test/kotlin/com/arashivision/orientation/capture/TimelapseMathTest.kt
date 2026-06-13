package com.arashivision.orientation.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelapseMathTest {

    @Test
    fun `computes finished video duration`() {
        // captureTime=60000ms, interval=2, fps=30 → ((60000/2)/30)*1000 = 1_000_000
        assertEquals(1_000_000L, TimelapseMath.videoDurationMs(60_000, 2, 30))
    }

    @Test
    fun `uses integer division like the source`() {
        // (1000/3)=333, /30=11, *1000 = 11000
        assertEquals(11_000L, TimelapseMath.videoDurationMs(1000, 3, 30))
    }

    @Test
    fun `zero interval returns zero (no divide by zero)`() {
        assertEquals(0L, TimelapseMath.videoDurationMs(60_000, 0, 30))
    }

    @Test
    fun `zero fps returns zero (no divide by zero)`() {
        assertEquals(0L, TimelapseMath.videoDurationMs(60_000, 2, 0))
    }

    @Test
    fun `negative inputs return zero`() {
        assertEquals(0L, TimelapseMath.videoDurationMs(60_000, -1, 30))
        assertEquals(0L, TimelapseMath.videoDurationMs(60_000, 2, -5))
    }

    @Test
    fun `zero capture time yields zero`() {
        assertEquals(0L, TimelapseMath.videoDurationMs(0, 2, 30))
    }
}
