package com.arashivision.sdk.demo.ui.player

import android.graphics.PointF
import com.elvishew.xlog.Logger
import com.elvishew.xlog.XLog
import java.lang.reflect.Method

/**
 * Rotates the sphere in the offline media3 player (`SphericalGLSurfaceView`).
 *
 * media3's `SphericalGLSurfaceView` has NO public setYaw/setPitch — that is exactly why
 * gyroscope view-direction control never worked offline (the old
 * [ReflectiveOrientationSink] silently failed with NoSuchMethodException on the missing methods).
 *
 * The actual object that rotates the sphere is the private GL renderer `SphericalGLSurfaceView$Renderer`
 * (stored in the `mRenderer` field of the base `GLSurfaceView`). It implements `TouchTracker$Listener`
 * with the method `onScrollChange(PointF)`, where `PointF.x` = yaw (degrees), `PointF.y` = pitch (degrees) —
 * this is the same channel through which the sphere is rotated by touch. The method is `synchronized`, so
 * it can be called from any thread. Feeding our gyro yaw/pitch into it rotates the sphere
 * exactly the same way as manual "dragging".
 *
 * WARNING (probe A, diagnostic): this is reflection into a third-party library's private renderer —
 * fragile, it may silently break when media3 is updated. Used temporarily to verify
 * that our gyro math rotates the sphere correctly and stays in sync with the arrow. The architectural
 * end state is our own panorama GL renderer with a single orientation source.
 *
 * @param sphericalView media3 SphericalGLSurfaceView (a GLSurfaceView subclass)
 */
class Media3SphericalOrientationSink(
    private val sphericalView: Any,
    private val logger: Logger = XLog.tag("Media3OrientationSink").build()
) : OrientationApplier {

    private var resolved = false
    private var renderer: Any? = null
    private var onScrollChange: Method? = null
    private val point = PointF()
    private var applyLogCounter = 0

    private fun resolveOnce() {
        if (resolved) return
        resolved = true

        // mRenderer is declared in android.opengl.GLSurfaceView (the base class).
        val rendererField = try {
            android.opengl.GLSurfaceView::class.java
                .getDeclaredField("mRenderer")
                .also { it.isAccessible = true }
        } catch (e: NoSuchFieldException) {
            logger.e("GLSurfaceView.mRenderer not found: ${e.message}")
            null
        }
        renderer = rendererField?.get(sphericalView)
        if (renderer == null) {
            logger.e("mRenderer is null on ${sphericalView.javaClass.name}")
            return
        }
        onScrollChange = try {
            renderer!!.javaClass.getMethod("onScrollChange", PointF::class.java)
        } catch (e: NoSuchMethodException) {
            logger.e("onScrollChange not found on ${renderer!!.javaClass.name}: ${e.message}")
            null
        }
        if (onScrollChange != null) {
            logger.d("Media3 renderer resolved OK: ${renderer!!.javaClass.name}")
        }
    }

    /**
     * Rotate the sphere to the given yaw/pitch (degrees). Fed into the private media3 renderer
     * through the same channel as touch (onScrollChange). media3 clamps pitch to ±45° internally.
     */
    override fun apply(yawDeg: Float, pitchDeg: Float) {
        resolveOnce()
        val method = onScrollChange ?: return
        val target = renderer ?: return
        try {
            point.set(yawDeg, pitchDeg)
            method.invoke(target, point)
            // Probe A: periodic log to confirm via logcat that the angles reach the sphere.
            if (applyLogCounter++ % 60 == 0) {
                logger.d("apply yaw=$yawDeg pitch=$pitchDeg → ${target.javaClass.simpleName}.onScrollChange")
            }
        } catch (e: Exception) {
            logger.e("apply orientation failed: ${e.message}")
        }
    }
}
