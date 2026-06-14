package com.panorama.core.vr

import com.panorama.core.math.GazeState
import com.panorama.core.math.yawPitchOf
import dev.romainguy.kotlin.math.Quaternion
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

/** Pins the stereo split: the two eyes straddle the base gaze symmetrically by the full IPD yaw,
 *  and the seekbar mappings (eyeScale / spacing <-> [0,1] progress) round-trip so the settings
 *  dialog can read a stored value back onto the slider without drift. */
class StereoEyeLayoutTest : FunSpec({

    test("stereoGaze splits the eyes symmetrically by the full ipd around Y") {
        val base = GazeState(Quaternion(), 0f, 0f, 0f)
        val ipd = 6f
        val (left, right) = StereoEyeLayout.stereoGaze(base, ipd)
        val (leftYaw, _) = yawPitchOf(left.quaternion)
        val (rightYaw, _) = yawPitchOf(right.quaternion)
        // Left eye yaw = base - ipd/2, right eye yaw = base + ipd/2 => separated by the full ipd,
        // centred on the base yaw (0).
        leftYaw shouldBe (-ipd / 2f plusOrMinus 1e-3f)
        rightYaw shouldBe (ipd / 2f plusOrMinus 1e-3f)
        (rightYaw - leftYaw) shouldBe (ipd plusOrMinus 1e-3f)
    }

    test("eyeScale <-> seekProgress round-trips") {
        val layout = StereoEyeLayout()
        for (p in listOf(0f, 0.25f, 0.5f, 0.9f, 1f)) {
            val scale = layout.eyeScaleFromSeek(p)
            layout.seekFromEyeScale(scale) shouldBe (p plusOrMinus 1e-4f)
        }
    }

    test("spacing <-> seekProgress round-trips") {
        val layout = StereoEyeLayout()
        for (p in listOf(0f, 0.3f, 0.5f, 0.75f, 1f)) {
            val spacing = layout.spacingFromSeek(p)
            layout.seekFromSpacing(spacing) shouldBe (p plusOrMinus 1e-4f)
        }
    }
})
