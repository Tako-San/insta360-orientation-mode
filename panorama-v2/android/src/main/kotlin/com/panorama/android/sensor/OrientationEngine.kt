package com.panorama.android.sensor

import com.panorama.core.calibration.AxisConvention
import com.panorama.core.math.GazeState
import com.panorama.core.math.quatFromYawPitch
import com.panorama.core.math.yawPitchOf
import com.panorama.core.orientation.OrientationProcessor
import com.panorama.core.orientation.OrientationSmoothing
import dev.romainguy.kotlin.math.Quaternion
import java.util.concurrent.atomic.AtomicReference

/** The :android sensor pipeline: raw rotation-vector samples ->
 *  [RemapConfig] (display-rotation remap, Site B) -> [OrientationProcessor] (re-base against the
 *  calibration zero) -> [OrientationSmoothing] (adaptive low-pass + angular rate) -> a lock-free
 *  [GazeState] snapshot the GL thread reads via [gazeRef].
 *
 *  Threading: the sample callback runs on the source's thread (the [SensorReader] HandlerThread on
 *  device). It is the single writer of [gazeRef]; the GL thread is the single reader. The
 *  [AtomicReference] makes that hand-off safe without locks.
 *
 *  @param sampleSource injectable orientation sample source (real [SensorReader] or a test fake).
 *  @param displayRotation supplies the current [android.view.Surface] rotation per sample, so a
 *         device re-orientation is picked up live.
 *  @param axisConvention reserved Site-A convention (kept here so the pipeline owns one place to
 *         thread it through to the renderer); not applied to the gaze quaternion, which stays
 *         sign-free until [com.panorama.core.calibration.ViewCalibration]. */
class OrientationEngine(
    private val sampleSource: SampleSource,
    private val displayRotation: () -> Int,
    @Suppress("unused") private val axisConvention: AxisConvention = AxisConvention(),
) {
    private val processor = OrientationProcessor()

    // Recreated on calibrate() so the filter does not slerp down from the pre-calibration
    // orientation: capturing a new zero should snap the gaze to identity, not ease into it.
    @Volatile
    private var smoothing = OrientationSmoothing()

    val gazeRef: AtomicReference<GazeState> = AtomicReference(IDENTITY_GAZE)

    /** Latest raw (remapped, pre-calibration) orientation, captured for [calibrate]. */
    @Volatile
    private var lastRaw: Quaternion = Quaternion()

    private var lastTimestampNs: Long = 0L

    fun start() = sampleSource.start(::onSample)

    fun stop() = sampleSource.stop()

    fun currentGaze(): GazeState = gazeRef.get()

    /** Capture the current orientation as the zero point; subsequent gaze is relative to it.
     *  Also resets the smoothing filter and the dt clock so the gaze snaps to identity at the new
     *  zero instead of easing in from the pre-calibration orientation. */
    fun calibrate() {
        // Re-zero heading only: project lastRaw onto its yaw so a rolled/pitched hold cannot tilt
        // the yaw/pitch basis. The body-frame rebasing in OrientationProcessor (inverse(ref)*current)
        // rotates the (yaw, pitch) plane by the reference's roll, so a raw lastRaw with any roll would
        // bleed a horizontal pan into pitch. quatFromYawPitch is roll-free by construction.
        val (yaw, _) = yawPitchOf(lastRaw)
        processor.calibrate(quatFromYawPitch(yaw, 0f))
        smoothing = OrientationSmoothing()
        lastTimestampNs = 0L
    }

    private fun onSample(values: FloatArray, timestampNs: Long) {
        val raw = RemapConfig.fromRotationVector(values, displayRotation())
        lastRaw = raw

        val dtSec = if (lastTimestampNs == 0L) 0f else (timestampNs - lastTimestampNs) / 1e9f
        lastTimestampNs = timestampNs

        val relative = processor.relativeTo(raw)
        val smoothed = smoothing.update(relative, dtSec)
        val (yaw, pitch) = yawPitchOf(smoothed.quaternion)

        // Phase 4 axis-calibration diagnostic. Sampled sparsely (every Nth sample) so it does not
        // flood logcat. Shows the angles at each stage so the device-frame -> screen mapping can be
        // read off directly: RAW = pre-calibration (absolute device pose), REL = after re-basing to
        // the calibration zero (this is what feeds the view), FINAL = after smoothing. Snapshot with
        //   adb logcat OrientationEngine:I '*:S'
        if (sampleCount++ % LOG_INTERVAL == 0L) {
            val (rawYaw, rawPitch) = yawPitchOf(raw)
            val (relYaw, relPitch) = yawPitchOf(relative)
            android.util.Log.i(
                TAG,
                "RAW yaw=${rawYaw.fmt()} pitch=${rawPitch.fmt()} | " +
                    "REL yaw=${relYaw.fmt()} pitch=${relPitch.fmt()} | " +
                    "FINAL yaw=${yaw.fmt()} pitch=${pitch.fmt()}",
            )
        }

        gazeRef.set(
            GazeState(
                quaternion = smoothed.quaternion,
                yawDeg = yaw,
                pitchDeg = pitch,
                angularVelocityDegPerSec = smoothed.angularVelocityDegPerSec,
                angularAxis = smoothed.angularAxis,
            ),
        )
    }

    private companion object {
        const val TAG = "OrientationEngine"
        const val LOG_INTERVAL = 30L
        val IDENTITY_GAZE = GazeState(Quaternion(), 0f, 0f, 0f)

        fun Float.fmt(): String = "%.1f".format(this)
    }

    @Volatile
    private var sampleCount: Long = 0L
}
