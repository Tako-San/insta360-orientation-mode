package com.arashivision.orientation.detection

import kotlin.math.abs

/**
 * Time-indexed access layer for detection sidecar frames.
 *
 * The offline player can call [frameAt] or [detectionsAt] with ExoPlayer.currentPosition to get
 * metadata for the current playback time without coupling playback code to JSON parsing details.
 */
class VideoDetectionTimeline(
    sidecar: VideoDetectionSidecar
) {
    private val frames: List<VideoDetectionFrame> = sidecar.frames

    val frameCount: Int = frames.size

    fun frameAt(positionMs: Long): VideoDetectionFrame? {
        if (frames.isEmpty()) return null

        val positionSec = positionMs / 1000.0
        val insertionPoint = frames.binarySearchBy(positionSec) { it.timeSec }.let { result ->
            if (result >= 0) result else -result - 1
        }

        val previous = frames.getOrNull(insertionPoint - 1)
        val next = frames.getOrNull(insertionPoint)

        return when {
            previous == null -> next
            next == null -> previous
            abs(positionSec - previous.timeSec) <= abs(next.timeSec - positionSec) -> previous
            else -> next
        }
    }

    fun detectionsAt(positionMs: Long): List<VideoDetectedObject> =
        frameAt(positionMs)?.objects.orEmpty()

    fun frameByIndex(frameIdx: Int): VideoDetectionFrame? =
        frames.firstOrNull { it.frameIdx == frameIdx }
}
