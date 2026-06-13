package com.arashivision.sdk.demo.ui.player

/**
 * Порт поворота сферы плеера к заданным yaw/pitch (градусы). Прячет конкретный механизм
 * (media3-рефлексия в [Media3SphericalOrientationSink]); в тестах подменяется фейком,
 * записывающим поданные углы.
 */
interface OrientationApplier {
    fun apply(yawDeg: Float, pitchDeg: Float)
}
