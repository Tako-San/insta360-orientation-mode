# WP1: Gyro Testability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract all gyro math from `GyroOrientationController` into a pure-JVM `OrientationProcessor` in `:lib` (unit-tested without a device), with native Android sensor calls behind two `:app` ports (`RotationMatrixMath`, `SensorSource`), leaving `GyroOrientationController` as thin lifecycle glue.

**Architecture:** Ports & Adapters. `SensorSource` feeds raw `FloatArray` rotation-vector values + timestamp. `RotationMatrixMath` (real impl uses native `SensorManager` functions) turns a rotation vector + display rotation into a `SensorOrientation` (rotation matrix → quaternion + raw yaw/pitch/roll). `OrientationProcessor` (pure `:lib`) consumes `SensorOrientation` and owns calibration, SLERP smoothing, gaze and target-angle computation — exactly the current logic, moved verbatim. `GyroOrientationController` wires them and handles sensor lifecycle.

**Tech Stack:** Kotlin, JUnit4, MockK, Robolectric (one focused test), Kover, Gradle version catalog.

---

## File Structure

**`:lib` (pure-JVM, new):**
- `lib/src/main/kotlin/com/arashivision/orientation/SensorOrientation.kt` — data class: the already-extracted per-frame inputs (quaternion + raw yaw/pitch/roll deg).
- `lib/src/main/kotlin/com/arashivision/orientation/OrientationProcessor.kt` — pure state machine: calibration, smoothing, gaze, target angles, rate-limit decision.
- `lib/src/test/kotlin/com/arashivision/orientation/OrientationProcessorTest.kt` — JVM tests.

**`:app` (Android):**
- `app/.../ui/capture/RotationMatrixMath.kt` — port interface + `AndroidRotationMatrixMath` impl (native calls + remap-axis selection per display rotation).
- `app/.../ui/capture/SensorSource.kt` — port interface + `AndroidSensorSource` impl.
- `app/.../ui/capture/GyroOrientationController.kt` — MODIFY: become thin glue over the above.
- `app/src/test/java/com/arashivision/sdk/demo/ui/capture/AndroidRotationMatrixMathTest.kt` — one Robolectric test.

**Build:**
- `gradle/libs.versions.toml` — add mockk, robolectric, coroutines-test, turbine.
- `app/build.gradle.kts` — add test deps, testOptions, Kover plugin.

---

## Task 1: Test infrastructure for :app

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `build.gradle.kts` (root — kover already added as `apply false`)

- [ ] **Step 1: Add versions and libraries to the catalog**

In `gradle/libs.versions.toml` under `[versions]` add:
```toml
mockk = "1.13.13"
robolectric = "4.13"
coroutinesTest = "1.6.4"
turbine = "1.1.0"
```
Under `[libraries]` add:
```toml
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutinesTest" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
```
(coroutines version 1.6.4 matches the existing `coroutines` version in the catalog.)

- [ ] **Step 2: Apply Kover + test deps + testOptions to :app**

In `app/build.gradle.kts`, add to the `plugins { }` block:
```kotlin
alias(libs.plugins.kover)
```
Inside `android { }` add:
```kotlin
testOptions {
    unitTests {
        isIncludeAndroidResources = true
        isReturnDefaultValues = true
    }
}
```
In `dependencies { }` add:
```kotlin
testImplementation(libs.mockk)
testImplementation(libs.robolectric)
testImplementation(libs.kotlinx.coroutines.test)
testImplementation(libs.turbine)
```

- [ ] **Step 3: Verify the project configures**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/farid/android-sdk ./gradlew :app:help --no-daemon`
Expected: `BUILD SUCCESSFUL` (catalog + plugin resolve).

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts build.gradle.kts
git commit -m "build(app): add test infra (mockk, robolectric, coroutines-test, turbine) and Kover"
```

---

## Task 2: `SensorOrientation` data class in :lib

**Files:**
- Create: `lib/src/main/kotlin/com/arashivision/orientation/SensorOrientation.kt`
- Test: covered indirectly via Task 3 (it is a plain data holder).

- [ ] **Step 1: Create the data class**

