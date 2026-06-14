package com.panorama.core.detection

/** Port the renderer queries for detections at the current playback position. Pure-JVM so the
 *  arrow logic can be tested without Android: the sidecar adapter is one implementation, a live
 *  on-device detector would be another. [available] lets the runtime skip arrow work entirely
 *  when no detection data exists. */
interface DetectionSource {
    fun detectionsAt(positionMs: Long): List<Detection>
    val available: Boolean
}
