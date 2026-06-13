# ViewBinding-рефлексия (#9) + гигиена (#10) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development или executing-plans. Шаги — checkbox.

**Goal:** Укрепить ViewBindingUtils (кэш Method, сохранение cause, валидация) и навести гигиену (логи в молчаливые catch, разнесение CaptureConst) — без изменения поведения логики.

**Architecture:** Точечные правки в `:app`. #9 — кэш + диагностика в ViewBindingUtils. #10a — добавить XLog в молчаливые catch (control flow не меняется). #10b — механически вынести resId-резолверы из CaptureConst.kt в CaptureText.kt (тот же пакет, сигнатуры неизменны).

**Tech Stack:** Kotlin, Android, XLog. Агент НЕ собирает (нет SDK) — верификация статическая (grep) + `:lib:test` (не затронут). Сборка/камера — пользователь.

---

## Task 1: #9 — кэш Method + cause + валидация в ViewBindingUtils

**Files:**
- Modify: `app/src/main/java/com/arashivision/sdk/demo/util/ViewBindingUtils.kt`

- [ ] **Step 1: Переписать ViewBindingUtils с кэшем и диагностикой**

Полное новое содержимое файла:
```kotlin
package com.arashivision.sdk.demo.util

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.util.concurrent.ConcurrentHashMap

object ViewBindingUtils {

    // Кэш inflate-методов per binding-класс: getMethod() дорог и вызывается на каждый
    // onCreateViewHolder. Ключ — (binding class, withViewGroup).
    private val inflateWithGroupCache = ConcurrentHashMap<Class<*>, Method>()
    private val inflateNoGroupCache = ConcurrentHashMap<Class<*>, Method>()

    @Suppress("UNCHECKED_CAST")
    fun <T> createBinding(cls: Class<*>, layoutInflater: LayoutInflater?, index: Int, viewGroup: ViewGroup?): T {
        try {
            val tClass = getParameterizedTypeClass(cls, index)
            return viewGroup?.let {
                val method = inflateWithGroupCache.getOrPut(tClass) {
                    tClass.getMethod(
                        "inflate",
                        LayoutInflater::class.java,
                        ViewGroup::class.java,
                        Boolean::class.javaPrimitiveType
                    )
                }
                method.invoke(null, layoutInflater, viewGroup, false) as T
            } ?: run {
                val method = inflateNoGroupCache.getOrPut(tClass) {
                    tClass.getMethod("inflate", LayoutInflater::class.java)
                }
                method.invoke(null, layoutInflater) as T
            }
        } catch (e: NoSuchMethodException) {
            throw RuntimeException("ViewBinding inflate method not found for $cls[$index]", e)
        } catch (e: InvocationTargetException) {
            throw RuntimeException("ViewBinding inflate failed for $cls[$index]", e)
        } catch (e: IllegalAccessException) {
            throw RuntimeException("ViewBinding inflate inaccessible for $cls[$index]", e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : ViewModel> createViewModel(owner: ViewModelStoreOwner, index: Int): T {
        try {
            val tClass = getParameterizedTypeClass(owner.javaClass, index) as Class<T>
            return ViewModelProvider(owner)[tClass]
        } catch (e: Exception) {
            // Сохраняем cause/stacktrace (раньше терялось через RuntimeException(e.message)).
            throw RuntimeException("createViewModel failed for ${owner.javaClass}[$index]", e)
        }
    }

    private fun getParameterizedTypeClass(cls: Class<*>, index: Int): Class<*> {
        val generic = cls.genericSuperclass
        check(generic is ParameterizedType) {
            "Expected ${cls.name} to have a parameterized superclass, got $generic"
        }
        val args = generic.actualTypeArguments
        require(index in args.indices) {
            "Type argument index $index out of bounds for ${cls.name} (has ${args.size})"
        }
        val arg = args[index]
        check(arg is Class<*>) { "Type argument $index of ${cls.name} is not a Class: $arg" }
        return arg
    }
}
```

- [ ] **Step 2: Проверить, что API не изменился (потребители)**

