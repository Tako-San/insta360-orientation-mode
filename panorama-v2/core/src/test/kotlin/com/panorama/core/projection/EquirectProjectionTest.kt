package com.panorama.core.projection

import dev.romainguy.kotlin.math.Float3
import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs

/** Pins the canonical (sign-free) equirect sphere: vertex/index counts, +Y top pole, and the
 *  forward -> horizontal-center UV anchor. The V-flip is owned by the runtime stMatrix in
 *  Phase 2, so this geometry stays in the single canonical base (top vertex +Y, forward -Z). */
class EquirectProjectionTest : FunSpec({
    val projection: ProjectionModel = EquirectProjection()

    test("mesh has expected vertex and index counts") {
        val stacks = 32
        val slices = 64
        val mesh = projection.buildMesh(stacks, slices)
        assert(mesh.positions.size == (stacks + 1) * (slices + 1) * 3)
        assert(mesh.indices.size == stacks * slices * 6)
    }

    test("mesh top vertex is at +Y (canonical, sign-free)") {
        val mesh = projection.buildMesh(2, 2)
        // First row of the first stack is the +Y pole: y-component of the first vertex ~= radius 1.
        assert(mesh.positions[1] > 0.99f)
    }

    test("forward direction maps to horizontal-center UV") {
        val uv = projection.directionToTexUv(Float3(0f, 0f, -1f))
        assert(abs(uv.x - 0.5f) < 0.01f)
    }
})
