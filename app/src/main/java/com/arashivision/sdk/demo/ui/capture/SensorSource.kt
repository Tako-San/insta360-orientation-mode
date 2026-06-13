package com.arashivision.sdk.demo.ui.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Порт над сенсором поворота. Прячет SensorManager/регистрацию listener'а.
 * Боевая реализация слушает TYPE_ROTATION_VECTOR; в тестах подменяется фейком,
 * который вызывает onValues напрямую.
 */
interface SensorSource {
    /** Начать слушать. onValues получает event.values (сырой вектор поворота). */
    fun start(onValues: (FloatArray) -> Unit)
    fun stop()
}

/** Боевая реализация на SensorManager (TYPE_ROTATION_VECTOR, SENSOR_DELAY_FASTEST). */
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
