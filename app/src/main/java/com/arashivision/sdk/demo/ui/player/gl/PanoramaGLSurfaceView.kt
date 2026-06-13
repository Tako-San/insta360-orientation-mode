package com.arashivision.sdk.demo.ui.player.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.Surface
import com.arashivision.orientation.panorama.UnitQuaternion

/**
 * Own panorama view: a GLSurfaceView that renders an equirectangular sphere via [PanoramaRenderer]
 * and exposes [videoSurface] for ExoPlayer plus [setOrientation] for the gyro. Replaces media3
 * SphericalGLSurfaceView; no reflection.
 */
class PanoramaGLSurfaceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer: PanoramaRenderer
    private var surfaceTexture: SurfaceTexture? = null

    /** The Surface ExoPlayer should render the video into. Null until the GL texture is ready. */
    @Volatile var videoSurface: Surface? = null
        private set

    /** Called once when videoSurface becomes available (so the Activity can attach ExoPlayer). */
    var onVideoSurfaceReady: ((Surface) -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        renderer = PanoramaRenderer(onTextureReady = { texId -> createSurfaceTexture(texId) })
        renderer.onDrawFrameCallback = {
            surfaceTexture?.let { st ->
                st.updateTexImage()
                st.getTransformMatrix(renderer.stMatrix)
            }
        }
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    private fun createSurfaceTexture(textureId: Int) {
        // runs on the GL thread (from onSurfaceCreated)
        val st = SurfaceTexture(textureId)
        st.setOnFrameAvailableListener { requestRender() }
        surfaceTexture = st
        val surface = Surface(st)
        videoSurface = surface
        post { onVideoSurfaceReady?.invoke(surface) }
    }

    fun setOrientation(q: UnitQuaternion) {
        renderer.setOrientation(q)
        requestRender()
    }

    fun release() {
        videoSurface?.release()
        videoSurface = null
        surfaceTexture?.release()
        surfaceTexture = null
    }
}
