package com.panorama.android.gl

import android.content.Context
import android.util.AttributeSet
import android.view.Surface
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.exoplayer.video.spherical.CameraMotionListener
import androidx.media3.exoplayer.video.spherical.SphericalGLSurfaceView

/** Mono 360 viewer backed by media3's [SphericalGLSurfaceView]. The inner view owns its own video
 *  [Surface], the gyro->view rotation (TYPE_GAME_ROTATION_VECTOR + touch drag), its sensor
 *  registration (in [onResume]/[onPause]), and its render loop — so none of the custom GL stack
 *  ([PanoramaGlView]/[PanoramaRenderer]/[ChoreographerDriver]) is involved in mono mode.
 *
 *  [SphericalGLSurfaceView] is `final`, so this holder wraps one by composition (as a single-child
 *  [FrameLayout]) instead of subclassing it, and forwards the lifecycle/listener surface the caller
 *  needs.
 *
 *  The caller wires three things into the player:
 *   - the emitted [Surface] via [onVideoSurfaceReady] (and detaches on destroy via
 *     [onVideoSurfaceDestroyed]),
 *   - the inner view's [getVideoFrameMetadataListener] and [getCameraMotionListener], required for
 *     correct equirect projection (hand them to ExoVideoPlayer.setVideoFrameMetadataListener /
 *     setCameraMotionListener).
 *
 *  This view does NOT expose the current gaze; the off-screen arrow takes its gaze from
 *  OrientationEngine.gazeRef independently, so no gaze binding lives here. */
@UnstableApi
class SphericalPanoramaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val glSurfaceView = SphericalGLSurfaceView(context)

    /** Invoked when the internal OES-backed [Surface] becomes available; hand it to the player. */
    var onVideoSurfaceReady: ((Surface) -> Unit)? = null

    /** Invoked when the surface is destroyed, so the caller can detach it from the player. */
    var onVideoSurfaceDestroyed: (() -> Unit)? = null

    init {
        addView(glSurfaceView)
        glSurfaceView.addVideoSurfaceListener(object : SphericalGLSurfaceView.VideoSurfaceListener {
            override fun onVideoSurfaceCreated(surface: Surface) {
                onVideoSurfaceReady?.invoke(surface)
            }

            override fun onVideoSurfaceDestroyed(surface: Surface) {
                onVideoSurfaceDestroyed?.invoke()
            }
        })
    }

    /** Hand to ExoVideoPlayer.setVideoFrameMetadataListener; required for equirect projection. */
    fun getVideoFrameMetadataListener(): VideoFrameMetadataListener =
        glSurfaceView.videoFrameMetadataListener

    /** Hand to ExoVideoPlayer.setCameraMotionListener; required for equirect projection. */
    fun getCameraMotionListener(): CameraMotionListener =
        glSurfaceView.cameraMotionListener

    /** Resume the inner view's sensor registration and render loop. */
    fun onResume() = glSurfaceView.onResume()

    /** Pause the inner view's sensor registration and render loop. */
    fun onPause() = glSurfaceView.onPause()
}
