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
 * Pure (JVM) generator of an inward-facing equirectangular UV sphere in the OpenGL eye basis
 * (+X right, +Y up, -Z forward), matching [ViewMatrixMath]. The camera sits at the origin and
 * looks at the inner surface, so the triangle winding is set for inward faces.
 *
 * Parameterization: phi is latitude from the top pole (0..PI), theta is longitude (0..2PI).
 * The top pole (phi=0, V=0) is +Y (up); longitude wraps around the Y axis with forward at -Z.
 * This puts the equirect image's vertical axis on screen-up and its horizontal axis on the
 * horizon — no separate roll correction needed.
 */
object SphereMesh {
    fun generate(stacks: Int = 32, slices: Int = 64, radius: Float = 1f): SphereMeshData {
        val positions = FloatArray((stacks + 1) * (slices + 1) * 3)
        val texCoords = FloatArray((stacks + 1) * (slices + 1) * 2)
        var p = 0
        var t = 0
        for (i in 0..stacks) {
            val v = i.toFloat() / stacks          // 0..1 top -> bottom
            val phi = (v * PI).toFloat()          // latitude 0..PI from the +Y pole
            for (j in 0..slices) {
                val u = j.toFloat() / slices      // 0..1 around
                val theta = (u * 2.0 * PI).toFloat()
                // +Y up sphere; longitude around Y, forward at -Z.
                val sinPhi = sin(phi); val cosPhi = cos(phi)
                val x = radius * sinPhi * sin(theta)
                val y = radius * cosPhi
                val z = -radius * sinPhi * cos(theta)
                positions[p++] = x; positions[p++] = y; positions[p++] = z
                // V flipped: equirect image top (V=0) is the zenith (+Y pole), but the GL
                // texture origin is bottom-left, so map the top pole to texture V=1.
                texCoords[t++] = u; texCoords[t++] = 1f - v
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
