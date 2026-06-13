package com.arashivision.sdk.demo.ui.capture

import android.app.Application
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric гоняет НАСТОЯЩУЮ нативную математику Android (getRotationMatrixFromVector,
 * remapCoordinateSystem, getOrientation). Точные углы зависят от remap-конвенции, поэтому
 * проверяем СВОЙСТВА обёртки (детерминизм, диапазоны, чувствительность к входу), а не
 * угаданные значения.
 *
 * Robolectric 4.13 поддерживает SDK ≤ 34 (проект targetSdk=35) → фиксируем 34.
 * application = Application::class — пустой Application вместо InstaApp, чтобы Robolectric
 * не вызывал InstaMediaSDK.init() с нативной c++_shared (её нет в JVM).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidRotationMatrixMathTest {
    private val math = AndroidRotationMatrixMath()

    private fun quat(axisX: Float, axisY: Float, axisZ: Float, deg: Double): FloatArray {
        val half = Math.toRadians(deg) / 2.0
        val s = Math.sin(half).toFloat()
        return floatArrayOf(axisX * s, axisY * s, axisZ * s, Math.cos(half).toFloat())
    }

    @Test
    fun `produces finite angles within valid ranges`() {
        val so = math.fromRotationVector(quat(0f, 0f, 1f, 30.0), Surface.ROTATION_0)
        assertTrue("yaw in [-180,180]", so.rawYawDeg in -180f..180f)
        assertTrue("pitch in [-180,180]", so.rawPitchDeg in -180f..180f)
        assertTrue("roll in [-180,180]", so.rawRollDeg in -180f..180f)
        // кватернион нормирован
        assertEquals(1f, so.quaternion.magnitude(), 1e-3f)
    }

    @Test
    fun `is deterministic for the same input`() {
        val v = quat(0f, 0f, 1f, 45.0)
        val a = math.fromRotationVector(v.copyOf(), Surface.ROTATION_0)
        val b = math.fromRotationVector(v.copyOf(), Surface.ROTATION_0)
        assertEquals(a.rawYawDeg, b.rawYawDeg, 1e-4f)
        assertEquals(a.rawPitchDeg, b.rawPitchDeg, 1e-4f)
    }

    @Test
    fun `different tilt rotations yield different pitch`() {
        // Наклон вокруг X — живая ось pitch после remap (это подтвердил probe A:
        // именно сюда «утекает» движение). Разные углы наклона → разный pitch.
        val a = math.fromRotationVector(quat(1f, 0f, 0f, 20.0), Surface.ROTATION_0)
        val b = math.fromRotationVector(quat(1f, 0f, 0f, 60.0), Surface.ROTATION_0)
        assertNotEquals(a.rawPitchDeg, b.rawPitchDeg)
    }

    @Test
    fun `landscape rotation selects a different pitch component than portrait`() {
        // В ROTATION_90 pitch берётся из out[2], в ROTATION_0 — из out[1]; для наклона
        // вокруг X это даёт разные значения pitch (проверяем, что выбор компоненты работает).
        val tilt = quat(1f, 0f, 0f, 40.0)
        val portrait = math.fromRotationVector(tilt.copyOf(), Surface.ROTATION_0)
        val landscape = math.fromRotationVector(tilt.copyOf(), Surface.ROTATION_90)
        assertNotEquals(portrait.rawPitchDeg, landscape.rawPitchDeg)
    }
}