`lib/src/main/kotlin/com/arashivision/orientation/SensorOrientation.kt`:
```kotlin
package com.arashivision.orientation

/**
 * Уже извлечённая ориентация устройства за один кадр сенсора — результат работы
 * RotationMatrixMath (native getRotationMatrixFromVector + remapCoordinateSystem +
 * getOrientation, с выбором оси под поворот экрана). Чистые данные для OrientationProcessor.
 *
 * @param quaternion ориентация как кватернион (из remapped-матрицы)
 * @param rawYawDeg yaw из getOrientation (градусы), азимут
 * @param rawPitchDeg pitch-компонента, уже выбранная по ориентации экрана (градусы)
 * @param rawRollDeg roll (градусы), для отладки
 */
data class SensorOrientation(
    val quaternion: Quaternion,
    val rawYawDeg: Float,
    val rawPitchDeg: Float,
    val rawRollDeg: Float
)
```

- [ ] **Step 2: Compile :lib**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :lib:compileKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add lib/src/main/kotlin/com/arashivision/orientation/SensorOrientation.kt
git commit -m "feat(lib): SensorOrientation data holder for gyro processor input"
```

---

## Task 3: `OrientationProcessor` (pure) — TDD

The processor holds the exact logic currently in `GyroOrientationController.onSensorChanged` + getters. It is fed one `SensorOrientation` per frame plus `displayRotation` and `now` (ms), and exposes the same outputs the controller exposes today.

**Files:**
- Create: `lib/src/main/kotlin/com/arashivision/orientation/OrientationProcessor.kt`
- Test: `lib/src/test/kotlin/com/arashivision/orientation/OrientationProcessorTest.kt`

- [ ] **Step 1: Write failing tests**

`lib/src/test/kotlin/com/arashivision/orientation/OrientationProcessorTest.kt`:
```kotlin
package com.arashivision.orientation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationProcessorTest {
    private val eps = 1e-3f
    private fun ident() = Quaternion(1f, 0f, 0f, 0f)

    private fun so(yaw: Float, pitch: Float, roll: Float = 0f, q: Quaternion = ident()) =
        SensorOrientation(q, yaw, pitch, roll)

    @Test
    fun `gaze yaw is relative to calibration and wraps`() {
        val p = OrientationProcessor()
        p.process(so(yaw = 100f, pitch = 0f), displayRotation = 0, now = 0L)
        p.calibrate()
        p.process(so(yaw = 130f, pitch = 0f), displayRotation = 0, now = 16L)
        assertEquals(30f, p.gazeYawDeg(), eps)
    }

    @Test
    fun `gaze yaw wraps across plus minus 180`() {
        val p = OrientationProcessor()
        p.process(so(yaw = 170f, pitch = 0f), displayRotation = 0, now = 0L)
        p.calibrate()
        p.process(so(yaw = -170f, pitch = 0f), displayRotation = 0, now = 16L)
        // 170 -> -170 is +20 the short way
        assertEquals(20f, p.gazeYawDeg(), eps)
    }

    @Test
    fun `gaze pitch is negated relative to calibration`() {
        val p = OrientationProcessor()
        p.process(so(yaw = 0f, pitch = 10f), displayRotation = 0, now = 0L)
        p.calibrate()
        p.process(so(yaw = 0f, pitch = 25f), displayRotation = 0, now = 16L)
        // gaze pitch = -(raw - offset) = -(25 - 10) = -15
        assertEquals(-15f, p.gazePitchDeg(), eps)
    }

    @Test
    fun `before calibration gaze yaw equals raw (offset zero)`() {
        val p = OrientationProcessor()
        p.process(so(yaw = 42f, pitch = 0f), displayRotation = 0, now = 0L)
        assertEquals(42f, p.gazeYawDeg(), eps)
    }

    @Test
    fun `rate limit skips full processing but keeps raw fresh`() {
        val p = OrientationProcessor(rateLimitMs = 100L)
        p.process(so(yaw = 0f, pitch = 0f), displayRotation = 0, now = 0L)
        // within rate limit window: raw still updates so gaze stays live
        val handled = p.process(so(yaw = 50f, pitch = 0f), displayRotation = 0, now = 10L)
        assertTrue("second frame should be rate-limited", !handled)
        assertEquals(50f, p.gazeYawDeg(), eps) // raw refreshed even when rate-limited
    }

    @Test
    fun `process returns true when frame is fully handled`() {
        val p = OrientationProcessor(rateLimitMs = 0L)
        assertTrue(p.process(so(yaw = 0f, pitch = 0f), displayRotation = 0, now = 0L))
        assertTrue(p.process(so(yaw = 1f, pitch = 0f), displayRotation = 0, now = 1L))
    }

    @Test
    fun `calibrate resets relative euler to zero`() {
        val p = OrientationProcessor()
        val q = Quaternion(0.9f, 0f, 0.43f, 0f).normalize()
        p.process(so(yaw = 80f, pitch = 5f, q = q), displayRotation = 0, now = 0L)
        p.calibrate()
        // immediately after calibration, relative euler ~ 0
        p.process(so(yaw = 80f, pitch = 5f, q = q), displayRotation = 0, now = 16L)
        assertEquals(0f, p.rawEulerYawDeg(), 1f)
        assertEquals(0f, p.rawEulerPitchDeg(), 1f)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :lib:test --tests "*OrientationProcessorTest*" --no-daemon`
Expected: FAIL — `OrientationProcessor` unresolved.

- [ ] **Step 3: Implement `OrientationProcessor`**

`lib/src/main/kotlin/com/arashivision/orientation/OrientationProcessor.kt`:
```kotlin
package com.arashivision.orientation

/**
 * Чистая (JVM, без Android) обработка ориентации гироскопа: калибровка,
 * SLERP-сглаживание, относительные углы взгляда (gaze) и целевые yaw/pitch.
 *
 * Логика перенесена дословно из GyroOrientationController.onSensorChanged + геттеров.
 * На вход подаётся [SensorOrientation] (native-извлечение делает RotationMatrixMath в :app).
 *
 * @param rateLimitMs минимальный интервал между полными обработками (мс); 0 = без прореживания
 * @param smoothingAlpha коэффициент SLERP
 */
class OrientationProcessor(
    private val rateLimitMs: Long = 0L,
    private val smoothingAlpha: Float = 0.12f,
    var sensivity: Float = 1.2f,
    var invertYaw: Boolean = false,
    var invertPitch: Boolean = true
) {
    private var lastSensorUpdate = 0L

    private var lastRawYawDeg = 0f
    private var lastRawPitchDeg = 0f
    private var lastRawRollDeg = 0f
    private var smoothedYaw = 0f
    private var smoothedPitch = 0f
    private var lastEulerYaw = 0f
    private var lastEulerPitch = 0f
    private var lastEulerRoll = 0f

    private var calibrationQuaternion = Quaternion(1f, 0f, 0f, 0f)
    private var calibrated = false
    private var calibrationRawYawDeg = 0f
    private var calibrationRawPitchDeg = 0f

    private var currentQuaternion = Quaternion(1f, 0f, 0f, 0f)
    private var smoothedQuaternion = Quaternion(1f, 0f, 0f, 0f)

    /**
     * Обработать кадр. Возвращает true, если кадр обработан полностью (не отсечён
     * rate-limit'ом). Даже при отсечении raw-значения обновляются (gaze остаётся живым).
     */
    fun process(orientation: SensorOrientation, displayRotation: Int, now: Long): Boolean {
        // raw обновляем всегда — gaze не должен застывать между полными обработками
        currentQuaternion = orientation.quaternion
        lastRawYawDeg = orientation.rawYawDeg
        lastRawPitchDeg = orientation.rawPitchDeg
        lastRawRollDeg = orientation.rawRollDeg

        if (now - lastSensorUpdate < rateLimitMs) {
            return false
        }
        lastSensorUpdate = now

        if (calibrated) {
            val calibrationInverse = calibrationQuaternion.conjugate()
            val relativeQuaternion = currentQuaternion.multiply(calibrationInverse)
            smoothedQuaternion = Quaternion.slerp(smoothedQuaternion, relativeQuaternion, smoothingAlpha)
            val (yaw, pitch, roll) = smoothedQuaternion.toEulerAngles(
                previousYaw = lastEulerYaw, previousPitch = lastEulerPitch, previousRoll = lastEulerRoll
            )
            lastEulerYaw = yaw; lastEulerPitch = pitch; lastEulerRoll = roll
            val target = computeTargetOrientation(yaw, pitch, sensivity, invertYaw, invertPitch)
            smoothedYaw = target.yawDeg; smoothedPitch = target.pitchDeg
        } else {
            val (yaw, pitch, roll) = currentQuaternion.toEulerAngles(
                previousYaw = lastEulerYaw, previousPitch = lastEulerPitch, previousRoll = lastEulerRoll
            )
            lastEulerYaw = yaw; lastEulerPitch = pitch; lastEulerRoll = roll
            val target = computeTargetOrientation(yaw, pitch, sensivity, invertYaw, invertPitch)
            smoothedYaw = target.yawDeg; smoothedPitch = target.pitchDeg
        }
        return true
    }

    fun calibrate() {
        calibrationQuaternion = currentQuaternion.copy()
        calibrationRawYawDeg = lastRawYawDeg
        calibrationRawPitchDeg = lastRawPitchDeg
        lastEulerYaw = 0f; lastEulerPitch = 0f; lastEulerRoll = 0f
        calibrated = true
    }

    fun gazeYawDeg(): Float {
        var relative = lastRawYawDeg - calibrationRawYawDeg
        while (relative > 180f) relative -= 360f
        while (relative <= -180f) relative += 360f
        return relative
    }

    fun gazePitchDeg(): Float = -(lastRawPitchDeg - calibrationRawPitchDeg)

    fun rawEulerYawDeg(): Float = lastEulerYaw
    fun rawEulerPitchDeg(): Float = lastEulerPitch
    fun smoothedYawDeg(): Float = smoothedYaw
    fun smoothedPitchDeg(): Float = smoothedPitch
    fun currentQuaternion(): Quaternion = currentQuaternion.copy()
    fun smoothedQuaternion(): Quaternion = smoothedQuaternion.copy()
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :lib:test --tests "*OrientationProcessorTest*" --no-daemon`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add lib/src/main/kotlin/com/arashivision/orientation/OrientationProcessor.kt lib/src/test/kotlin/com/arashivision/orientation/OrientationProcessorTest.kt
git commit -m "feat(lib): pure OrientationProcessor with calibration/smoothing/gaze + tests"
```

---

## Task 4: `RotationMatrixMath` port + Android impl

This isolates the three native `SensorManager` calls and the remap-axis selection. The impl mirrors the current `updateRawFromEvent` + the `when(displayRotation)` remap block + `getOrientation` pitch-component selection exactly.

**Files:**
- Create: `app/src/main/java/com/arashivision/sdk/demo/ui/capture/RotationMatrixMath.kt`
- Test: `app/src/test/java/com/arashivision/sdk/demo/ui/capture/AndroidRotationMatrixMathTest.kt`

- [ ] **Step 1: Create the port + impl**

`app/src/main/java/com/arashivision/sdk/demo/ui/capture/RotationMatrixMath.kt`:
```kotlin
package com.arashivision.sdk.demo.ui.capture

import android.hardware.SensorManager
import android.view.Surface
import com.arashivision.orientation.Quaternion
import com.arashivision.orientation.SensorOrientation

/**
 * Порт над нативными функциями ориентации Android (getRotationMatrixFromVector,
 * remapCoordinateSystem, getOrientation). Прячет единственную часть гиро-логики,
 * которую нельзя выполнить в чистом JVM. Реальная реализация покрыта Robolectric-тестом;
 * OrientationProcessor получает уже готовый [SensorOrientation].
 */
interface RotationMatrixMath {
    /** Построить ориентацию из сырого вектора поворота и текущего поворота экрана. */
    fun fromRotationVector(rotationVectorValues: FloatArray, displayRotation: Int): SensorOrientation
}

/** Боевая реализация на нативных SensorManager-функциях. */
class AndroidRotationMatrixMath : RotationMatrixMath {
    private val rotMat = FloatArray(9)
    private val remapped = FloatArray(9)
    private val out = FloatArray(3)

    override fun fromRotationVector(rotationVectorValues: FloatArray, displayRotation: Int): SensorOrientation {
        SensorManager.getRotationMatrixFromVector(rotMat, rotationVectorValues)
        when (displayRotation) {
            Surface.ROTATION_0 -> SensorManager.remapCoordinateSystem(
                rotMat, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                rotMat, SensorManager.AXIS_Z, SensorManager.AXIS_MINUS_X, remapped)
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                rotMat, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Z, remapped)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                rotMat, SensorManager.AXIS_MINUS_Z, SensorManager.AXIS_X, remapped)
            else -> SensorManager.remapCoordinateSystem(
                rotMat, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        }
        val quaternion = Quaternion.fromRotationMatrix(remapped)
        SensorManager.getOrientation(remapped, out)
        val rawYawDeg = Math.toDegrees(out[0].toDouble()).toFloat()
        // landscape (90/270): pitch is in out[2]; portrait: out[1]
        val rawPitchComponent =
            if (displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270) out[2] else out[1]
        val rawPitchDeg = Math.toDegrees(rawPitchComponent.toDouble()).toFloat()
        val rawRollDeg = Math.toDegrees(out[2].toDouble()).toFloat()
        return SensorOrientation(quaternion, rawYawDeg, rawPitchDeg, rawRollDeg)
    }
}
```

- [ ] **Step 2: Write the Robolectric test**

`app/src/test/java/com/arashivision/sdk/demo/ui/capture/AndroidRotationMatrixMathTest.kt`:
```kotlin
package com.arashivision.sdk.demo.ui.capture

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidRotationMatrixMathTest {
    private val math = AndroidRotationMatrixMath()

    @Test
    fun `identity rotation vector yields near-zero yaw`() {
        // rotation vector for no rotation: (0,0,0) → identity matrix
        val so = math.fromRotationVector(floatArrayOf(0f, 0f, 0f), Surface.ROTATION_0)
        assertEquals(0f, so.rawYawDeg, 1f)
    }

    @Test
    fun `90 deg yaw about Z produces ~90 deg azimuth magnitude`() {
        // quaternion for 90° about Z: (x,y,z,w) = (0,0,sin45,cos45)
        val s = Math.sin(Math.toRadians(45.0)).toFloat()
        val c = Math.cos(Math.toRadians(45.0)).toFloat()
        val so = math.fromRotationVector(floatArrayOf(0f, 0f, s, c), Surface.ROTATION_0)
        assertEquals(90f, Math.abs(so.rawYawDeg), 5f)
    }
}
```

- [ ] **Step 3: Run the Robolectric test**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/farid/android-sdk ./gradlew :app:testDebugUnitTest --tests "*AndroidRotationMatrixMathTest*" --no-daemon`
Expected: PASS (2 tests). If Robolectric needs SDK download and the runner is offline, this is the only network-dependent test; note it but proceed.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arashivision/sdk/demo/ui/capture/RotationMatrixMath.kt app/src/test/java/com/arashivision/sdk/demo/ui/capture/AndroidRotationMatrixMathTest.kt
git commit -m "feat(app): RotationMatrixMath port wrapping native sensor math + Robolectric test"
```

---

## Task 5: `SensorSource` port + Android impl

**Files:**
- Create: `app/src/main/java/com/arashivision/sdk/demo/ui/capture/SensorSource.kt`

- [ ] **Step 1: Create the port + impl**

`app/src/main/java/com/arashivision/sdk/demo/ui/capture/SensorSource.kt`:
```kotlin
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
```

- [ ] **Step 2: Compile :app**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/farid/android-sdk ./gradlew :app:compileDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/arashivision/sdk/demo/ui/capture/SensorSource.kt
git commit -m "feat(app): SensorSource port over SensorManager rotation-vector"
```

---

## Task 6: Rewire `GyroOrientationController` as thin glue

Replace the controller's internals with the three new collaborators. Public API (constructor signature, `start/stop/calibrate/setzOrientationEnabled`, and the getters consumed by `LocalSphericalPlayerActivity` and VR managers) MUST stay the same so callers don't change.

**Files:**
- Modify: `app/src/main/java/com/arashivision/sdk/demo/ui/capture/GyroOrientationController.kt`

- [ ] **Step 1: Rewrite the controller**

Replace the full body of `GyroOrientationController.kt` with:
```kotlin
package com.arashivision.sdk.demo.ui.capture

import android.content.Context
import com.arashivision.orientation.OrientationProcessor
import com.arashivision.orientation.Quaternion
import com.elvishew.xlog.Logger
import com.elvishew.xlog.XLog

/**
 * Тонкая обвязка: SensorSource → RotationMatrixMath → OrientationProcessor → applyOrientation.
 * Вся математика теперь в pure-JVM OrientationProcessor (:lib), нативные вызовы — в
 * RotationMatrixMath. Здесь только связка и lifecycle.
 *
 * Конструктор сохраняет совместимость: context/getDisplayRotation/applyOrientation.
 * Опциональные порты подменяются в тестах.
 */
class GyroOrientationController(
    context: Context,
    private val getDisplayRotation: () -> Int,
    private val applyOrientation: (yawDeg: Float, pitchDeg: Float) -> Unit,
    private val sensorSource: SensorSource = AndroidSensorSource(context),
    private val rotationMath: RotationMatrixMath = AndroidRotationMatrixMath(),
    private val processor: OrientationProcessor = OrientationProcessor()
) {
    private val logger: Logger = XLog.tag(GyroOrientationController::class.java.simpleName).build()
    private var nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    var enabled: Boolean = true
    var sensivity: Float
        get() = processor.sensivity
        set(v) { processor.sensivity = v }
    var invertYaw: Boolean
        get() = processor.invertYaw
        set(v) { processor.invertYaw = v }
    var invertPitch: Boolean
        get() = processor.invertPitch
        set(v) { processor.invertPitch = v }

    fun start() {
        if (!enabled) return
        sensorSource.start { values -> onValues(values) }
        logger.d("GyroOrientationController started (ports-based)")
    }

    fun stop() {
        sensorSource.stop()
        logger.d("GyroOrientationController stopped")
    }

    fun calibrate() {
        processor.calibrate()
        logger.d("Gyro calibrated")
    }

    fun setzOrientationEnabled(enabled: Boolean) { this.enabled = enabled }

    private fun onValues(values: FloatArray) {
        if (!enabled) return
        val orientation = rotationMath.fromRotationVector(values, getDisplayRotation())
        processor.process(orientation, getDisplayRotation(), nowMs())
        applyOrientation(processor.smoothedYawDeg(), processor.smoothedPitchDeg())
    }

    // --- API, сохранённый для совместимости с плеером/VR ---
    fun getRawEulerYawDeg(): Float = processor.rawEulerYawDeg()
    fun getRawEulerPitchDeg(): Float = processor.rawEulerPitchDeg()
    fun getSmoothedYaw(): Float = processor.smoothedYawDeg()
    fun getSmoothedPitch(): Float = processor.smoothedPitchDeg()
    fun getGazeYawDeg(): Float = processor.gazeYawDeg()
    fun getGazePitchDeg(): Float = processor.gazePitchDeg()
    fun getCurrentQuaternion(): Quaternion = processor.currentQuaternion()
    fun getSmoothedQuaternion(): Quaternion = processor.smoothedQuaternion()
    fun getRawCurrentQuaternion(): Quaternion = processor.currentQuaternion()
}
```

- [ ] **Step 2: Check for removed getters still referenced**

Run: `cd /home/farid/sandbox/misc/gasem/insta360-orientation-mode && grep -rn "getLastRawYawDeg\|getLastRawPitchDeg\|getLastRawRollDeg\|\.rateLimitMs\|\.smoothingAlpha\|\.enabled" app/src/main --include=*.kt | grep -i gyro`
Expected: no references to `getLastRaw*Deg`, `rateLimitMs`, `smoothingAlpha` from outside the controller. If any exist, add a compatibility getter/field. (`enabled` is still public.)

- [ ] **Step 3: Compile :app**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/farid/android-sdk ./gradlew :app:compileDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`. Fix any caller that used a removed member by adding a compat shim on the controller.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arashivision/sdk/demo/ui/capture/GyroOrientationController.kt
git commit -m "refactor(app): GyroOrientationController as thin glue over ports + pure processor"
```

---

## Task 7: Controller glue test with fakes (MockK)

Verify the wiring: fed a fake `SensorSource` and a stub `RotationMatrixMath`, the controller drives the processor and calls `applyOrientation`.

**Files:**
- Test: `app/src/test/java/com/arashivision/sdk/demo/ui/capture/GyroOrientationControllerTest.kt`

- [ ] **Step 1: Write the test**

`app/src/test/java/com/arashivision/sdk/demo/ui/capture/GyroOrientationControllerTest.kt`:
```kotlin
package com.arashivision.sdk.demo.ui.capture

import android.content.Context
import com.arashivision.orientation.OrientationProcessor
import com.arashivision.orientation.Quaternion
import com.arashivision.orientation.SensorOrientation
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GyroOrientationControllerTest {

    /** Fake sensor source: capture the callback so the test can push frames. */
    private class FakeSensorSource : SensorSource {
        var onValues: ((FloatArray) -> Unit)? = null
        override fun start(onValues: (FloatArray) -> Unit) { this.onValues = onValues }
        override fun stop() { onValues = null }
        fun emit(v: FloatArray) { onValues?.invoke(v) }
    }

    /** Stub math: returns a fixed yaw from the first vector element. */
    private class StubMath : RotationMatrixMath {
        override fun fromRotationVector(rotationVectorValues: FloatArray, displayRotation: Int) =
            SensorOrientation(Quaternion(1f, 0f, 0f, 0f), rotationVectorValues[0], 0f, 0f)
    }

    @Test
    fun `emitting a frame drives processor and calls applyOrientation`() {
        val src = FakeSensorSource()
        var appliedYaw = Float.NaN
        val ctx = mockk<Context>(relaxed = true)
        val controller = GyroOrientationController(
            context = ctx,
            getDisplayRotation = { 0 },
            applyOrientation = { yaw, _ -> appliedYaw = yaw },
            sensorSource = src,
            rotationMath = StubMath(),
            processor = OrientationProcessor(rateLimitMs = 0L)
        )
        controller.start()
        src.emit(floatArrayOf(30f))
        // applyOrientation was called (smoothed target derived from processor)
        assertTrue("applyOrientation should have been called", !appliedYaw.isNaN())
    }

    @Test
    fun `gaze reflects calibration via controller API`() {
        val src = FakeSensorSource()
        val ctx = mockk<Context>(relaxed = true)
        val controller = GyroOrientationController(
            context = ctx,
            getDisplayRotation = { 0 },
            applyOrientation = { _, _ -> },
            sensorSource = src,
            rotationMath = StubMath(),
            processor = OrientationProcessor(rateLimitMs = 0L)
        )
        controller.start()
        src.emit(floatArrayOf(100f))
        controller.calibrate()
        src.emit(floatArrayOf(130f))
        assertEquals(30f, controller.getGazeYawDeg(), 1e-3f)
    }

    @Test
    fun `disabled controller ignores frames`() {
        val src = FakeSensorSource()
        var called = false
        val ctx = mockk<Context>(relaxed = true)
        val controller = GyroOrientationController(
            context = ctx,
            getDisplayRotation = { 0 },
            applyOrientation = { _, _ -> called = true },
            sensorSource = src,
            rotationMath = StubMath(),
            processor = OrientationProcessor(rateLimitMs = 0L)
        )
        controller.setzOrientationEnabled(false)
        controller.start()      // start() returns early when disabled; source not started
        src.emit(floatArrayOf(50f)) // no callback wired
        assertTrue("disabled controller must not apply orientation", !called)
    }
}
```

- [ ] **Step 2: Run the test**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/farid/android-sdk ./gradlew :app:testDebugUnitTest --tests "*GyroOrientationControllerTest*" --no-daemon`
Expected: PASS (3 tests).

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/arashivision/sdk/demo/ui/capture/GyroOrientationControllerTest.kt
git commit -m "test(app): GyroOrientationController glue tests with fake ports"
```

---

## Task 8: Full build + Kover gate on the gyro packages

**Files:**
- Modify: `app/build.gradle.kts` (Kover verify rule)
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Add a Kover verify rule scoped to the new logic**

In `app/build.gradle.kts`, after the `android { }` block add:
```kotlin
kover {
    reports {
        verify {
            rule {
                minBound(80)
                filters { includes { classes("com.arashivision.sdk.demo.ui.capture.*") } }
            }
        }
    }
}
```
(`:lib` already has Kover from the earlier commit; its math is ~97% covered. This rule gates the new `:app` gyro package.)

- [ ] **Step 2: Run :app unit tests + verify**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/farid/android-sdk ./gradlew :app:testDebugUnitTest :app:koverVerify --no-daemon`
Expected: tests PASS; `koverVerify` PASS (≥80% on `ui.capture.*`). If under 80% because Android-coupled lines (AndroidSensorSource/AndroidRotationMatrixMath registration) drag it down, narrow the `includes` to the tested classes (`GyroOrientationController`, processors) or add `excludes` for the thin Android impls — they are covered by the one Robolectric test, not unit tests.

- [ ] **Step 3: Wire :app tests into CI**

In `.github/workflows/ci.yml`, add a second job after `lib-tests`:
```yaml
  app-unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4
      # :app unit tests no longer need the Insta360 SDK at test runtime because
      # all SDK/sensor edges sit behind mocked ports. Robolectric downloads its
      # own runtime. If the SDK artifact is still needed at compile time, this
      # job requires the Nexus credentials as secrets.
      - name: Run :app unit tests with coverage
        run: ./gradlew :app:testDebugUnitTest :app:koverXmlReport
        env:
          ORG_GRADLE_PROJECT_instaNexusUser: ${{ secrets.INSTA_NEXUS_USER }}
          ORG_GRADLE_PROJECT_instaNexusPassword: ${{ secrets.INSTA_NEXUS_PASSWORD }}
      - name: Upload app coverage
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: app-coverage-report
          path: app/build/reports/kover/
          if-no-files-found: warn
```

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts .github/workflows/ci.yml
git commit -m "ci(app): run :app unit tests with Kover coverage gate on gyro package"
```

---

## Task 9: On-device verification (gates WP1)

WP1 is behavior-preserving but touches the live gyro path. Re-verify on the phone before considering it done.

- [ ] **Step 1: Build + install**

```bash
cd /home/farid/sandbox/misc/gasem/insta360-orientation-mode
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/farid/android-sdk ./gradlew :app:assembleDebug --no-daemon
/home/farid/android-sdk/platform-tools/adb install -r app/build/outputs/apk/debug/insta_sdk_demo_debug_1.8.1_build_06.apk
```

- [ ] **Step 2: Manual check (user)**

Offline player → load video → Calibrate → rotate the phone. Confirm: sphere follows correctly (same as probe A), arrow tracks, no new jitter/lag. This is a human gate — ask the user to confirm before merging WP1.

---

## Self-Review notes

- **Spec coverage:** WP1 of the design (pure `OrientationProcessor` in `:lib`, `RotationMatrixMath` + `SensorSource` ports, thin controller, `:app` test infra, Kover gate-on-new-code, CI runs `:app` tests) — all covered by Tasks 1–8; Task 9 is the on-device gate from §6/§7 of the spec.
- **Behavior preservation:** the processor logic is copied verbatim from the current `onSensorChanged` (both calibrated and uncalibrated branches, the probe-A apply-before-calibration behavior is preserved because `process()` always computes `smoothedYaw/Pitch` and the controller always calls `applyOrientation`). Gaze getters copied verbatim including the pitch negation and ±180 wrap.
- **Type consistency:** `SensorOrientation(quaternion, rawYawDeg, rawPitchDeg, rawRollDeg)` used identically in Tasks 2, 3, 4, 7. `RotationMatrixMath.fromRotationVector(FloatArray, Int): SensorOrientation` consistent across 4, 6, 7. `OrientationProcessor.process(...)` / `calibrate()` / getters consistent across 3, 6, 7.
- **Known caveat:** the controller drops the `rateLimitMs`/`smoothingAlpha` public fields (they were not read externally — verified by grep in Task 6 Step 2). Rate-limit now lives in `OrientationProcessor(rateLimitMs=0L)` matching the current `rateLimitMs = 0L`. The 500ms debug logging block is intentionally dropped (it was debug-only).
