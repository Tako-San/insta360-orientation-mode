package com.arashivision.sdk.demo.ui.player

import com.arashivision.orientation.OrientationSink
import com.elvishew.xlog.Logger
import com.elvishew.xlog.XLog
import java.lang.reflect.Method

/**
 * OrientationSink over reflection: SDK views (InstaCapturePlayerView / SphericalGLSurfaceView)
 * have setYaw(float)/setPitch(float) methods that are not exposed in the public API. The method is resolved
 * and cached once; a missing method is logged (previously it was swallowed silently).
 *
 * @param target the player view
 * @param yawOffsetDeg added to yaw (for the VR IPD offset)
 */
class ReflectiveOrientationSink(
    private val target: Any,
    private val yawOffsetDeg: Float = 0f,
    private val logger: Logger = XLog.tag("ReflectiveOrientationSink").build()
) : OrientationSink {

    private var resolved = false
    private var setYaw: Method? = null
    private var setPitch: Method? = null

    private fun resolveOnce() {
        if (resolved) return
        resolved = true
        val cls = target.javaClass
        setYaw = try {
            cls.getMethod("setYaw", Float::class.javaPrimitiveType)
        } catch (e: NoSuchMethodException) {
            logger.w("setYaw not found on ${cls.name}: ${e.message}")
            null
        }
        setPitch = try {
            cls.getMethod("setPitch", Float::class.javaPrimitiveType)
        } catch (e: NoSuchMethodException) {
            logger.w("setPitch not found on ${cls.name}: ${e.message}")
            null
        }
    }

    override fun apply(yawDeg: Float, pitchDeg: Float) {
        resolveOnce()
        try {
            setYaw?.invoke(target, yawDeg + yawOffsetDeg)
            setPitch?.invoke(target, pitchDeg)
        } catch (e: Exception) {
            logger.e("apply orientation failed: ${e.message}")
        }
    }
}
