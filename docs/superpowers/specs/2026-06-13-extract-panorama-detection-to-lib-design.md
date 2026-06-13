# Spec: вынос панорамной математики и detection в :lib (направление #1)

Дата: 2026-06-13
Ветка: `refactoring`
Статус: одобрено к реализации
Основано на: `2026-06-13-architecture-audit.md` (направление #1)

## Цель

Расширить safety net: вынести чистую панорамную математику и парсинг детекций из Android-модуля `:app` в pure-JVM модуль `:lib` и покрыть тестами. Это фундамент для последующих рискованных рефакторингов (рефлексия, VR, god-object) — без него правки без UI-тестов опасны.

## Контекст

Уже есть `:lib` (pure-Kotlin JVM, пакет `com.arashivision.orientation`) с `Quaternion`, `OrientationMath` и 15 тестами; CI гоняет `./gradlew :lib:test`.

Кандидаты на вынос (изучены):
- `app/.../ui/player/panorama/EquirectangularProjection.kt` — `object EquirectangularProjection` + data-классы `PanoramaDirection`, `UnitVector3`, `UnitQuaternion`. Только `kotlin.math` — чистый.
- `app/.../ui/player/panorama/PanoramaFovMath.kt` — `object PanoramaFovMath` + `TargetFovState`. Только `kotlin.math` — чистый.
- `app/.../ui/player/detection/VideoDetectionModels.kt` — data-классы, без импортов.
- `app/.../ui/player/detection/VideoDetectionTimeline.kt` — `kotlin.math.abs`, чистый.
- `app/.../ui/player/detection/VideoDetectionSidecarParser.kt` — использует `org.json` (на Android встроен, в pure-JVM отсутствует). Поддерживает 4 формата входа: (1) JSON-массив фреймов; (2) объект с `frame_idx` (одиночный фрейм); (3) объект с ключом `frames`/`detections`/`data`; (4) «голая» comma-separated последовательность объектов без обрамляющих `[ ]`. Плюс сортировка по `(timeSec, frameIdx)` и валидация (bbox=4 числа, point=2 числа).

Существующие тесты в `:app`: `EquirectangularProjectionTest`, `PanoramaFovMathTest`.

## Решения (согласованы)

- **Структура :lib — подпакеты по домену:**
  - `com.arashivision.orientation` (есть: Quaternion, OrientationMath)
  - `com.arashivision.orientation.panorama` (проекции, FOV + их data-классы)
  - `com.arashivision.orientation.detection` (Models, Timeline, Parser)
- **Парсер переписать без `org.json` на kotlinx.serialization** (через ручной обход `JsonElement`, НЕ `@Serializable` — из-за 4 разнородных форматов входа). Добавить `org.jetbrains.kotlinx:kotlinx-serialization-json` в `:lib` и плагин `kotlin-serialization`.

## Порядок (safety-net-first — критично)

Парсер сейчас БЕЗ тестов. Переписывать непокрытый код = риск молча потерять формат. Поэтому:

### Фаза A — характеризационные тесты парсера ДО изменений
Написать тесты на ТЕКУЩИЙ `VideoDetectionSidecarParser` (пока он в `:app`, с `org.json`), фиксирующие поведение всех 4 форматов + edge-кейсы:
- формат 1: массив `[{frame_idx,...}]`
- формат 2: одиночный объект `{frame_idx,...}`
- формат 3: `{"frames":[...]}` (и проверить `detections`/`data`)
- формат 4: «голая» последовательность `{...},{...},` (без скобок, с хвостовой запятой)
- сортировка по `(time_sec, frame_idx)`
- фрейм без `objects` → пустой список
- невалидный bbox (≠4 чисел) → исключение
- пустой вход → исключение

Эти тесты — эталон поведения. Они должны проходить и до, и после переписывания.

### Фаза B — перенос чистых файлов
- Перенести `EquirectangularProjection.kt`, `PanoramaFovMath.kt` в `:lib` пакет `...panorama` (без изменения логики, сменить `package`).
- Перенести `VideoDetectionModels.kt`, `VideoDetectionTimeline.kt` в `:lib` пакет `...detection`.
- Переместить `EquirectangularProjectionTest`, `PanoramaFovMathTest` в `:lib` (сменить package/импорты).

### Фаза C — переписать парсер на kotlinx.serialization
- Добавить в `:lib`: плагин `kotlin-serialization`, зависимость `kotlinx-serialization-json`.
- Переписать `VideoDetectionSidecarParser` на ручной обход `JsonElement`/`JsonArray`/`JsonObject`, сохранив ВСЕ 4 формата и валидацию.
- Перенести характеризационные тесты из Фазы A в `:lib`, прогнать против переписанной версии — должны остаться зелёными (доказательство неизменности поведения).
- Удалить старый парсер из `:app`.

### Фаза D — обновить потребителей
- Поправить импорты в `:app` (`LocalSphericalPlayerActivity` и другие потребители panorama/detection) на новые пакеты `:lib`.
- Проверить, что `:app` компилируется (чекпоинт; локально/CI).

## Границы

Математику (`EquirectangularProjection`, `PanoramaFovMath`, `Timeline`, `Models`) переносим БЕЗ изменения логики. Меняем реализацию ТОЛЬКО парсера и ТОЛЬКО под защитой характеризационных тестов. Не трогаем рефлексию, VR, ViewModel, gyro-контроллер (это направления #3–#7).

## Критерий готовности

- `./gradlew :lib:test` зелёный: старые тесты (Quaternion, projection, fov) + новые (parser characterization).
- Характеризационные тесты парсера проходят на обеих реализациях (org.json → kotlinx).
- `:app` компилируется с новыми импортами.
- CI (`:lib:test`) зелёный на PR.
- Всё в ветке `refactoring`.

## Версии

- `kotlinx-serialization-json`: 1.7.x (совместима с Kotlin 2.0.21). Точную версию выверить при сборке.
- Плагин `org.jetbrains.kotlin.plugin.serialization` версии Kotlin (2.0.21) — добавить в catalog `[plugins]` и в `build.gradle.kts` корня (apply false) и `:lib`.
