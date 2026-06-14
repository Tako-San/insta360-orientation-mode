package com.panorama.android.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread

/** A source of raw orientation samples, decoupled from the platform sensor so [OrientationEngine]
 *  can be driven by canned data in tests and by a real sensor on device. */
fun interface SampleSource {
    fun start(onSample: (values: FloatArray, timestampNs: Long) -> Unit)
    /** Default no-op so a lambda SampleSource (test/fake) need not implement teardown. */
    fun stop() {}
}

/** Real device sample source: registers [Sensor.TYPE_ROTATION_VECTOR] at
 *  [SensorManager.SENSOR_DELAY_FASTEST] and delivers events on a dedicated [HandlerThread] so the
 *  callback never runs on the main thread. [sensorManager] is injected for testability.
 *
 *  The sensor values array is the OS-owned [SensorEvent.values]; consumers must read it
 *  synchronously inside the callback (do not retain the reference across samples). */
class SensorReader(private val sensorManager: SensorManager) : SampleSource {

    private var thread: HandlerThread? = null
    private var listener: SensorEventListener? = null

    override fun start(onSample: (values: FloatArray, timestampNs: Long) -> Unit) {
        if (thread != null) return // already started
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return

        val handlerThread = HandlerThread("orientation-sensor").apply { start() }
        val handler = Handler(handlerThread.looper)
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                onSample(event.values, event.timestamp)
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }

        thread = handlerThread
        listener = l
        sensorManager.registerListener(l, sensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
    }

    override fun stop() {
        listener?.let { sensorManager.unregisterListener(it) }
        listener = null
        thread?.quitSafely()
        thread = null
    }
}
