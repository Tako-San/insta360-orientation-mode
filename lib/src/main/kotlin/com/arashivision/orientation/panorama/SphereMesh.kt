package com.arashivision.orientation.panorama

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/** Interleave-free mesh buffers for an equirectangular UV sphere. */
data class SphereMeshData(
    val positions: FloatArray,  // x,y,z per vertex
    val texCoords: FloatArray,  // u,v per vertex
    val indices: ShortArray     // triangle list
)

/**
 * Pure (JVM) generator of an inward-facing equirectangular UV sphere. The camera sits at the
 * origin and looks at the inner surface, so the triangle winding is set for inward faces.
 * Coordinate system matches the :lib UnitQuaternion: +X forward, +Y right, +Z up.
 */
object SphereMesh {
    fun generate(stacks: Int = 32, slices: Int = 64, radius: Float = 1f): SphereMeshData {
        val positions = FloatArray((stacks + 1) * (slices + 1) * 3)
        val texCoords = FloatArray((stacks + 1) * (slices + 1) * 2)
        var p = 0
        var t = 0
        for (i in 0..stacks) {
            val v = i.toFloat() / stacks          // 0..1 top -> bottom
            val phi = (v * PI).toFloat()          // polar angle 0..PI
            for (j in 0..slices) {
                val u = j.toFloat() / slices      // 0..1 around
                val theta = (u * 2.0 * PI).toFloat()
                // +Z up sphere; longitude around Z, latitude from the +Z pole.
                val sinPhi = sin(phi); val cosPhi = cos(phi)
                val x = radius * sinPhi * cos(theta)
                val y = radius * sinPhi * sin(theta)
                val z = radius * cosPhi
                positions[p++] = x; positions[p++] = y; positions[p++] = z
                texCoords[t++] = u; texCoords[t++] = v
            }
        }
        val indices = ShortArray(stacks * slices * 6)
        var idx = 0
        val stride = slices + 1
        for (i in 0 until stacks) {
            for (j in 0 until slices) {
                val a = (i * stride + j).toShort()
                val b = (i * stride + j + 1).toShort()
                val c = ((i + 1) * stride + j).toShort()
                val d = ((i + 1) * stride + j + 1).toShort()
                // inward-facing winding
                indices[idx++] = a; indices[idx++] = c; indices[idx++] = b
                indices[idx++] = b; indices[idx++] = c; indices[idx++] = d
            }
        }
        return SphereMeshData(positions, texCoords, indices)
    }
}
