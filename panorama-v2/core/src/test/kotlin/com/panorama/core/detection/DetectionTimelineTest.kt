package com.panorama.core.detection

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Pins the nearest-frame lookup: positions before the first / after the last frame clamp to the
 *  ends, an in-between position snaps to the nearest timeMs, and an empty timeline yields no
 *  detections (the runtime asks the timeline every frame; it must answer for any position). */
class DetectionTimelineTest : FunSpec({

    fun frame(timeMs: Long, label: String) =
        SidecarFrame(timeMs, listOf(SidecarObject(listOf(0.5f, 0.5f), null, label)))

    val timeline = DetectionTimeline(Sidecar(listOf(frame(100, "a"), frame(200, "b"))))

    test("position before first frame clamps to first") {
        timeline.frameAt(0).timeMs shouldBe 100L
        timeline.detectionsAt(0)[0].label shouldBe "a"
    }

    test("position after last frame clamps to last") {
        timeline.frameAt(9999).timeMs shouldBe 200L
        timeline.detectionsAt(9999)[0].label shouldBe "b"
    }

    test("in-between position snaps to nearest frame") {
        // 120 is closer to 100 than to 200.
        timeline.frameAt(120).timeMs shouldBe 100L
        // 160 is closer to 200.
        timeline.frameAt(160).timeMs shouldBe 200L
    }

    test("empty timeline yields no detections") {
        val empty = DetectionTimeline(Sidecar(emptyList()))
        empty.detectionsAt(50) shouldBe emptyList()
    }

    test("wire bbox is carried into the domain Rect, absent bbox stays null") {
        val withBox = SidecarObject(listOf(0.5f, 0.5f), listOf(0.1f, 0.2f, 0.3f, 0.4f), "drone")
        val withoutBox = SidecarObject(listOf(0.5f, 0.5f), null, "drone")
        val tl = DetectionTimeline(
            Sidecar(listOf(SidecarFrame(0, listOf(withBox)), SidecarFrame(100, listOf(withoutBox)))),
        )
        tl.detectionsAt(0)[0].bboxNorm shouldBe Rect(0.1f, 0.2f, 0.3f, 0.4f)
        tl.detectionsAt(100)[0].bboxNorm shouldBe null
    }
})
