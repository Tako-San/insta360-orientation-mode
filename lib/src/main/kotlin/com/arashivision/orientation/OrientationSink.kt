package com.arashivision.orientation

/**
 * Приёмник ориентации панорамы. Реализация применяет (yaw, pitch) к конкретному
 * вью-плееру; вызывающий код не знает деталей (рефлексия SDK прячется в реализации).
 */
interface OrientationSink {
    fun apply(yawDeg: Float, pitchDeg: Float)
}
