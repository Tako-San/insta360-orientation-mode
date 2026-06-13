# Spec: убрать static из GyroOrientationController (#4)

Дата: 2026-06-13
Ветка: `refactoring`
Статус: одобрено к реализации (автономно)
Основано на: `2026-06-13-architecture-audit.md` направление #4

## Цель

Убрать `companion object` static-поля `sensivity`/`invertYaw`/`invertPitch` из `GyroOrientationController` — они создают неявную глобальную связь между capture- и player-экранами (оба VR-диалога настройки дёргают один static) и race. Перевести в instance-поля → у каждого экрана своя чувствительность.

## ⚠️ Поведение МЕНЯЕТСЯ намеренно + слепая правка

Сейчас static `sensivity` РАЗДЕЛЯЕТСЯ между capture-VR и player-VR. После #4 — per-screen (независимо). Это сознательное UX-решение пользователя, НЕ поведение-нейтральный рефакторинг. Критерий «работает как раньше» здесь неверен — правильный «работает по-новому, как задумано».

Правки в `:app` — агент НЕ собирает (нет SDK) и не проверяет на камере. Гарантия — статика. Проверку обоих экранов делает пользователь.

## Текущее состояние (изучено)

`GyroOrientationController` companion object:
```kotlin
var sensivity: Float = 1.2f
private val yawFactor = 0.04f
private val pitchFactor = 0.02f
private val yawSensitivity get() = yawFactor * sensivity
private val pitchSensitivity get() = pitchFactor * sensivity
var invertYaw = false
var invertPitch = true
```
Внешние потребители (только `sensivity`): `VrManager` (497/506/548 — read+write), `LocalVrManager` (153/156/194 — read+write). `invertYaw/invertPitch` снаружи не используются.

VR-менеджеры создаются через конструктор и уже принимают лямбды (`calibrateGyro` в VrManager).

## Решения (согласованы)

- Все три поля (`sensivity`, `invertYaw`, `invertPitch`) + производные → **instance**-поля контроллера (те же дефолты 1.2f/false/true).
- Per-screen: каждый `GyroOrientationController` владеет своими настройками.
- Проброс в VR-менеджеры — **лямбды get/set** (в стиле существующего `calibrateGyro`), НЕ ссылка на контроллер целиком.

## Изменения

### GyroOrientationController
`companion object { ... }` с этими полями → удалить, перенести в тело класса как instance:
```kotlin
var sensivity: Float = 1.2f
var invertYaw = false
var invertPitch = true
private val yawFactor = 0.04f
private val pitchFactor = 0.02f
private val yawSensitivity: Float get() = yawFactor * sensivity
private val pitchSensitivity: Float get() = pitchFactor * sensivity
```
Тело `onSensorChanged` (вызов `computeTargetOrientation(yaw, pitch, sensivity, invertYaw, invertPitch)`) и лог (`yaw * yawSensitivity`) не меняются — те же имена, теперь instance. computeTargetOrientation в :lib не трогаем (он уже принимает sensivity параметром).

### VrManager (capture)
Конструктор: добавить `getSensitivity: () -> Float` и `setSensitivity: (Float) -> Unit`. Заменить `GyroOrientationController.sensivity` (read → `getSensitivity()`, write → `setSensitivity(newSens)`). В `CaptureActivity` при создании передать `{ gyroController.sensivity }` и `{ v -> gyroController.sensivity = v }`.

### LocalVrManager (player)
Аналогично: конструктор + лямбды, замена 3 обращений. В `LocalSphericalPlayerActivity` передать лямбды на свой `gyroController`.

## Границы

Не трогаем: расчёт углов, quaternion, computeTargetOrientation (:lib), рефлексию (#3 сделан), VR-копирование кадров. Только владение настройками sensivity/invert.

## Критерий готовности

- Статически: нет `companion object` с sensivity/invert в контроллере; нет `GyroOrientationController.sensivity` (static-обращений) в app; VR-менеджеры используют лямбды.
- `:lib:test` зелёный (не затронут).
- Проверка per-screen чувствительности и VR на камере — ПОЛЬЗОВАТЕЛЬ.
