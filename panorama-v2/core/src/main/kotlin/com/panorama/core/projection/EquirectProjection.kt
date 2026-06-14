package com.panorama.core.projection

import com.panorama.core.math.MeshData
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.normalize
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Inverted (camera-inside) equirect UV-sphere in the single canonical base shared with
 *  [com.panorama.core.math.quatFromYawPitch] and ViewCalibration: top vertex on +Y, forward -Z.
 *
 *  No V-flip parameter lives here — the texture vertical flip is owned by the runtime stMatrix in
 *  Phase 2, so this geometry is sign-free: v=0 at the +Y pole, v=1 at the -Y pole, canonically.
 *
 *  Vertices are a (stacks+1) x (slices+1) grid. stack i runs from the +Y pole (i=0) down to the
 *  -Y pole (i=stacks); slice j wraps the longitude from -180 deg (j=0) to +180 deg (j=slices).
 *  Positions are placed so the per-vertex direction is consistent with [directionToTexUv]:
 *  u = j/slices, v = i/stacks. Radius is unit (1.0). */
class EquirectProjection : ProjectionModel {

    override fun buildMesh(stacks: Int, slices: Int): MeshData {
        val rows = stacks + 1
        val cols = slices + 1
        val positions = FloatArray(rows * cols * 3)
        val texCoords = FloatArray(rows * cols * 2)

        var p = 0
        var t = 0
        for (i in 0..stacks) {
            // Polar angle from +Y pole: phi = 0 at +Y, phi = PI at -Y.
            val phi = PI * i / stacks
            val y = cos(phi)
            val sinPhi = sin(phi)
            for (j in 0..slices) {
                // Longitude from -180 deg (j=0) to +180 deg (j=slices).
                val theta = TWO_PI * j / slices - PI
                // x = -sinPhi * sin(theta), z = -sinPhi * cos(theta) so that the canonical
                // forward (0,0,-1) sits at theta=0 -> u=0.5 (see directionToTexUv).
                positions[p++] = (-sinPhi * sin(theta)).toFloat()
                positions[p++] = y.toFloat()
                positions[p++] = (-sinPhi * cos(theta)).toFloat()

                texCoords[t++] = (j.toFloat() / slices)
                texCoords[t++] = (i.toFloat() / stacks)
            }
        }

        val indices = ShortArray(stacks * slices * 6)
        var k = 0
        for (i in 0 until stacks) {
            for (j in 0 until slices) {
                val topLeft = (i * cols + j).toShort()
                val topRight = (i * cols + j + 1).toShort()
                val bottomLeft = ((i + 1) * cols + j).toShort()
                val bottomRight = ((i + 1) * cols + j + 1).toShort()
                // Inward-facing winding (camera is inside the sphere): triangles wound so the
                // inner surface is front-facing. Final visibility is verified on the GPU in Phase 4.
                indices[k++] = topLeft
                indices[k++] = bottomLeft
                indices[k++] = topRight
                indices[k++] = topRight
                indices[k++] = bottomLeft
                indices[k++] = bottomRight
            }
        }

        return MeshData(positions, texCoords, indices)
    }

    override fun directionToTexUv(dir: Float3): Float2 {
        val d = normalize(dir)
        val yaw = Math.toDegrees(atan2(-d.x, -d.z).toDouble()).toFloat()
        val pitch = Math.toDegrees(asin(d.y.coerceIn(-1f, 1f).toDouble())).toFloat()
        val u = yaw / 360f + 0.5f
        val v = 0.5f - pitch / 180f
        return Float2(u, v)
    }

    private companion object {
        const val PI = Math.PI
        const val TWO_PI = 2.0 * Math.PI
    }
}
