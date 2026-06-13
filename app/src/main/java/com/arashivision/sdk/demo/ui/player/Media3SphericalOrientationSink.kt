package com.arashivision.sdk.demo.ui.player

import android.graphics.PointF
import com.elvishew.xlog.Logger
import com.elvishew.xlog.XLog
import java.lang.reflect.Method

/**
 * Поворот сферы в офлайн-плеере media3 (`SphericalGLSurfaceView`).
 *
 * У media3 `SphericalGLSurfaceView` НЕТ публичных setYaw/setPitch — именно поэтому
 * управление ракурсом по гироскопу никогда не работало в офлайне (старый
 * [ReflectiveOrientationSink] молча падал NoSuchMethodException на несуществующих методах).
 *
 * Реальный объект, который крутит сферу, — приватный GL-рендерер `SphericalGLSurfaceView$Renderer`
 * (хранится в поле `mRenderer` базового `GLSurfaceView`). Он реализует `TouchTracker$Listener`
 * с методом `onScrollChange(PointF)`, где `PointF.x` = yaw (градусы), `PointF.y` = pitch (градусы) —
 * это тот же канал, по которому сфера поворачивается от тача. Метод `synchronized`, поэтому
 * звать его можно с любого потока. Подача наших yaw/pitch из гиро туда поворачивает сферу
 * ровно так же, как ручное «перетаскивание».
 *
 * ВНИМАНИЕ (зонд A, диагностический): это рефлексия в приватный рендерер чужой библиотеки —
 * хрупко, при обновлении media3 может молча сломаться. Используется временно, чтобы проверить,
 * что наша математика гиро корректно крутит сферу и синхронна со стрелкой. Архитектурный
 * финал — свой GL-рендерер панорамы с единым источником ориентации.
 *
 * @param sphericalView media3 SphericalGLSurfaceView (GLSurfaceView-наследник)
 */
class Media3SphericalOrientationSink(
    private val sphericalView: Any,
    private val logger: Logger = XLog.tag("Media3OrientationSink").build()
) : OrientationApplier {

    private var resolved = false
    private var renderer: Any? = null
    private var onScrollChange: Method? = null
    private val point = PointF()
    private var applyLogCounter = 0

    private fun resolveOnce() {
        if (resolved) return
        resolved = true

        // mRenderer объявлен в android.opengl.GLSurfaceView (базовый класс).
        val rendererField = try {
            android.opengl.GLSurfaceView::class.java
                .getDeclaredField("mRenderer")
                .also { it.isAccessible = true }
        } catch (e: NoSuchFieldException) {
            logger.e("GLSurfaceView.mRenderer not found: ${e.message}")
            null
        }
        renderer = rendererField?.get(sphericalView)
        if (renderer == null) {
            logger.e("mRenderer is null on ${sphericalView.javaClass.name}")
            return
        }
        onScrollChange = try {
            renderer!!.javaClass.getMethod("onScrollChange", PointF::class.java)
        } catch (e: NoSuchMethodException) {
            logger.e("onScrollChange not found on ${renderer!!.javaClass.name}: ${e.message}")
            null
        }
        if (onScrollChange != null) {
            logger.d("Media3 renderer resolved OK: ${renderer!!.javaClass.name}")
        }
    }

    /**
     * Повернуть сферу к заданным yaw/pitch (градусы). Подаётся в приватный рендерер media3
     * тем же каналом, что и тач (onScrollChange). pitch media3 клампит к ±45° внутри себя.
     */
    override fun apply(yawDeg: Float, pitchDeg: Float) {
        resolveOnce()
        val method = onScrollChange ?: return
        val target = renderer ?: return
        try {
            point.set(yawDeg, pitchDeg)
            method.invoke(target, point)
            // Зонд A: периодический лог, чтобы по logcat убедиться, что углы доходят до сферы.
            if (applyLogCounter++ % 60 == 0) {
                logger.d("apply yaw=$yawDeg pitch=$pitchDeg → ${target.javaClass.simpleName}.onScrollChange")
            }
        } catch (e: Exception) {
            logger.e("apply orientation failed: ${e.message}")
        }
    }
}
