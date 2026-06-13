# OrientationSink (#3) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: subagent-driven-development / executing-plans. Шаги — checkbox.

**Goal:** Заменить 3 дублированных молча-падающих блока рефлексии setYaw/setPitch единым типобезопасным OrientationSink (интерфейс в :lib) + ReflectiveOrientationSink (реализация в :app с кэшем Method и логированием).

**Architecture:** Интерфейс `OrientationSink` в :lib (чистый). `ReflectiveOrientationSink` в :app: lazy-кэш Method, лог при отсутствии метода, yawOffsetDeg для VR-IPD. 3 потребителя (CaptureActivity, VrManager, LocalSphericalPlayerActivity) используют sink вместо локальных applyTo.

**Tech Stack:** Kotlin, Android, XLog. Агент НЕ собирает :app (нет SDK) — верификация статическая + :lib:test. Сборка/камера — пользователь.

---

## ⚠️ Окружение

Правки в :app не компилируются/не проверяются агентом. Поведение применения углов идентично прежнему (те же setYaw/setPitch, тот же IPD-сдвиг). Реальную ориентацию/VR на камере проверяет пользователь. Гарантия агента — статика (grep, типы) + :lib:test (интерфейс компилируется).

## Task 1: Интерфейс OrientationSink в :lib

**Files:**
- Create: `lib/src/main/kotlin/com/arashivision/orientation/OrientationSink.kt`

- [ ] **Step 1: Создать интерфейс**

```kotlin
package com.arashivision.orientation

/**
 * Приёмник ориентации панорамы. Реализация применяет (yaw, pitch) к конкретному
 * вью-плееру; вызывающий код не знает деталей (рефлексия SDK прячется в реализации).
 */
interface OrientationSink {
    fun apply(yawDeg: Float, pitchDeg: Float)
}
```

- [ ] **Step 2: Commit**

```bash
git add lib/src/main/kotlin/com/arashivision/orientation/OrientationSink.kt
git commit -m "feat(#3): интерфейс OrientationSink в :lib"
```

## Task 2: ReflectiveOrientationSink в :app

**Files:**
- Create: `app/src/main/java/com/arashivision/sdk/demo/ui/player/ReflectiveOrientationSink.kt`

> Размещаем в пакете `ui.player`, т.к. используется и capture, и player. (Можно ui.capture — но player нейтральнее. Импорт в потребителях укажем явно.)

- [ ] **Step 1: Создать класс**

```kotlin
package com.arashivision.sdk.demo.ui.player

import com.arashivision.orientation.OrientationSink
import com.elvishew.xlog.Logger
import com.elvishew.xlog.XLog
import java.lang.reflect.Method

/**
 * OrientationSink поверх рефлексии: SDK-вью (InstaCapturePlayerView / SphericalGLSurfaceView)
 * имеют методы setYaw(float)/setPitch(float), недоступные в публичном API. Метод резолвится
 * и кэшируется один раз; отсутствие метода логируется (раньше глоталось молча).
 *
 * @param target вью-плеер
 * @param yawOffsetDeg добавляется к yaw (для VR-IPD-сдвига)
 */
class ReflectiveOrientationSink(
    private val target: Any,
    private val yawOffsetDeg: Float = 0f,
    private val logger: Logger = XLog.tag("ReflectiveOrientationSink").build()
) : OrientationSink {

    private var resolved = false
    private var setYaw: Method? = null
    private var setPitch: Method? = null

    private fun resolveOnce() {
        if (resolved) return
        resolved = true
        val cls = target.javaClass
        setYaw = try {
            cls.getMethod("setYaw", Float::class.javaPrimitiveType)
        } catch (e: NoSuchMethodException) {
            logger.w("setYaw not found on ${cls.name}: ${e.message}")
            null
        }
        setPitch = try {
            cls.getMethod("setPitch", Float::class.javaPrimitiveType)
        } catch (e: NoSuchMethodException) {
            logger.w("setPitch not found on ${cls.name}: ${e.message}")
            null
        }
    }

    override fun apply(yawDeg: Float, pitchDeg: Float) {
        resolveOnce()
        try {
            setYaw?.invoke(target, yawDeg + yawOffsetDeg)
            setPitch?.invoke(target, pitchDeg)
        } catch (e: Exception) {
            logger.e("apply orientation failed: ${e.message}")
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/arashivision/sdk/demo/ui/player/ReflectiveOrientationSink.kt
git commit -m "feat(#3): ReflectiveOrientationSink (кэш Method, лог отсутствия, IPD-offset)"
```

## Task 3: CaptureActivity → captureSink

