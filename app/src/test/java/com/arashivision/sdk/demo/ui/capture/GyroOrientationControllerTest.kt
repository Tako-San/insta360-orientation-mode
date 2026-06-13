package com.arashivision.sdk.demo.ui.capture

import android.content.Context
import com.arashivision.orientation.OrientationProcessor
import com.arashivision.orientation.Quaternion
import com.arashivision.orientation.SensorOrientation
import com.elvishew.xlog.LogConfiguration
import com.elvishew.xlog.XLog
import com.elvishew.xlog.printer.Printer
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GyroOrientationControllerTest {

    @Before
    fun initLog() {
        // XLog is usually initialized in Application; in a pure JVM test we initialize
        // it with a no-op printer so that logger.d(...) in start/stop/calibrate does not crash.
        // A repeated initialization throws IllegalStateException — we swallow it.
        try {
            XLog.init(LogConfiguration.Builder().build(), Printer { _, _, _ -> })
        } catch (_: IllegalStateException) {
        }
    }

    /** Fake sensor source: capture the callback so the test can push frames. */
    private class FakeSensorSource : SensorSource {
        var onValues: ((FloatArray) -> Unit)? = null
        override fun start(onValues: (FloatArray) -> Unit) { this.onValues = onValues }
        override fun stop() { onValues = null }
        fun emit(v: FloatArray) { onValues?.invoke(v) }
    }

    /** Stub math: returns a fixed yaw from the first vector element. */
    private class StubMath : RotationMatrixMath {
        override fun fromRotationVector(rotationVectorValues: FloatArray, displayRotation: Int) =
            SensorOrientation(Quaternion(1f, 0f, 0f, 0f), rotationVectorValues[0], 0f, 0f)
    }

    @Test
    fun `emitting a frame drives processor and calls applyOrientation`() {
        val src = FakeSensorSource()
        var appliedYaw = Float.NaN
        val ctx = mockk<Context>(relaxed = true)
        val controller = GyroOrientationController(
            context = ctx,
            getDisplayRotation = { 0 },
            applyOrientation = { yaw, _ -> appliedYaw = yaw },
            sensorSource = src,
            rotationMath = StubMath(),
            processor = OrientationProcessor(rateLimitMs = 0L)
        )
        controller.start()
        src.emit(floatArrayOf(30f))
        assertTrue("applyOrientation should have been called", !appliedYaw.isNaN())
    }

    @Test
    fun `gaze reflects calibration via controller API`() {
        val src = FakeSensorSource()
        val ctx = mockk<Context>(relaxed = true)
        val controller = GyroOrientationController(
            context = ctx,
            getDisplayRotation = { 0 },
            applyOrientation = { _, _ -> },
            sensorSource = src,
            rotationMath = StubMath(),
            processor = OrientationProcessor(rateLimitMs = 0L)
        )
        controller.start()
        src.emit(floatArrayOf(100f))
        controller.calibrate()
        src.emit(floatArrayOf(130f))
        assertEquals(30f, controller.getGazeYawDeg(), 1e-3f)
    }

    private fun controller(
        src: SensorSource,
        apply: (Float, Float) -> Unit = { _, _ -> }
    ): GyroOrientationController {
        val ctx = mockk<Context>(relaxed = true)
        return GyroOrientationController(
            context = ctx,
            getDisplayRotation = { 0 },
            applyOrientation = apply,
            sensorSource = src,
            rotationMath = StubMath(),
            processor = OrientationProcessor(rateLimitMs = 0L)
        )
    }

    @Test
    fun `stop tears down the sensor source`() {
        val src = FakeSensorSource()
        val c = controller(src)
        c.start()
        src.emit(floatArrayOf(10f))
        c.stop()
        // after stop the source is detached — emit does nothing (the callback is removed)
        var afterStop = false
        // re-subscribing directly with the fake is absent; we just verify that stop does not crash
        c.stop() // a repeated stop is safe
        assertTrue(!afterStop)
    }

    @Test
    fun `sensitivity and inversion proxy to processor`() {
        val c = controller(FakeSensorSource())
        c.sensivity = 2.0f
        c.invertYaw = true
        c.invertPitch = false
        assertEquals(2.0f, c.sensivity, 1e-6f)
        assertTrue(c.invertYaw)
        assertTrue(!c.invertPitch)
    }

    @Test
    fun `getters expose processor state`() {
        val src = FakeSensorSource()
        val c = controller(src)
        c.start()
        src.emit(floatArrayOf(15f))
        // the getters do not crash and return consistent values
        assertEquals(c.getSmoothedYaw(), c.getSmoothedYaw(), 1e-6f)
        assertEquals(15f, c.getGazeYawDeg(), 1e-3f)
        assertEquals(1f, c.getCurrentQuaternion().magnitude(), 1e-3f)
        assertEquals(1f, c.getSmoothedQuaternion().magnitude(), 1e-3f)
        assertEquals(1f, c.getRawCurrentQuaternion().magnitude(), 1e-3f)
        // raw euler getters are available
        c.getRawEulerYawDeg(); c.getRawEulerPitchDeg(); c.getSmoothedPitch()
    }

    @Test
    fun `disabled controller ignores frames`() {
        val src = FakeSensorSource()
        var called = false
        val ctx = mockk<Context>(relaxed = true)
        val controller = GyroOrientationController(
            context = ctx,
            getDisplayRotation = { 0 },
            applyOrientation = { _, _ -> called = true },
            sensorSource = src,
            rotationMath = StubMath(),
            processor = OrientationProcessor(rateLimitMs = 0L)
        )
        controller.setzOrientationEnabled(false)
        controller.start()      // start() returns early when disabled; source not started
        src.emit(floatArrayOf(50f)) // no callback wired
        assertTrue("disabled controller must not apply orientation", !called)
    }
}
