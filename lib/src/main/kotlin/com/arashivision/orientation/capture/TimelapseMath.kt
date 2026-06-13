package com.arashivision.orientation.capture

/**
 * Чистая (JVM) арифметика расчёта длительности готового timelapse-видео.
 *
 * Вынесено из CaptureViewModel.onCaptureTimeChanged ради тестируемости (там оно было
 * вперемешку с вызовами InstaCameraManager). SDK-типы сюда не протекают — на вход идут
 * уже извлечённые intervalNativeValue и fps.
 */
object TimelapseMath {
    /**
     * Длительность итогового видео (мс) для timelapse.
     *
     * Формула из исходника: ((captureTimeMs / intervalNativeValue) / fps) * 1000.
     * Возвращает 0, если interval или fps неположительны (защита от деления на ноль).
     *
     * @param captureTimeMs прошедшее время съёмки (мс)
     * @param intervalNativeValue нативное значение интервала кадров
     * @param fps кадров в секунду у выбранного разрешения
     */
    fun videoDurationMs(captureTimeMs: Long, intervalNativeValue: Int, fps: Int): Long {
        if (intervalNativeValue <= 0 || fps <= 0) return 0L
        return ((captureTimeMs / intervalNativeValue) / fps) * 1000
    }
}