**Files:**
- Modify: `app/src/main/java/com/arashivision/sdk/demo/ui/capture/CaptureActivity.kt`

- [ ] **Step 1: Добавить поле sink**

Рядом с другими полями класса CaptureActivity добавить (импорт `com.arashivision.sdk.demo.ui.player.ReflectiveOrientationSink`):
```kotlin
    private val captureSink by lazy { ReflectiveOrientationSink(binding.capturePlayerView) }
```

- [ ] **Step 2: Заменить тело tryApplyOrientationToPlayer**

Текущее (стр ~420-456):
```kotlin
    private fun tryApplyOrientationToPlayer(yawDeg: Float, pitchDeg: Float) {
        val pipelinePresent = try {
            binding.capturePlayerView.pipeline != null
        } catch (e: Exception) {
            false
        }
        if (!pipelinePresent) return

        fun applyTo(obj: Any?, yaw: Float, pitch: Float) {
            if (obj == null) return
            try {
                val cls = obj.javaClass
                try {
                    val mYaw = cls.getMethod("setYaw", Float::class.javaPrimitiveType)
                    mYaw.invoke(obj, yaw)
                } catch (e: NoSuchMethodException) { /* ignore */ }

                try {
                    val mPitch = cls.getMethod("setPitch", Float::class.javaPrimitiveType)
                    mPitch.invoke(obj, pitch)
                } catch (e: NoSuchMethodException) { /* ignore */ }

            } catch (e: Exception) {
                logger.e("applyTo error: ${e.message}")
            }
        }

        try {
            if (vrManager.isVrMode) {
                vrManager.applyOrientation(yawDeg, pitchDeg)
            } else {
                applyTo(binding.capturePlayerView, yawDeg, pitchDeg)
            }
        } catch (e: Exception) {
            logger.e("tryApplyOrientationToPlayer error: ${e.message}")
        }
    }
```
Заменить на:
```kotlin
    private fun tryApplyOrientationToPlayer(yawDeg: Float, pitchDeg: Float) {
        val pipelinePresent = try {
            binding.capturePlayerView.pipeline != null
        } catch (e: Exception) {
            false
        }
        if (!pipelinePresent) return

        try {
            if (vrManager.isVrMode) {
                vrManager.applyOrientation(yawDeg, pitchDeg)
            } else {
                captureSink.apply(yawDeg, pitchDeg)
            }
        } catch (e: Exception) {
            logger.e("tryApplyOrientationToPlayer error: ${e.message}")
        }
    }
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/arashivision/sdk/demo/ui/capture/CaptureActivity.kt
git commit -m "refactor(#3): CaptureActivity использует ReflectiveOrientationSink"
```

## Task 4: VrManager → rightSink

**Files:**
- Modify: `app/src/main/java/com/arashivision/sdk/demo/ui/capture/VrManager.kt`

- [ ] **Step 1: Добавить nullable-поле sink + импорт**

Импорт `com.arashivision.sdk.demo.ui.player.ReflectiveOrientationSink`. Рядом с `private var rightVrPlayer: InstaCapturePlayerView? = null` (стр 51) добавить:
```kotlin
    private var rightSink: ReflectiveOrientationSink? = null
```

- [ ] **Step 2: Заменить тело applyOrientation**

Текущее (стр ~282-302):
```kotlin
    fun applyOrientation(yawDeg: Float, pitchDeg: Float) {
        lastYawDeg = yawDeg
        lastPitchDeg = pitchDeg
        try {
            rightVrPlayer?.let { obj ->
                val cls = obj.javaClass
                try {
                    val mYaw = cls.getMethod("setYaw", Float::class.javaPrimitiveType)
                    mYaw.invoke(obj, yawDeg + vrIpdYawDeg)
                } catch (_: NoSuchMethodException) {
                }
                try {
                    val mPitch = cls.getMethod("setPitch", Float::class.javaPrimitiveType)
                    mPitch.invoke(obj, pitchDeg)
                } catch (_: NoSuchMethodException) {
                }
            }
        } catch (e: Exception) {
            logger.e("applyOrientation -> right player error: ${e.message}")
        }
    }
```
Заменить на:
```kotlin
    fun applyOrientation(yawDeg: Float, pitchDeg: Float) {
        lastYawDeg = yawDeg
        lastPitchDeg = pitchDeg
        val player = rightVrPlayer ?: return
        val sink = rightSink ?: ReflectiveOrientationSink(player, yawOffsetDeg = vrIpdYawDeg).also { rightSink = it }
        sink.apply(yawDeg, pitchDeg)
    }
```

