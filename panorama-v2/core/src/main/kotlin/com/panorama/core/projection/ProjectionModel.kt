package com.panorama.core.projection

import com.panorama.core.math.MeshData
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3

/** A panorama projection: it builds the textured mesh the renderer draws and maps a world-space
 *  look direction to the texture coordinate it samples. shaderSource() from v1 is deferred to a
 *  later phase; v2 only needs geometry + the direction->UV mapping here. */
interface ProjectionModel {
    /** Build the projection mesh as a UV grid of (stacks+1) x (slices+1) vertices. */
    fun buildMesh(stacks: Int, slices: Int): MeshData

    /** Map a (not necessarily normalized) world-space direction to its texture UV. */
    fun directionToTexUv(dir: Float3): Float2
}