Run:
```bash
grep -rn "ViewBindingUtils.createBinding\|ViewBindingUtils.createViewModel" app/src/main
```
Expected: вызовы есть (BaseActivity/BaseFragment/адаптеры), сигнатуры те же (`createBinding(cls, inflater, index, viewGroup)`, `createViewModel(owner, index)`) — менять потребителей НЕ нужно.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/arashivision/sdk/demo/util/ViewBindingUtils.kt
git commit -m "refactor(#9): кэш inflate-Method, сохранение cause, валидация ParameterizedType в ViewBindingUtils"
```

---

## Task 2: #10a — логи в молчаливые catch GyroOrientationController

**Files:**
- Modify: `app/src/main/java/com/arashivision/sdk/demo/ui/capture/GyroOrientationController.kt`

- [ ] **Step 1: Логировать в catch stop()**

Заменить (около стр 95):
```kotlin
        } catch (t: Throwable) {
            // ignore
        }
        logger.d("GyroOrientationController stopped")
```
на:
```kotlin
        } catch (t: Throwable) {
            logger.w("unregisterListener failed on stop: ${t.message}")
        }
        logger.d("GyroOrientationController stopped")
```

- [ ] **Step 2: Логировать в catch updateRawFromEvent()**

Заменить (около стр 238, в `updateRawFromEvent`):
```kotlin
        } catch (t: Throwable) {
            // ignore
        }
```
на:
```kotlin
        } catch (t: Throwable) {
            logger.w("getRotationMatrixFromVector failed: ${t.message}")
        }
```

> ПРИМЕЧАНИЕ: проверить точное расположение обоих catch перед правкой (`grep -n "// ignore" GyroOrientationController.kt`). Это ЕДИНСТВЕННЫЕ два молчаливых catch в файле; рефлексию не трогаем (её в этом файле нет — она в Activity/VrManager).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/arashivision/sdk/demo/ui/capture/GyroOrientationController.kt
git commit -m "refactor(#10): логировать молчаливые catch в GyroOrientationController"
```

---

## Task 3: #10a — логи в молчаливые catch VrManager/LocalVrManager

**Files:**
- Modify: `app/src/main/java/com/arashivision/sdk/demo/ui/capture/VrManager.kt`
- Modify: `app/src/main/java/com/arashivision/sdk/demo/ui/player/LocalVrManager.kt`

- [ ] **Step 1: Найти молчаливые (без лога) catch**

Run:
```bash
grep -n "catch" app/src/main/java/com/arashivision/sdk/demo/ui/capture/VrManager.kt app/src/main/java/com/arashivision/sdk/demo/ui/player/LocalVrManager.kt
```
Для КАЖДОГО catch проверить тело: если внутри уже есть `logger.e/w/d` — пропустить. Если тело пустое / `// ignore` / только control flow без лога — добавить `logger.w("<контекст>: ${e.message}")`.

ИСКЛЮЧЕНИЕ: `catch (_: NoSuchMethodException)` вокруг setYaw/setPitch (VrManager ~291,296) НЕ трогать — домен #3.

- [ ] **Step 2: Добавить логи в найденные молчаливые catch**

Для каждого молчаливого catch добавить строку лога с контекстом метода, в котором он находится (например в `destroy()` → `logger.w("VR destroy cleanup failed: ${e.message}")`). Control flow и тип исключения НЕ менять. Если у catch уже есть лог — оставить как есть.

