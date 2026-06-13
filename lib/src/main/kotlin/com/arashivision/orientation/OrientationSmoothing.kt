package com.arashivision.orientation

import kotlin.math.abs

/**
 * Адаптивное сглаживание углов ориентации (yaw/pitch в градусах).
 *
 * Чистая JVM-логика без Android-зависимостей — тестируется на JVM. Давит дрожание
 * датчика на покое (малая дельта → сильное сглаживание) и почти не сглаживает быстрые
 * повороты (большая дельта → alpha→1, нет задержки). Yaw обрабатывается с учётом
 * перехода через ±180°.
 *
 * @param alphaMin коэффициент сглаживания на покое (сильное сглаживание)
 * @param alphaMax коэффициент при быстром движении (без задержки)
 * @param speedFullDeg дельта (град/тик), при которой alpha достигает alphaMax
 */
class OrientationSmoothing(
    private val alphaMin: Float = 0.15f,
    private val alphaMax: Float = 1.0f,
    private val speedFullDeg: Float = 2.5f
) {
    private var smoothedYaw = 0f
    private var smoothedPitch = 0f
    private var initialized = false

    /** Сбросить состояние — следующий [update] примет входные углы как есть. */
    fun reset() {
        initialized = false
    }

    /**
     * Подать сырые углы, получить сглаженные. Первый вызов (или после [reset])
     * возвращает вход без изменений и инициализирует состояние.
     */
    fun update(rawYawDeg: Float, rawPitchDeg: Float): TargetOrientation {
        if (!initialized) {
            smoothedYaw = rawYawDeg
            smoothedPitch = rawPitchDeg
            initialized = true
            return TargetOrientation(smoothedYaw, smoothedPitch)
        }

        var yawDelta = rawYawDeg - smoothedYaw
        while (yawDelta > 180f) yawDelta -= 360f
        while (yawDelta < -180f) yawDelta += 360f
        val pitchDelta = rawPitchDeg - smoothedPitch

        smoothedYaw += yawDelta * adaptiveAlpha(yawDelta)
        smoothedPitch += pitchDelta * adaptiveAlpha(pitchDelta)
        return TargetOrientation(smoothedYaw, smoothedPitch)
    }

    /**
     * Коэффициент сглаживания в зависимости от скорости изменения угла (град/тик).
     * Линейная интерполяция alpha от [alphaMin] (покой) до [alphaMax] (быстрое движение)
     * по мере роста скорости от 0 до [speedFullDeg].
     */
    fun adaptiveAlpha(deltaDeg: Float): Float {
        val t = (abs(deltaDeg) / speedFullDeg).coerceIn(0f, 1f)
        return alphaMin + (alphaMax - alphaMin) * t
    }
}
