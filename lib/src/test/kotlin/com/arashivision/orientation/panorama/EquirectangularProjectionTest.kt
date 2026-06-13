package com.arashivision.orientation.panorama

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Test

class EquirectangularProjectionTest {

    @Test
    fun centerMapsToZeroYawPitchForwardVectorAndIdentityQuaternion() {
        val direction = EquirectangularProjection.fromPixel(x = WIDTH / 2.0, y = HEIGHT / 2.0, WIDTH, HEIGHT)

        assertEquals(0.0, direction.yawRad, EPSILON)
        assertEquals(0.0, direction.pitchRad, EPSILON)
        assertVectorEquals(UnitVector3(1.0, 0.0, 0.0), direction.unitVector)
        assertQuaternionEquals(UnitQuaternion.IDENTITY, direction.orientation)
    }

    @Test
    fun horizontalEdgesMapToMinusAndPlusHalfTurnYaw() {
        val left = EquirectangularProjection.fromPixel(x = 0.0, y = HEIGHT / 2.0, WIDTH, HEIGHT)
        val right = EquirectangularProjection.fromPixel(x = WIDTH.toDouble(), y = HEIGHT / 2.0, WIDTH, HEIGHT)

        assertEquals(-PI, left.yawRad, EPSILON)
        assertEquals(PI, right.yawRad, EPSILON)
        assertEquals(0.0, left.pitchRad, EPSILON)
        assertEquals(0.0, right.pitchRad, EPSILON)
        assertVectorEquals(UnitVector3(-1.0, 0.0, 0.0), left.unitVector)
        assertVectorEquals(UnitVector3(-1.0, 0.0, 0.0), right.unitVector)
    }

    @Test
    fun verticalEdgesMapToUpAndDownPitch() {
        val top = EquirectangularProjection.fromPixel(x = WIDTH / 2.0, y = 0.0, WIDTH, HEIGHT)
        val bottom = EquirectangularProjection.fromPixel(x = WIDTH / 2.0, y = HEIGHT.toDouble(), WIDTH, HEIGHT)

        assertEquals(0.0, top.yawRad, EPSILON)
        assertEquals(PI / 2.0, top.pitchRad, EPSILON)
        assertVectorEquals(UnitVector3(0.0, 0.0, 1.0), top.unitVector)

        assertEquals(0.0, bottom.yawRad, EPSILON)
        assertEquals(-PI / 2.0, bottom.pitchRad, EPSILON)
        assertVectorEquals(UnitVector3(0.0, 0.0, -1.0), bottom.unitVector)
    }

    @Test
    fun cornersKeepYawAndPitchWhileVectorsPointToPoles() {
        val topLeft = EquirectangularProjection.fromPixel(x = 0.0, y = 0.0, WIDTH, HEIGHT)
        val topRight = EquirectangularProjection.fromPixel(x = WIDTH.toDouble(), y = 0.0, WIDTH, HEIGHT)
        val bottomLeft = EquirectangularProjection.fromPixel(x = 0.0, y = HEIGHT.toDouble(), WIDTH, HEIGHT)
        val bottomRight = EquirectangularProjection.fromPixel(x = WIDTH.toDouble(), y = HEIGHT.toDouble(), WIDTH, HEIGHT)

        assertEquals(-PI, topLeft.yawRad, EPSILON)
        assertEquals(PI, topRight.yawRad, EPSILON)
        assertEquals(-PI, bottomLeft.yawRad, EPSILON)
        assertEquals(PI, bottomRight.yawRad, EPSILON)

        assertEquals(PI / 2.0, topLeft.pitchRad, EPSILON)
        assertEquals(PI / 2.0, topRight.pitchRad, EPSILON)
        assertEquals(-PI / 2.0, bottomLeft.pitchRad, EPSILON)
        assertEquals(-PI / 2.0, bottomRight.pitchRad, EPSILON)

        assertVectorEquals(UnitVector3(0.0, 0.0, 1.0), topLeft.unitVector)
        assertVectorEquals(UnitVector3(0.0, 0.0, 1.0), topRight.unitVector)
        assertVectorEquals(UnitVector3(0.0, 0.0, -1.0), bottomLeft.unitVector)
        assertVectorEquals(UnitVector3(0.0, 0.0, -1.0), bottomRight.unitVector)
    }

    @Test
    fun quaternionRotatesForwardVectorToComputedUnitVector() {
        val direction = EquirectangularProjection.fromNormalized(x = 0.75, y = 0.25)
        val rotatedForward = direction.orientation.rotate(FORWARD)

        assertEquals(90.0, direction.yawDeg, EPSILON)
        assertEquals(45.0, direction.pitchDeg, EPSILON)
        assertVectorEquals(direction.unitVector, rotatedForward)
    }

    private fun assertVectorEquals(expected: UnitVector3, actual: UnitVector3) {
        assertEquals(expected.x, actual.x, EPSILON)
        assertEquals(expected.y, actual.y, EPSILON)
        assertEquals(expected.z, actual.z, EPSILON)
    }

    private fun assertQuaternionEquals(expected: UnitQuaternion, actual: UnitQuaternion) {
        assertEquals(expected.x, actual.x, EPSILON)
        assertEquals(expected.y, actual.y, EPSILON)
        assertEquals(expected.z, actual.z, EPSILON)
        assertEquals(expected.w, actual.w, EPSILON)
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 640
        const val EPSILON = 1e-9
        val FORWARD = UnitVector3(1.0, 0.0, 0.0)
    }
}