- [ ] **Step 3: Сбросить sink при пересоздании/уничтожении плеера**

В `disableVrMode()` и `destroy()` (там, где `rightVrPlayer?.destroy()` / зануление) добавить `rightSink = null`. Найти:
```bash
grep -n "rightVrPlayer = null\|rightVrPlayer?.destroy" app/src/main/java/com/arashivision/sdk/demo/ui/capture/VrManager.kt
```
Рядом с занулением `rightVrPlayer` (или в destroy после destroy()) добавить `rightSink = null`. Если `rightVrPlayer` не зануляется явно — добавить зануление обоих в destroy():
```kotlin
        rightSink = null
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arashivision/sdk/demo/ui/capture/VrManager.kt
git commit -m "refactor(#3): VrManager использует ReflectiveOrientationSink (IPD через yawOffsetDeg)"
```

## Task 5: LocalSphericalPlayerActivity → playerSink

**Files:**
- Modify: `app/src/main/java/com/arashivision/sdk/demo/ui/player/LocalSphericalPlayerActivity.kt`

- [ ] **Step 1: Добавить поле sink**

Класс в пакете `ui.player` — импорт ReflectiveOrientationSink НЕ нужен (тот же пакет). Добавить поле:
```kotlin
    private val playerSink by lazy { ReflectiveOrientationSink(binding.sphericalView) }
```

- [ ] **Step 2: Заменить applyTo на sink в tryApplyOrientation**

Удалить локальную `fun applyTo(...)` (стр ~376-389) и заменить блок:
```kotlin
        try {
            // Apply raw Euler angles directly ...
            applyTo(binding.sphericalView, rawYawDeg, rawPitchDeg)
        } catch (e: Exception) {
            logger.e("tryApplyOrientation failed: ${e.message}")
        }
```
на:
```kotlin
        try {
            // Apply raw Euler angles directly — NOT the sensitivity-scaled params.
            // Media3's sensor is disabled, so this is the ONLY rotation source.
            playerSink.apply(rawYawDeg, rawPitchDeg)
        } catch (e: Exception) {
            logger.e("tryApplyOrientation failed: ${e.message}")
        }
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/arashivision/sdk/demo/ui/player/LocalSphericalPlayerActivity.kt
git commit -m "refactor(#3): LocalSphericalPlayerActivity использует ReflectiveOrientationSink"
```

## Task 6: Верификация и push

- [ ] **Step 1: Статическая проверка — рефлексия setYaw/setPitch убрана**

```bash
grep -rn "getMethod(\"setYaw\"\|getMethod(\"setPitch\"" app/src/main
```
Expected: ПУСТО (вся инлайн-рефлексия setYaw/setPitch теперь только в ReflectiveOrientationSink).

```bash
grep -rn "fun applyTo" app/src/main
```
Expected: пусто (локальные applyTo удалены).

```bash
grep -rn "ReflectiveOrientationSink\|OrientationSink" app/src/main lib/src/main
```
Expected: интерфейс в :lib, класс в :app, 3 потребителя (captureSink/rightSink/playerSink).

- [ ] **Step 2: :lib компилируется**

Run: `./gradlew :lib:test` (CI/пользователь) — интерфейс OrientationSink компилируется, тесты зелёные.

- [ ] **Step 3: Push**

```bash
git push origin refactoring
```
`assembleDebug` + проверка ОРИЕНТАЦИИ ПО ГИРОСКОПУ И VR НА КАМЕРЕ — пользователь (критично: это главная фича форка).

## Self-Review

- **Spec coverage:** интерфейс :lib → Task 1; ReflectiveOrientationSink → Task 2; 3 потребителя → Tasks 3,4,5; VR-IPD через yawOffsetDeg → Task 4; удаление applyTo/молчаливой рефлексии → Tasks 3-5 + grep Task 6.
- **Границы:** расчёт углов/clamp/gaze (currentGazeDirection в LocalSpherical) НЕ тронут — sink заменяет только сам вызов setYaw/setPitch. VR-копирование кадров не тронуто.
- **Type consistency:** `OrientationSink.apply(yawDeg, pitchDeg)` — единая сигнатура; `ReflectiveOrientationSink(target, yawOffsetDeg, logger)` — конструктор; потребители: captureSink/rightSink/playerSink.
- **Риск (высокий, непроверяемый):** VrManager — sink пересоздаётся вместе с rightVrPlayer (поле сбрасывается в null). lazy в Activity безопасен, т.к. view стабильны. Поведение углов идентично. Но компиляцию/камеру проверяет пользователь — слепая правка.
- **Placeholder scan:** весь код приведён; grep-проверки конкретны.
