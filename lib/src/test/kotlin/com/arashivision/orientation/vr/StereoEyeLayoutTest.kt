package com.arashivision.orientation.vr

import org.junit.Assert.assertEquals
import org.junit.Test

class StereoEyeLayoutTest {

    @Test
    fun `negative spacing pulls eyes together via half margins`() {
        val l = StereoEyeLayout(eyeScale = 0.7f, eyeSpacingPx = -400)
        assertEquals(-200, l.halfSpacingPx)
        assertEquals(-200, l.leftEyeMarginEndPx)
        assertEquals(-200, l.rightEyeMarginStartPx)
    }

    @Test
    fun `positive spacing pushes eyes apart`() {
        val l = StereoEyeLayout(eyeScale = 1.0f, eyeSpacingPx = 120)
        assertEquals(60, l.halfSpacingPx)
        assertEquals(60, l.leftEyeMarginEndPx)
        assertEquals(60, l.rightEyeMarginStartPx)
    }

    @Test
    fun `zero spacing yields zero margins`() {
        val l = StereoEyeLayout(eyeScale = 0.7f, eyeSpacingPx = 0)
        assertEquals(0, l.halfSpacingPx)
    }

    @Test
    fun `eye scale round-trips through seek progress`() {
        assertEquals(0.5f, seekProgressToEyeScale(0), 1e-6f)
        assertEquals(1.5f, seekProgressToEyeScale(100), 1e-6f)
        assertEquals(0.7f, seekProgressToEyeScale(20), 1e-6f)
        // (0.7-0.5)*100 == 19.999.. in float → toInt()==19; matches the original
        // applyVrAdjustments behavior exactly (do not "fix" the formula).
        assertEquals(19, eyeScaleToSeekProgress(0.7f))
        // clamping
        assertEquals(0, eyeScaleToSeekProgress(0.1f))
        assertEquals(100, eyeScaleToSeekProgress(2.0f))
    }

    @Test
    fun `spacing round-trips through seek progress with midpoint zero`() {
        val maxPx = 525  // ~200dp at 2.625 density; value is arbitrary for the test
        assertEquals(0, seekProgressToSpacingPx(maxPx, maxPx))           // midpoint = zero
        assertEquals(-maxPx, seekProgressToSpacingPx(0, maxPx))          // far left = -maxPx
        assertEquals(maxPx, seekProgressToSpacingPx(maxPx * 2, maxPx))   // far right = +maxPx
        assertEquals(maxPx, spacingPxToSeekProgress(0, maxPx))           // zero spacing = midpoint
        assertEquals(0, spacingPxToSeekProgress(-maxPx, maxPx))
        // clamping
        assertEquals(0, spacingPxToSeekProgress(-10 * maxPx, maxPx))
        assertEquals(maxPx * 2, spacingPxToSeekProgress(10 * maxPx, maxPx))
    }
}
