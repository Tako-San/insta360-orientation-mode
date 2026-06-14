package com.panorama.core.detection

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Pins the port adapter over a sidecar timeline: a populated timeline reports available and
 *  returns the nearest frame's detections; an empty sidecar reports unavailable and returns
 *  nothing, so the runtime can fall back to no-arrow with a single boolean check. */
class SidecarDetectionSourceTest : FunSpec({

    fun frame(timeMs: Long, label: String) =
        SidecarFrame(timeMs, listOf(SidecarObject(listOf(0.5f, 0.5f), null, label)))

    test("populated timeline is available and returns nearest detections") {
        val timeline = DetectionTimeline(Sidecar(listOf(frame(100, "a"), frame(200, "b"))))
        val source: DetectionSource = SidecarDetectionSource(timeline)
        source.available shouldBe true
        source.detectionsAt(120)[0].label shouldBe "a"
        source.detectionsAt(180)[0].label shouldBe "b"
    }

    test("empty sidecar is unavailable and returns no detections") {
        val source: DetectionSource = SidecarDetectionSource(DetectionTimeline(Sidecar(emptyList())))
        source.available shouldBe false
        source.detectionsAt(50) shouldBe emptyList()
    }
})