- [ ] **Step 3: Проверить, что не осталось пустых catch (кроме #3-рефлексии)**

Run:
```bash
grep -n -A2 "catch" app/src/main/java/com/arashivision/sdk/demo/ui/capture/VrManager.kt app/src/main/java/com/arashivision/sdk/demo/ui/player/LocalVrManager.kt | grep -B1 "// ignore\|{ *}" 
```
Expected: пусто (нет молчаливых), либо только NoSuchMethodException-блоки рефлексии.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arashivision/sdk/demo/ui/capture/VrManager.kt app/src/main/java/com/arashivision/sdk/demo/ui/player/LocalVrManager.kt
git commit -m "refactor(#10): логировать молчаливые catch в VrManager/LocalVrManager"
```

---

## Task 4: #10b — вынести resId-резолверы в CaptureText.kt

**Files:**
- Create: `app/src/main/java/com/arashivision/sdk/demo/ui/capture/CaptureText.kt`
- Modify: `app/src/main/java/com/arashivision/sdk/demo/ui/capture/CaptureConst.kt`

- [ ] **Step 1: Прочитать CaptureConst.kt целиком**

Прочитать файл, выделить: (а) что остаётся в CaptureConst — `stepToLoadingTextMap`, `stepToErrorTextMap` (map-таблицы текстов init-шагов); (б) что переносится в CaptureText — функции `getCaptureModeTextResId`, `getCaptureSettingNameResId`, `getCaptureSettingValueName`.

- [ ] **Step 2: Создать CaptureText.kt с перенесёнными функциями**

Создать `app/src/main/java/com/arashivision/sdk/demo/ui/capture/CaptureText.kt`:
- package `com.arashivision.sdk.demo.ui.capture` (ТОТ ЖЕ — импорты потребителей не меняются).
- Перенести ДОСЛОВНО три функции (`getCaptureModeTextResId`, `getCaptureSettingNameResId`, `getCaptureSettingValueName`) со ВСЕМИ их импортами (R, SDK-модели CaptureMode/CaptureSetting/..., DecimalFormat/DecimalFormatSymbols/Locale, Context).
- Сигнатуры и тела НЕ менять.

- [ ] **Step 3: Удалить перенесённые функции из CaptureConst.kt**

Из `CaptureConst.kt` удалить три функции и осиротевшие импорты (оставить только те, что нужны для `stepToLoadingTextMap`/`stepToErrorTextMap` — это импорты CaptureEvent.InitStep.* и R). Прогнать глазами: какие SDK-импорты (CaptureMode, CaptureSetting, DecimalFormat...) больше не используются в оставшемся CaptureConst → удалить.

- [ ] **Step 4: Проверить потребителей и осиротевшие ссылки**

Run:
```bash
grep -rn "getCaptureModeTextResId\|getCaptureSettingNameResId\|getCaptureSettingValueName" app/src/main
grep -rn "stepToLoadingTextMap\|stepToErrorTextMap" app/src/main
```
Expected: функции определены теперь в CaptureText.kt, используются в ShotActivity/CaptureActivity; т.к. пакет тот же — их импорты (`import ...ui.capture.getCaptureSettingValueName`) остаются валидными. Maps — в CaptureConst.kt. Дубликатов определений нет.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arashivision/sdk/demo/ui/capture/CaptureText.kt app/src/main/java/com/arashivision/sdk/demo/ui/capture/CaptureConst.kt
git commit -m "refactor(#10): вынести resId-резолверы из CaptureConst в CaptureText"
```

---

## Task 5: Верификация и push

- [ ] **Step 1: Статическая проверка целостности**

Run:
```bash
# нет осиротевших ссылок на перенесённые функции
grep -rn "ui.capture.getCaptureSettingValueName\|ui.capture.getCaptureModeTextResId\|ui.capture.getCaptureSettingNameResId" app/src/main
# нет дубликатов определений (каждая fun определена ровно 1 раз)
grep -rn "^fun getCaptureModeTextResId\|^fun getCaptureSettingNameResId\|^fun getCaptureSettingValueName" app/src/main
# ViewBindingUtils API не сломан
grep -rn "ViewBindingUtils\." app/src/main
```
Expected: каждая функция определена 1 раз (в CaptureText.kt); потребители на месте.

- [ ] **Step 2: :lib не затронут**

Run: `./gradlew :lib:test` (CI/пользователь)
Expected: BUILD SUCCESSFUL — мы :lib не трогали, тесты остаются зелёными.

- [ ] **Step 3: Push**

```bash
git push origin refactoring
```
`assembleDebug` и проверка на камере — пользователь (CI :app не собирает из-за Nexus).

---

## Self-Review

- **Spec coverage:** #9 → Task 1. #10a (логи catch) → Tasks 2,3. #10b (CaptureConst) → Task 4. Верификация → Task 5.
- **Границы:** рефлексия setYaw/setPitch (#3) не тронута (явное исключение в Tasks 3); логика gyro/VR не меняется (только логи в catch); CaptureConst — механический перенос без смены сигнатур.
- **Placeholder scan:** ViewBindingUtils приведён полностью; для catch-логов и Captureconsts — точные grep-и + правила (т.к. точные строки зависят от чтения файла в момент правки, но процедура детерминирована).
- **Риск:** всё в :app, не собирается агентом. #9 — поведение штатного пути неизменно. #10a — только добавление логов. #10b — перенос в тот же пакет (импорты не ломаются). Финальная сборка за пользователем.
- **Type consistency:** ViewBindingUtils сигнатуры `createBinding`/`createViewModel`/`getParameterizedTypeClass` сохранены; CaptureText функции — те же имена/сигнатуры, что были в CaptureConst.
