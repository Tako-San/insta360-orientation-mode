package com.arashivision.orientation.capture

/**
 * Pure (JVM) arithmetic for computing the duration of the finished timelapse video.
 *
 * Extracted from CaptureViewModel.onCaptureTimeChanged for testability (there it was
 * intermixed with InstaCameraManager calls). SDK types do not leak here — the inputs are
 * the already-extracted intervalNativeValue and fps.
 */
object TimelapseMath {
    /**
     * The duration of the resulting video (ms) for timelapse.
     *
     * Formula from the source: ((captureTimeMs / intervalNativeValue) / fps) * 1000.
     * Returns 0 if interval or fps are non-positive (guard against division by zero).
     *
     * @param captureTimeMs the elapsed capture time (ms)
     * @param intervalNativeValue the native frame-interval value
     * @param fps frames per second of the selected resolution
     */
    fun videoDurationMs(captureTimeMs: Long, intervalNativeValue: Int, fps: Int): Long {
        if (intervalNativeValue <= 0 || fps <= 0) return 0L
        return ((captureTimeMs / intervalNativeValue) / fps) * 1000
    }
}
