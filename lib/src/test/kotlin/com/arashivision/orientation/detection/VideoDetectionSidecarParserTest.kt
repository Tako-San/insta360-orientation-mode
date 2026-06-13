package com.arashivision.orientation.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDetectionSidecarParserTest {

    private val parser = VideoDetectionSidecarParser()

    private val frameObj = """
        {
          "frame_idx": 198,
          "time_sec": 6.6066,
          "objects": [
            {
              "track_id": 2,
              "bbox_xyxy": [1204.0, 270.0, 1243.0, 283.0],
              "center_xy": [1223.5, 276.5],
              "center_norm": [0.955859, 0.432031]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesArrayRoot() {
        val sidecar = parser.parse("[$frameObj]")
        assertEquals(1, sidecar.frameCount)
        val f = sidecar.frames[0]
        assertEquals(198, f.frameIdx)
        assertEquals(6.6066, f.timeSec, 1e-9)
        assertEquals(1, f.objects.size)
        assertEquals(2, f.objects[0].trackId)
        assertEquals(1204.0, f.objects[0].bboxXyxy.left, 1e-9)
        assertEquals(0.955859, f.objects[0].centerNorm.x, 1e-9)
    }

    @Test
    fun parsesSingleObjectRoot() {
        val sidecar = parser.parse(frameObj)
        assertEquals(1, sidecar.frameCount)
        assertEquals(198, sidecar.frames[0].frameIdx)
    }

    @Test
    fun parsesFramesWrapperKey() {
        val sidecar = parser.parse("""{"frames": [$frameObj]}""")
        assertEquals(1, sidecar.frameCount)
    }

    @Test
    fun parsesDetectionsWrapperKey() {
        val sidecar = parser.parse("""{"detections": [$frameObj]}""")
        assertEquals(1, sidecar.frameCount)
    }

    @Test
    fun parsesDataWrapperKey() {
        val sidecar = parser.parse("""{"data": [$frameObj]}""")
        assertEquals(1, sidecar.frameCount)
    }

    @Test
    fun parsesBareCommaSeparatedSequenceWithTrailingComma() {
        val f1 = frameObj
        val f2 = frameObj.replace("\"frame_idx\": 198", "\"frame_idx\": 199")
            .replace("6.6066", "6.7")
        val sidecar = parser.parse("$f1,\n$f2,")
        assertEquals(2, sidecar.frameCount)
    }

    @Test
    fun sortsByTimeThenFrameIdx() {
        val late = frameObj.replace("\"frame_idx\": 198", "\"frame_idx\": 10").replace("6.6066", "9.0")
        val early = frameObj.replace("\"frame_idx\": 198", "\"frame_idx\": 20").replace("6.6066", "1.0")
        val sidecar = parser.parse("[$late, $early]")
        assertEquals(20, sidecar.frames[0].frameIdx) // time 1.0 is earlier than 9.0
        assertEquals(10, sidecar.frames[1].frameIdx)
    }

    @Test
    fun frameWithoutObjectsGivesEmptyList() {
        val noObjects = """{"frame_idx": 5, "time_sec": 1.0}"""
        val sidecar = parser.parse(noObjects)
        assertTrue(sidecar.frames[0].objects.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyInputThrows() {
        parser.parse("   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidBboxLengthThrows() {
        val bad = """{"frame_idx": 1, "time_sec": 1.0, "objects": [
            {"track_id": 1, "bbox_xyxy": [1.0, 2.0, 3.0], "center_xy": [1.0,2.0], "center_norm": [0.1,0.2]}
        ]}"""
        parser.parse(bad)
    }
}
