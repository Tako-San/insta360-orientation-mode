package com.arashivision.orientation.detection

/**
 * Parsed sidecar JSON for one panoramic video.
 *
 * The coordinate fields intentionally keep the source equirectangular-space values from the
 * JSON file. Mapping these coordinates to the visible viewport is the next layer and can be
 * added without changing the offline player.
 */
data class VideoDetectionSidecar(
    val frames: List<VideoDetectionFrame>
) {
    val frameCount: Int = frames.size
}

data class VideoDetectionFrame(
    val frameIdx: Int,
    val timeSec: Double,
    val objects: List<VideoDetectedObject>
)

data class VideoDetectedObject(
    val trackId: Int,
    val bboxXyxy: BboxXyxy,
    val centerXy: Point2d,
    val centerNorm: Point2d
)

data class BboxXyxy(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double
)

data class Point2d(
    val x: Double,
    val y: Double
)
