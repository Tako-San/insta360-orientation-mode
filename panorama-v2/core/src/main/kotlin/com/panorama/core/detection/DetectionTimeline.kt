package com.panorama.core.detection

import dev.romainguy.kotlin.math.Float2

/** Random-access lookup over a parsed [Sidecar]. The runtime queries it once per rendered frame
 *  with the current playback position, so the lookup is a binary search for the nearest frame by
 *  timeMs (frames are assumed time-ordered, as produced by the offline detector).
 *
 *  Positions before the first / after the last frame clamp to the ends. An empty sidecar answers
 *  every query with no detections. */
class DetectionTimeline(private val sidecar: Sidecar) {

    private val frames: List<SidecarFrame> = sidecar.frames

    val isEmpty: Boolean get() = frames.isEmpty()

    /** The frame whose timeMs is nearest to [positionMs]. Caller must ensure the timeline is
     *  non-empty (guarded here only via [detectionsAt] for the empty case). */
    fun frameAt(positionMs: Long): SidecarFrame {
        // Binary search for the insertion point of positionMs among the frame times.
        var lo = 0
        var hi = frames.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (frames[mid].timeMs < positionMs) lo = mid + 1 else hi = mid
        }
        // lo is the first frame with timeMs >= positionMs (or the last frame).
        val hiIdx = lo
        val loIdx = if (lo > 0) lo - 1 else 0
        val distHi = kotlin.math.abs(frames[hiIdx].timeMs - positionMs)
        val distLo = kotlin.math.abs(frames[loIdx].timeMs - positionMs)
        return if (distLo <= distHi) frames[loIdx] else frames[hiIdx]
    }

    /** Domain detections at the frame nearest to [positionMs]; empty list if the timeline is empty. */
    fun detectionsAt(positionMs: Long): List<Detection> {
        if (frames.isEmpty()) return emptyList()
        return frameAt(positionMs).objects.map { it.toDetection() }
    }
}

private fun SidecarObject.toDetection(): Detection {
    val cx = centerNorm.getOrElse(0) { 0f }
    val cy = centerNorm.getOrElse(1) { 0f }
    val rect = bboxNorm?.takeIf { it.size >= 4 }?.let { Rect(it[0], it[1], it[2], it[3]) }
    return Detection(Float2(cx, cy), rect, label)
}
