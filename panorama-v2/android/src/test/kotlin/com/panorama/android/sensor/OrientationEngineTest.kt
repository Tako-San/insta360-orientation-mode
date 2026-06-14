package com.panorama.android.sensor

import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.view.Surface
import com.panorama.core.calibration.AxisConvention
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrientationEngineTest {

    /** Hand-fed sample source: the test pushes (values, timestampNs) pairs at will. */
    private class FakeSampleSource : SampleSource {
        private var sink: ((FloatArray, Long) -> Unit)? = null
        override fun start(onSample: (FloatArray, Long) -> Unit) { sink = onSample }
        override fun stop() { sink = null }
        fun emit(values: FloatArray, timestampNs: Long) = sink?.invoke(values, timestampNs)
    }

    private fun engine(source: SampleSource) =
        OrientationEngine(source, { Surface.ROTATION_0 }, AxisConvention())

    @Test
    fun `engine updates gazeRef on sample`() {
        val source = FakeSampleSource()
        val eng = engine(source)
        eng.start()

        // Two samples 20ms apart describing a yaw turn; the second must move the gaze.
        val t0 = 1_000_000_000L
        source.emit(rotationVectorYaw(0f), t0)
        source.emit(rotationVectorYaw(30f), t0 + 20_000_000L)

        val gaze = eng.gazeRef.get()
        assertNotNull(gaze)
        // After a 30-degree yaw the gaze is no longer the identity it started at.
        assertTrue("expected gaze to move, yawDeg=${gaze.yawDeg}", abs(gaze.yawDeg) > 1f)
    }

    @Test
    fun `calibrate zeroes the gaze`() {
        val source = FakeSampleSource()
        val eng = engine(source)
        eng.start()

        val t0 = 1_000_000_000L
        // Move to a non-trivial orientation and let smoothing settle on it.
        var t = t0
        repeat(40) {
            source.emit(rotationVectorYaw(45f), t)
            t += 16_000_000L
        }
        eng.calibrate()

        // Feed the SAME orientation again: relative to the calibration point it is the zero gaze.
        source.emit(rotationVectorYaw(45f), t)
        t += 16_000_000L
        source.emit(rotationVectorYaw(45f), t)

        val gaze = eng.gazeRef.get()
        assertTrue("yaw should be ~0 after calibrate, got ${gaze.yawDeg}", abs(gaze.yawDeg) < 2f)
        assertTrue("pitch should be ~0 after calibrate, got ${gaze.pitchDeg}", abs(gaze.pitchDeg) < 2f)
    }

    @Test
    fun `SensorReader registers rotation-vector listener at fastest rate`() {
        val sensorManager = mockk<SensorManager>(relaxed = true)
        val rvSensor = mockk<Sensor>()
        every { sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) } returns rvSensor

        val listenerSlot = slot<SensorEventListener>()
        every {
            sensorManager.registerListener(capture(listenerSlot), rvSensor, any<Int>(), any<Handler>())
        } returns true

        val reader = SensorReader(sensorManager)
        reader.start { _, _ -> }

        verify {
            sensorManager.registerListener(
                any<SensorEventListener>(),
                rvSensor,
                SensorManager.SENSOR_DELAY_FASTEST,
                any<Handler>(),
            )
        }

        reader.stop()
        verify { sensorManager.unregisterListener(any<SensorEventListener>()) }
    }

    /** Build a TYPE_ROTATION_VECTOR sample (3-element form) for a pure yaw about +Y, degrees. */
    private fun rotationVectorYaw(deg: Float): FloatArray {
        val half = Math.toRadians(deg.toDouble() / 2.0)
        // rotation vector = axis * sin(half); axis = +Y
        return floatArrayOf(0f, kotlin.math.sin(half).toFloat(), 0f)
    }
}
