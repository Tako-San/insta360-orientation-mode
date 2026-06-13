# Убрать static из GyroOrientationController (#4) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: subagent-driven-development / executing-plans.

**Goal:** Перенести static `sensivity`/`invertYaw`/`invertPitch` в instance-поля GyroOrientationController (per-screen), пробросив в VR-менеджеры get/set-лямбды.

**Architecture:** companion object → instance-поля. VrManager/LocalVrManager получают `getSensitivity:()->Float` и `setSensitivity:(Float)->Unit` через конструктор; Activity передают лямбды на свой gyroController.

**Tech Stack:** Kotlin/Android. Агент НЕ собирает :app. ПОВЕДЕНИЕ МЕНЯЕТСЯ намеренно (per-screen вместо общего). Проверка — пользователь на камере (оба экрана).

---

## Task 1: instance-поля в GyroOrientationController

**Files:** Modify `app/src/main/java/com/arashivision/sdk/demo/ui/capture/GyroOrientationController.kt`

- [ ] **Step 1: Перенести поля из companion в тело класса**

Удалить из `companion object` (стр ~45-54):
```kotlin
    companion object {
        var sensivity: Float = 1.2f
        private val yawFactor = 0.04f
        private val pitchFactor = 0.02f
        private val yawSensitivity: Float
            get() = yawFactor * sensivity
        private val pitchSensitivity: Float
            get() = pitchFactor * sensivity
        var invertYaw = false
        var invertPitch = true
    }
```
Если в companion object НЕ осталось других членов — удалить блок целиком. Добавить в тело класса (рядом с другими instance var, напр. после `var smoothingAlpha`):
```kotlin
    var sensivity: Float = 1.2f
    var invertYaw = false
    var invertPitch = true
    private val yawFactor = 0.04f
    private val pitchFactor = 0.02f
    private val yawSensitivity: Float get() = yawFactor * sensivity
    private val pitchSensitivity: Float get() = pitchFactor * sensivity
```

> ВАЖНО: проверить, не осталось ли в companion object других членов (если были — оставить companion с ними). `onSensorChanged` и лог используют `sensivity`/`invertYaw`/`invertPitch`/`yawSensitivity`/`pitchSensitivity` по простому имени — теперь резолвятся на instance, тело НЕ меняется.

- [ ] **Step 2: Проверка**

```bash
grep -n "companion object\|sensivity\|invertYaw\|invertPitch\|yawSensitivity\|pitchSensitivity" app/src/main/java/com/arashivision/sdk/demo/ui/capture/GyroOrientationController.kt
```
Expected: поля теперь instance (не внутри companion); если companion остался пустым — удалён.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/arashivision/sdk/demo/ui/capture/GyroOrientationController.kt
git commit -m "refactor(#4): sensivity/invert — instance-поля GyroOrientationController (убран static)"
```

## Task 2: VrManager — лямбды get/set sensitivity

**Files:** Modify `app/src/main/java/com/arashivision/sdk/demo/ui/capture/VrManager.kt`

- [ ] **Step 1: Добавить параметры конструктора**

В конструктор VrManager (после `calibrateGyro: () -> Unit = {}`) добавить:
```kotlin
    private val getSensitivity: () -> Float = { 1.2f },
    private val setSensitivity: (Float) -> Unit = {}
```

- [ ] **Step 2: Заменить 3 обращения к static**

- стр ~497: `val currentSens = GyroOrientationController.sensivity` → `val currentSens = getSensitivity()`
- стр ~506: `progress = ( (GyroOrientationController.sensivity * 100f).toInt() ).coerceIn(0, max)` → `progress = ( (getSensitivity() * 100f).toInt() ).coerceIn(0, max)`
- стр ~548: `GyroOrientationController.sensivity = newSens` → `setSensitivity(newSens)`

- [ ] **Step 3: Убрать импорт GyroOrientationController, если он стал не нужен**

```bash
grep -n "GyroOrientationController" app/src/main/java/com/arashivision/sdk/demo/ui/capture/VrManager.kt
```
Если не осталось ссылок — удалить import (если был).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arashivision/sdk/demo/ui/capture/VrManager.kt
git commit -m "refactor(#4): VrManager читает/пишет sensitivity через лямбды"
```

## Task 3: CaptureActivity — передать лямбды

**Files:** Modify `app/src/main/java/com/arashivision/sdk/demo/ui/capture/CaptureActivity.kt`

- [ ] **Step 1: Дополнить создание VrManager**

