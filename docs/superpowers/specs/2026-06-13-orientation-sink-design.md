# Spec: OrientationSink — инкапсуляция рефлексии setYaw/setPitch (#3)

Дата: 2026-06-13
Ветка: `refactoring`
Статус: одобрено к реализации (автономно)
Основано на: `2026-06-13-architecture-audit.md` направление #3 (critical), [[insta360-reflection-sites]]

## Цель

Убрать дублированную, молча падающую рефлексию `setYaw`/`setPitch` за типобезопасный интерфейс. Сейчас 3 почти идентичных `applyTo`-блока (`cls.getMethod("setYaw"/"setPitch", Float).invoke(obj, ...)`) глотают `NoSuchMethodException` молча — при обновлении SDK ориентация сломается без единой ошибки в логах.

## ⚠️ Ограничение верификации

Рефлексия завязана на Android-view SDK (`InstaCapturePlayerView`, `SphericalGLSurfaceView`), методы `setYaw/setPitch` недоступны в публичном API. Агент НЕ может собрать `:app` (нет SDK + Nexus за файрволом) и проверить на камере. Гарантия — статическая корректность + тест интерфейса в `:lib` с fake. Реальную работу ориентации/VR на камере проверяет ПОЛЬЗОВАТЕЛЬ. Это слепая правка боевого UI — риск тонкой регрессии реален.

## Текущие места рефлексии (изучены)

- `CaptureActivity.tryApplyOrientationToPlayer` (~стр 420): `applyTo(binding.capturePlayerView, yaw, pitch)`.
- `VrManager.applyOrientation` (~стр 282): рефлексия на `rightVrPlayer` с `yaw + vrIpdYawDeg` (IPD-сдвиг 3°). `rightVrPlayer` ПЕРЕСОЗДАётся при каждом enableVrMode.
- `LocalSphericalPlayerActivity.tryApplyOrientation` (~стр 376): `applyTo(binding.sphericalView, rawYawDeg, rawPitchDeg)`.

Все методы вызываются как `setYaw(Float)`/`setPitch(Float)`. Отсутствие метода НЕ должно ронять приложение.

## Компоненты

### OrientationSink (интерфейс, :lib, пакет com.arashivision.orientation)
```kotlin
interface OrientationSink {
    fun apply(yawDeg: Float, pitchDeg: Float)
}
```
Чистый, без Android. Позволяет тестировать потребителей с fake и даёт стабильную границу.

### ReflectiveOrientationSink (:app)
Реализация поверх рефлексии. Конструктор:
- `target: Any` — view (`InstaCapturePlayerView`/`SphericalGLSurfaceView`),
- `yawOffsetDeg: Float = 0f` — для VR-IPD,
- `logger` (XLog).

Поведение:
- lazy-резолв и кэш `Method` `setYaw`/`setPitch` (по `target.javaClass`), один раз.
- при первом резолве, если метод не найден — `logger.w(...)` (раньше молчало); далее не спамит.
- `apply(yaw, pitch)`: вызывает закэшированные методы с `yaw + yawOffsetDeg` и `pitch`; исключения вызова ловит и логирует, не роняя.

## Интеграция в 3 местах (поведение идентично)

- **CaptureActivity:** поле `private val captureSink by lazy { ReflectiveOrientationSink(binding.capturePlayerView, logger = ...) }` (view стабильна). В `tryApplyOrientationToPlayer` ветка non-VR: `captureSink.apply(yawDeg, pitchDeg)` вместо `applyTo(...)`. VR-ветка остаётся вызовом `vrManager.applyOrientation`.
- **VrManager.applyOrientation:** т.к. `rightVrPlayer` пересоздаётся — НЕ кэшировать sink на весь объект; создавать `ReflectiveOrientationSink(player, yawOffsetDeg = vrIpdYawDeg, ...)` лениво при наличии `rightVrPlayer` и держать в поле, сбрасывая в null при пересоздании/destroy. Заменяет инлайн-рефлексию; IPD-сдвиг теперь через `yawOffsetDeg`.
- **LocalSphericalPlayerActivity:** поле `private val playerSink by lazy { ReflectiveOrientationSink(binding.sphericalView, logger = ...) }`. В `tryApplyOrientation`: `playerSink.apply(rawYawDeg, rawPitchDeg)` вместо `applyTo(...)`.

Удалить локальные `fun applyTo(...)` из всех трёх. `NoSuchMethodException` больше не глотается молча — логируется в sink.

## Границы

Не трогаем: расчёт углов (gyro/quaternion/projection), clamp/коэрс перед apply (остаётся в вызывающем коде), VR-копирование кадров, ViewModel/SDK. Только инкапсуляция самого вызова setYaw/setPitch.

## Тест

Интерфейс `OrientationSink` тривиален, а вся нетривиальная логика (рефлексия, кэш, offset) живёт в `ReflectiveOrientationSink` в `:app` и НЕ тестируема без SDK. Отдельный fake-тест интерфейса в :lib дал бы ложное ощущение покрытия, поэтому НЕ добавляем — это честнее. Проверка #3 — статическая (grep) + сборка/камера пользователя. (Если позже yaw-offset-логика будет вынесена в чистую функцию в :lib — тогда покроем тестом.)

## Критерий готовности

- Статически: 3 места используют sink; инлайн `getMethod("setYaw"/"setPitch")` не осталось (grep → 0 в Activity/VrManager); `applyTo` удалены.
- `:lib:test` зелёный (интерфейс `OrientationSink` компилируется в :lib, существующие тесты не сломаны).
- `assembleDebug` + проверка ориентации и VR на камере — ПОЛЬЗОВАТЕЛЬ.
