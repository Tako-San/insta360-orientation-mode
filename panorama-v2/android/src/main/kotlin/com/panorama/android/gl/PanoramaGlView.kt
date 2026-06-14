package com.panorama.android.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import com.panorama.core.calibration.AxisConvention
import com.panorama.core.math.GazeState
import com.panorama.core.math.yawPitchOf
import com.panorama.core.projection.EquirectProjection
import com.panorama.core.projection.ProjectionModel
import dev.romainguy.kotlin.math.Quaternion
import java.util.concurrent.atomic.AtomicReference

/** [GLSurfaceView] that renders the panorama via [PanoramaRenderer] and paces redraws through a
 *  [ChoreographerDriver]. Render mode is WHEN_DIRTY: a frame is only drawn when the driver (during
 *  playback) or a seek-repaint asks for it via [GLSurfaceView.requestRender], so a paused, still
 *  player costs no GPU work.
 *
 *  Frame-availability vs. cadence are decoupled (spec section 5.2): the decoder's
 *  [SurfaceTexture.OnFrameAvailableListener] only flips [PanoramaRenderer.pendingFrame]; it does
 *  NOT request a render. The Choreographer driver owns the redraw cadence, so we render on the
 *  display clock instead of the (jittery, possibly faster-than-display) decoder clock.
 *
 *  @param projectionModel sphere mesh provider (defaults to equirect).
 *  @param axisConvention Site-A signs threaded to the renderer. */
class PanoramaGlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    projectionModel: ProjectionModel = EquirectProjection(),
    axisConvention: AxisConvention = AxisConvention(),
) : GLSurfaceView(context, attrs) {

    /** Lock-free orientation snapshot the renderer reads each frame; the sensor pipeline writes it.
     *  Seeded with an identity gaze so the first frames before any sensor sample render forward. */
    val gazeRef: AtomicReference<GazeState> = AtomicReference(identityGaze())

    /** Invoked on the GL thread once the video [Surface] is ready to hand to the player. */
    var onVideoSurfaceReady: ((Surface) -> Unit)? = null

    private val renderer: PanoramaRenderer
    private val driver: ChoreographerDriver
    private var surfaceTexture: SurfaceTexture? = null

    init {
        setEGLContextClientVersion(2)
        renderer = PanoramaRenderer(
            projectionModel = projectionModel,
            gazeRef = gazeRef,
            axisConvention = axisConvention,
            onSurfaceTextureReady = ::onSurfaceTextureReady,
        )
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        // Driver tick just nudges the WHEN_DIRTY surface; onDrawFrame does the real work.
        driver = ChoreographerDriver(ChoreographerFrameScheduler()) { requestRender() }
    }

    /** Point the renderer at an external orientation snapshot — typically the sensor engine's own
     *  AtomicReference — so the GL thread reads exactly the gaze the engine writes, with no copy
     *  step. The reference swap is volatile inside the renderer and is picked up on the next frame. */
    fun bindGazeRef(ref: AtomicReference<GazeState>) {
        renderer.gazeRef = ref
        requestRender()
    }

    /** Enable / disable split-screen VR. Takes effect on the next drawn frame. */
    fun setVrEnabled(enabled: Boolean) {
        renderer.vrEnabled = enabled
        requestRender()
    }

    /** Playback transitions drive the render loop: playing -> continuous redraw on the display
     *  clock; paused -> halt the loop (the last frame stays on screen). */
    fun onPlaybackStateChanged(isPlaying: Boolean) {
        if (isPlaying) driver.start() else driver.stop()
    }

    /** Repaint exactly one frame without starting the loop — for a seek while paused. */
    fun renderNow() {
        driver.renderOnce()
    }

    override fun onPause() {
        driver.stop()
        super.onPause()
    }

    private fun onSurfaceTextureReady(st: SurfaceTexture) {
        surfaceTexture = st
        Log.i(TAG, "onSurfaceTextureReady: video surface ready, wiring frame listener")
        // The Choreographer driver owns the playback cadence (for gaze smoothness), but a decoded
        // frame must always be pulled even when the driver is stopped or out of sync with the
        // decoder clock. So a new frame both marks pendingFrame AND requests a render: belt and
        // braces against the first-frame/freeze classes of bugs.
        st.setOnFrameAvailableListener {
            renderer.pendingFrame = true
            requestRender()
            // WHEN_DIRTY coalesces a requestRender() that races the one auto-draw fired right after
            // onSurfaceCreated: the draw can consume the tick before pendingFrame is set, stranding
            // the first decoded frame until an unrelated UI event redraws. Re-arm on the next
            // main-loop pass (after that auto-draw has run) so the still-pending frame always paints.
            post { requestRender() }
        }
        onVideoSurfaceReady?.invoke(Surface(st))
    }

    private companion object {
        private const val TAG = "PanoramaGlView"

        fun identityGaze(): GazeState {
            val q = Quaternion()
            val (yaw, pitch) = yawPitchOf(q)
            return GazeState(q, yaw, pitch, angularVelocityDegPerSec = 0f)
        }
    }
}