В `vrManager = VrManager(...)` (стр ~79) после `calibrateGyro = ...` добавить:
```kotlin
            getSensitivity = { gyroController.sensivity },
            setSensitivity = { v -> gyroController.sensivity = v }
```

> gyroController — lateinit; лямбды вызываются при открытии VR-диалога (позже init), ссылка валидна — как и существующая calibrateGyro.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/arashivision/sdk/demo/ui/capture/CaptureActivity.kt
git commit -m "refactor(#4): CaptureActivity передаёт sensitivity-лямбды в VrManager"
```

## Task 4: LocalVrManager + LocalSphericalPlayerActivity

**Files:**
- Modify `app/src/main/java/com/arashivision/sdk/demo/ui/player/LocalVrManager.kt`
- Modify `app/src/main/java/com/arashivision/sdk/demo/ui/player/LocalSphericalPlayerActivity.kt`

- [ ] **Step 1: Конструктор LocalVrManager**

После `private val overlaysToHide: List<View>` добавить:
```kotlin
    private val getSensitivity: () -> Float = { 1.2f },
    private val setSensitivity: (Float) -> Unit = {}
```

- [ ] **Step 2: Заменить 3 обращения**

- стр ~153: `"%.2f".format(GyroOrientationController.sensivity)` → `"%.2f".format(getSensitivity())`
- стр ~156: `progress = (GyroOrientationController.sensivity * 100f).toInt().coerceIn(0, max)` → `progress = (getSensitivity() * 100f).toInt().coerceIn(0, max)`
- стр ~194: `GyroOrientationController.sensivity = newSens` → `setSensitivity(newSens)`

- [ ] **Step 3: Убрать import GyroOrientationController в LocalVrManager, если осиротел**

```bash
grep -n "GyroOrientationController" app/src/main/java/com/arashivision/sdk/demo/ui/player/LocalVrManager.kt
```
Нет ссылок → удалить import.

- [ ] **Step 4: Передать лямбды в LocalSphericalPlayerActivity**

В `vrManager = LocalVrManager(...)` (стр ~91) добавить:
```kotlin
            getSensitivity = { gyroController.sensivity },
            setSensitivity = { v -> gyroController.sensivity = v }
```
Проверить, что gyroController создаётся ДО или его ссылка валидна к моменту вызова (лямбда вызывается при открытии диалога — ок).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arashivision/sdk/demo/ui/player/LocalVrManager.kt app/src/main/java/com/arashivision/sdk/demo/ui/player/LocalSphericalPlayerActivity.kt
git commit -m "refactor(#4): LocalVrManager/Player — sensitivity через лямбды"
```

## Task 5: Верификация и push

- [ ] **Step 1: Нет static-обращений**

```bash
grep -rn "GyroOrientationController.sensivity\|GyroOrientationController.invertYaw\|GyroOrientationController.invertPitch" app/src/main
```
Expected: ПУСТО.

```bash
grep -rn "companion object" app/src/main/java/com/arashivision/sdk/demo/ui/capture/GyroOrientationController.kt
```
Expected: пусто (или только если остались иные члены — но их не было).

- [ ] **Step 2: лямбды на местах**

```bash
grep -rn "getSensitivity\|setSensitivity" app/src/main
```
Expected: объявлены в обоих VR-менеджерах, переданы из обеих Activity.

- [ ] **Step 3: :lib не затронут**

Run: `./gradlew :lib:test` (CI) — зелёный.

- [ ] **Step 4: Push**

```bash
git push origin refactoring
```
Проверка per-screen чувствительности (настроить в capture-VR, открыть player-VR — должна быть НЕЗАВИСИМА) и работы гироскопа — ПОЛЬЗОВАТЕЛЬ на камере.

## Self-Review

- **Spec coverage:** instance-поля → Task 1; VrManager лямбды → Tasks 2,3; LocalVrManager лямбды → Task 4; верификация → Task 5.
- **Границы:** computeTargetOrientation (:lib) не тронут; логика углов/quaternion не тронута; рефлексия (#3) не тронута.
- **Поведение:** МЕНЯЕТСЯ намеренно — per-screen sensitivity. Дефолты сохранены (1.2/false/true), внутри экрана поведение прежнее.
- **Type consistency:** `getSensitivity:()->Float`, `setSensitivity:(Float)->Unit` — едины в обоих VR-менеджерах и обеих Activity. `gyroController.sensivity` — instance var.
- **Риск:** lateinit gyroController в лямбдах — безопасно (вызов отложен до диалога). Слепая правка, сборка/камера — пользователь.
