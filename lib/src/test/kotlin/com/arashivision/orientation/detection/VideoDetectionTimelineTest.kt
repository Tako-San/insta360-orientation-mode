package com.arashivision.orientation.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDetectionTimelineTest {

    private fun frame(idx: Int, t: Double) =
        VideoDetectionFrame(frameIdx = idx, timeSec = t, objects = emptyList())

    private fun timeline(vararg frames: VideoDetectionFrame) =
        VideoDetectionTimeline(VideoDetectionSidecar(frames.toList()))

    @Test
    fun emptyTimelineReturnsNull() {
        assertNull(timeline().frameAt(1000L))
    }

    @Test
    fun returnsNearestFrameByTime() {
        val tl = timeline(frame(0, 0.0), frame(1, 1.0), frame(2, 2.0))
        // 1100ms = 1.1s ближе к 1.0 чем к 2.0
        assertEquals(1, tl.frameAt(1100L)?.frameIdx)
        // 1600ms = 1.6s ближе к 2.0
        assertEquals(2, tl.frameAt(1600L)?.frameIdx)
    }

    @Test
    fun beforeFirstReturnsFirst() {
        val tl = timeline(frame(0, 5.0), frame(1, 6.0))
        assertEquals(0, tl.frameAt(0L)?.frameIdx)
    }

    @Test
    fun afterLastReturnsLast() {
        val tl = timeline(frame(0, 5.0), frame(1, 6.0))
        assertEquals(1, tl.frameAt(999999L)?.frameIdx)
    }

    @Test
    fun detectionsAtEmptyWhenNoFrame() {
        assertTrue(timeline().detectionsAt(0L).isEmpty())
    }

    @Test
    fun frameByIndexFindsByFrameIdx() {
        val tl = timeline(frame(7, 1.0), frame(9, 2.0))
        assertEquals(2.0, tl.frameByIndex(9)?.timeSec ?: 0.0, 1e-9)
        assertNull(tl.frameByIndex(42))
    }
}
