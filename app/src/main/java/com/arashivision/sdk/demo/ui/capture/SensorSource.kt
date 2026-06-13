package com.arashivision.sdk.demo.ui.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Port over the rotation sensor. Hides SensorManager / listener registration.
 * The production implementation listens to TYPE_ROTATION_VECTOR; in tests it is replaced by a fake
 * that calls onValues directly.
 */
interface SensorSource {
    /** Start listening. onValues receives event.values (the raw rotation vector). */
    fun start(onValues: (FloatArray) -> Unit)
    fun stop()
}

/** Production implementation on SensorManager (TYPE_ROTATION_VECTOR, SENSOR_DELAY_FASTEST). */
class AndroidSensorSource(context: Context) : SensorSource {
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var listener: SensorEventListener? = null

    override fun start(onValues: (FloatArray) -> Unit) {
        val s = sensor ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) = onValues(event.values)
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        listener = l
        sensorManager.registerListener(l, s, SensorManager.SENSOR_DELAY_FASTEST)
    }

    override fun stop() {
        listener?.let {
            try { sensorManager.unregisterListener(it) } catch (_: Throwable) {}
        }
        listener = null
    }
}
