package com.arashivision.orientation.panorama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class SphereMeshTest {
    @Test
    fun `vertex and index counts match stacks and slices`() {
        val m = SphereMesh.generate(stacks = 32, slices = 64, radius = 1f)
        val verts = (32 + 1) * (64 + 1)
        assertEquals(verts * 3, m.positions.size)
        assertEquals(verts * 2, m.texCoords.size)
        assertEquals(32 * 64 * 6, m.indices.size)
    }

    @Test
    fun `all positions lie on the sphere of given radius`() {
        val m = SphereMesh.generate(stacks = 8, slices = 16, radius = 2f)
        var i = 0
        while (i < m.positions.size) {
            val x = m.positions[i]; val y = m.positions[i + 1]; val z = m.positions[i + 2]
            val r = sqrt(x * x + y * y + z * z)
            assertEquals(2f, r, 1e-3f)
            i += 3
        }
    }

    @Test
    fun `texcoords are within unit range`() {
        val m = SphereMesh.generate(stacks = 8, slices = 16)
        for (uv in m.texCoords) assertTrue(uv in -1e-4f..1.0001f)
    }

    @Test
    fun `indices reference valid vertices`() {
        val m = SphereMesh.generate(stacks = 4, slices = 8)
        val vertexCount = (4 + 1) * (8 + 1)
        for (idx in m.indices) assertTrue(idx.toInt() in 0 until vertexCount)
    }
}
