package com.panorama.core.detection

/** [DetectionSource] backed by an offline-detector sidecar [DetectionTimeline]. Available only
 *  when the timeline holds at least one frame, so an absent/empty sidecar disables arrows. */
class SidecarDetectionSource(private val timeline: DetectionTimeline) : DetectionSource {

    override val available: Boolean get() = !timeline.isEmpty

    override fun detectionsAt(positionMs: Long): List<Detection> =
        timeline.detectionsAt(positionMs)
}
