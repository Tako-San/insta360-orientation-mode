package com.arashivision.orientation.detection

import com.arashivision.orientation.panorama.EquirectangularProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionArrowResolverTest {
    private val hFov = Math.toRadians(60.0)
    private val vFov = Math.toRadians(45.0)

    private fun obj(normX: Double, normY: Double, id: Int = 1) = VideoDetectedObject(
        trackId = id,
        bboxXyxy = BboxXyxy(0.0, 0.0, 0.0, 0.0),
        centerXy = Point2d(0.0, 0.0),
        centerNorm = Point2d(normX, normY)
    )

    @Test
    fun `empty detections hide the arrow`() {
        val gaze = EquirectangularProjection.fromYawPitch(0.0, 0.0)
        val s = DetectionArrowResolver.resolve(emptyList(), gaze, hFov, vFov)
        assertFalse(s.visible)
        assertNull(s.angleRad)
        assertEquals(ArrowState.HIDDEN, s)
    }

    @Test
    fun `target dead ahead is inside fov so arrow hidden`() {
        // gaze смотрит в центр (yaw0/pitch0). Цель в центре эквирект-картинки (0.5,0.5) = вперёд.
        val gaze = EquirectangularProjection.fromYawPitch(0.0, 0.0)
        val s = DetectionArrowResolver.resolve(listOf(obj(0.5, 0.5)), gaze, hFov, vFov)
        assertFalse("target ahead must be inside FOV", s.visible)
    }

    @Test
    fun `target far to the side shows arrow with angle`() {
        val gaze = EquirectangularProjection.fromYawPitch(0.0, 0.0)
        // цель у левого края панорамы (normX≈0.0) — далеко вне 60° FOV
        val s = DetectionArrowResolver.resolve(listOf(obj(0.02, 0.5)), gaze, hFov, vFov)
        assertTrue("target to the side must be outside FOV", s.visible)
        assertNotNull(s.angleRad)
    }

    @Test
    fun `picks first detection that is outside fov`() {
        val gaze = EquirectangularProjection.fromYawPitch(0.0, 0.0)
        // первая цель — в центре (внутри FOV), вторая — сбоку (вне). Резолвер должен выбрать вторую.
        val s = DetectionArrowResolver.resolve(
            listOf(obj(0.5, 0.5, id = 1), obj(0.02, 0.5, id = 2)),
            gaze, hFov, vFov
        )
        assertTrue(s.visible)
        assertNotNull(s.angleRad)
    }
}
