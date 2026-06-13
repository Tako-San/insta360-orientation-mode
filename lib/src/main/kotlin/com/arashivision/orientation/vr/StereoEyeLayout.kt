package com.arashivision.orientation.vr

/**
 * Pure (JVM, no Android) stereo split-screen geometry for VR mode.
 *
 * Both VR managers (capture and offline player) duplicated the same eye scaling and
 * spacing math. This holds the pure part: given an eye scale and a pixel spacing between
 * the two eyes, it produces the per-eye scale and the inner margins.
 *
 * Margins: the spacing is split in half; the left eye gets the half as its END margin
 * and the right eye gets the half as its START margin, pulling the two eyes together
 * (negative spacing) or apart (positive). This matches the original applyVrAdjustments.
 */
data class StereoEyeLayout(
    val eyeScale: Float,
    val eyeSpacingPx: Int
) {
    /** Half of the spacing, applied as inner margin to each eye. */
    val halfSpacingPx: Int get() = eyeSpacingPx / 2

    /** Margin on the inner (right) side of the LEFT eye. */
    val leftEyeMarginEndPx: Int get() = halfSpacingPx

    /** Margin on the inner (left) side of the RIGHT eye. */
    val rightEyeMarginStartPx: Int get() = halfSpacingPx
}

/** Maps a 0..100 SeekBar progress to eyeScale in [0.5, 1.5]. */
fun seekProgressToEyeScale(progress: Int): Float = 0.5f + progress / 100f

/** Inverse of [seekProgressToEyeScale]: eyeScale → 0..100 progress (clamped). */
fun eyeScaleToSeekProgress(eyeScale: Float): Int =
    ((eyeScale - 0.5f) * 100f).toInt().coerceIn(0, 100)

/**
 * Maps a spacing SeekBar progress to a pixel spacing, given the half-range in px.
 * The bar runs 0..(2*maxPx); the midpoint (maxPx) is zero spacing.
 */
fun seekProgressToSpacingPx(progress: Int, maxPx: Int): Int = progress - maxPx

/** Inverse: pixel spacing → spacing SeekBar progress (clamped to 0..2*maxPx). */
fun spacingPxToSeekProgress(spacingPx: Int, maxPx: Int): Int =
    (spacingPx + maxPx).coerceIn(0, maxPx * 2)
