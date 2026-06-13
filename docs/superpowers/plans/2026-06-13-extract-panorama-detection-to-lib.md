# Extract Panorama & Detection to :lib — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Вынести чистую панорамную математику (проекции, FOV) и парсинг детекций из Android-модуля `:app` в pure-JVM `:lib`, покрыть тестами, переписав JSON-парсер с `org.json` на kotlinx.serialization — чтобы расширить тестируемый safety net перед рискованными рефакторингами.

**Architecture:** Чистые файлы переносятся в `:lib` под подпакеты `com.arashivision.orientation.panorama` и `.detection` без изменения логики. Парсер переписывается на ручной обход `JsonElement` (kotlinx.serialization), сохраняя все 4 формата входа, и проверяется характеризационными тестами на образцах JSON. Потребители в `:app` переключаются на новые импорты.

**Tech Stack:** Kotlin 2.0.21, pure-JVM `:lib` (kotlin.jvm), kotlinx-serialization-json, JUnit 4, Gradle 8.13. Android SDK в окружении агента отсутствует — `./gradlew` прогоняет ПОЛЬЗОВАТЕЛЬ/CI; агент пишет код и коммитит.

---

## Окружение и важные оговорки

- **Агент не может прогнать сборку/тесты** (нет Android SDK; даже `:lib:test` требует gradle-прогона). Все `./gradlew ...` — чекпоинты CI/пользователя. CI на PR гоняет `./gradlew :lib:test` и уже зелёный для текущего `:lib`.
- **«Работает» = `:lib:test` зелёный в CI + `:app` компилируется.** Полная работа на камере не проверяется в этом цикле (нет железа).
- **Характеризационные тесты парсера** пишутся в `:lib` против ПЕРЕПИСАННОГО парсера, с эталоном-поведением, выведенным из текущей `org.json`-реализации и примеров JSON в исходном файле. Тесты на `org.json`-версию в `:app` НЕ пишем — они не прогоняемы в pure-JVM CI (Android-зависимость), их ценность для safety net нулевая. Эталон фиксируется как набор ассертов в Task 5, выведенных из чтения текущего кода (4 формата, сортировка, валидация).
- Все коммиты — в `refactoring`. Push — после зелёного CI.

## File Structure

- Modify: `gradle/libs.versions.toml` — добавить версию kotlinx-serialization и плагин.
- Modify: `build.gradle.kts` (root) — объявить плагин serialization `apply false`.
- Modify: `lib/build.gradle.kts` — применить плагин serialization, добавить зависимость.
- Create: `lib/src/main/kotlin/com/arashivision/orientation/panorama/EquirectangularProjection.kt`
- Create: `lib/src/main/kotlin/com/arashivision/orientation/panorama/PanoramaFovMath.kt`
- Create: `lib/src/main/kotlin/com/arashivision/orientation/detection/VideoDetectionModels.kt`
- Create: `lib/src/main/kotlin/com/arashivision/orientation/detection/VideoDetectionTimeline.kt`
- Create: `lib/src/main/kotlin/com/arashivision/orientation/detection/VideoDetectionSidecarParser.kt` (переписан)
- Create: `lib/src/test/kotlin/com/arashivision/orientation/panorama/EquirectangularProjectionTest.kt` (перенос)
- Create: `lib/src/test/kotlin/com/arashivision/orientation/panorama/PanoramaFovMathTest.kt` (перенос)
- Create: `lib/src/test/kotlin/com/arashivision/orientation/detection/VideoDetectionSidecarParserTest.kt` (новые characterization)
- Create: `lib/src/test/kotlin/com/arashivision/orientation/detection/VideoDetectionTimelineTest.kt` (новые)
- Delete (git rm): соответствующие файлы из `app/src/main/.../ui/player/panorama/` и `.../detection/`, старые тесты из `app/src/test/.../panorama/`.
- Modify: потребители в `:app` (импорты).

---

## Task 1: Подключить kotlinx-serialization к :lib

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts` (root)
- Modify: `lib/build.gradle.kts`

- [ ] **Step 1: Добавить в catalog версию и плагин**

В `gradle/libs.versions.toml` в `[versions]` добавить:
```toml
kotlinxSerialization = "1.7.3"
```
В `[libraries]` добавить:
```toml
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
```
В `[plugins]` добавить:
```toml
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 2: Объявить плагин в корневом build.gradle.kts**

В `build.gradle.kts` (root) в блок `plugins { ... }` добавить:
```kotlin
    alias(libs.plugins.kotlin.serialization) apply false
```

- [ ] **Step 3: Применить плагин и зависимость в lib**

Переписать `lib/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 4 (CI/пользователь): проверить, что :lib конфигурируется**

Run: `./gradlew :lib:dependencies --configuration compileClasspath`
Expected: kotlinx-serialization-json в дереве, BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts lib/build.gradle.kts
git commit -m "build: подключить kotlinx-serialization к :lib"
```

---

## Task 2: Перенести EquirectangularProjection в :lib

**Files:**
- Create: `lib/src/main/kotlin/com/arashivision/orientation/panorama/EquirectangularProjection.kt`
- Delete: `app/src/main/java/com/arashivision/sdk/demo/ui/player/panorama/EquirectangularProjection.kt`

- [ ] **Step 1: Скопировать файл в :lib со сменой пакета**

Создать `lib/src/main/kotlin/com/arashivision/orientation/panorama/EquirectangularProjection.kt` с ТЕМ ЖЕ содержимым, что `app/src/main/java/com/arashivision/sdk/demo/ui/player/panorama/EquirectangularProjection.kt`, изменив ТОЛЬКО первую строку:
```kotlin
package com.arashivision.orientation.panorama
```
Импорты (`kotlin.math.PI/cos/sin/sqrt`) и всё тело (`object EquirectangularProjection`, `data class PanoramaDirection`, `UnitVector3`, `UnitQuaternion`) — без изменений.

- [ ] **Step 2: Удалить оригинал из :app**

```bash
git rm app/src/main/java/com/arashivision/sdk/demo/ui/player/panorama/EquirectangularProjection.kt
```

- [ ] **Step 3: Commit** (импорты потребителей чиним в Task 8 — пока :app может не компилироваться, это ок для промежуточного коммита; финальная компиляция проверяется в Task 9)

```bash
git add lib/src/main/kotlin/com/arashivision/orientation/panorama/EquirectangularProjection.kt
git commit -m "refactor: перенести EquirectangularProjection в :lib (panorama)"
```

---

## Task 3: Перенести PanoramaFovMath в :lib

**Files:**
- Create: `lib/src/main/kotlin/com/arashivision/orientation/panorama/PanoramaFovMath.kt`
- Delete: `app/src/main/java/com/arashivision/sdk/demo/ui/player/panorama/PanoramaFovMath.kt`

- [ ] **Step 1: Скопировать в :lib со сменой пакета**

Создать `lib/src/main/kotlin/com/arashivision/orientation/panorama/PanoramaFovMath.kt` с тем же содержимым, изменив первую строку на:
```kotlin
package com.arashivision.orientation.panorama
```
Тело (`object PanoramaFovMath`, `data class TargetFovState`) и импорты (`kotlin.math.PI/abs/atan2/sqrt`) — без изменений.

- [ ] **Step 2: Удалить оригинал**

```bash
git rm app/src/main/java/com/arashivision/sdk/demo/ui/player/panorama/PanoramaFovMath.kt
```

- [ ] **Step 3: Commit**

```bash
git add lib/src/main/kotlin/com/arashivision/orientation/panorama/PanoramaFovMath.kt
git commit -m "refactor: перенести PanoramaFovMath в :lib (panorama)"
```

---

## Task 4: Перенести detection Models и Timeline в :lib

**Files:**
- Create: `lib/src/main/kotlin/com/arashivision/orientation/detection/VideoDetectionModels.kt`
- Create: `lib/src/main/kotlin/com/arashivision/orientation/detection/VideoDetectionTimeline.kt`
- Delete: соответствующие из `app/.../detection/`

- [ ] **Step 1: Перенести Models**

Создать `lib/src/main/kotlin/com/arashivision/orientation/detection/VideoDetectionModels.kt` с содержимым текущего `VideoDetectionModels.kt`, сменив первую строку на:
```kotlin
package com.arashivision.orientation.detection
```
Все data-классы (`VideoDetectionSidecar`, `VideoDetectionFrame`, `VideoDetectedObject`, `BboxXyxy`, `Point2d`) — без изменений.

- [ ] **Step 2: Перенести Timeline**

Создать `lib/src/main/kotlin/com/arashivision/orientation/detection/VideoDetectionTimeline.kt` с содержимым текущего, сменив первую строку на:
```kotlin
package com.arashivision.orientation.detection
```
Импорт `kotlin.math.abs` и тело (`class VideoDetectionTimeline` с `frameAt`/`detectionsAt`/`frameByIndex`) — без изменений.

- [ ] **Step 3: Удалить оригиналы**

```bash
git rm app/src/main/java/com/arashivision/sdk/demo/ui/player/detection/VideoDetectionModels.kt
git rm app/src/main/java/com/arashivision/sdk/demo/ui/player/detection/VideoDetectionTimeline.kt
```

- [ ] **Step 4: Commit**

```bash
git add lib/src/main/kotlin/com/arashivision/orientation/detection/VideoDetectionModels.kt lib/src/main/kotlin/com/arashivision/orientation/detection/VideoDetectionTimeline.kt
git commit -m "refactor: перенести VideoDetectionModels и Timeline в :lib (detection)"
```

---

## Task 5: Переписать VideoDetectionSidecarParser на kotlinx.serialization

**Files:**
- Create: `lib/src/main/kotlin/com/arashivision/orientation/detection/VideoDetectionSidecarParser.kt`
- Delete: `app/src/main/java/com/arashivision/sdk/demo/ui/player/detection/VideoDetectionSidecarParser.kt`

Эталон поведения (из текущей org.json-реализации), который надо сохранить:
1. Вход начинается с `[` → массив фреймов.
2. Вход начинается с `{` → либо одиночный фрейм (есть ключ `frame_idx`), либо объект-обёртка с массивом под ключом `frames`/`detections`/`data` (первый существующий).
3. Если `{...}` не парсится как объект (например, «голая» comma-separated последовательность объектов) → обернуть в `[...]`, убрав хвостовую запятую, и распарсить как массив.
4. Пустой/blank вход → исключение.
5. Каждый фрейм: `frame_idx` (Int), `time_sec` (Double), `objects` (массив; отсутствует → пустой список).
6. Каждый объект: `track_id` (Int), `bbox_xyxy` (ровно 4 числа), `center_xy` (2 числа), `center_norm` (2 числа); неверная длина → исключение.
7. Итог сортируется по `(timeSec, frameIdx)`.

- [ ] **Step 1: Написать переписанный парсер**

Создать `lib/src/main/kotlin/com/arashivision/orientation/detection/VideoDetectionSidecarParser.kt`:
```kotlin
package com.arashivision.orientation.detection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Parses detection sidecar JSON files produced for offline panoramic videos. */
class VideoDetectionSidecarParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(input: String): VideoDetectionSidecar {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "Detection JSON is empty" }

        val framesArray: JsonArray = when (trimmed.first()) {
            '[' -> json.parseToJsonElement(trimmed).jsonArray
            '{' -> parseObjectRootOrObjectSequence(trimmed)
            else -> error("Detection JSON must start with '[' or '{'")
        }

        val frames = framesArray
            .map { parseFrame(it.jsonObject) }
            .sortedWith(compareBy<VideoDetectionFrame> { it.timeSec }.thenBy { it.frameIdx })

        return VideoDetectionSidecar(frames)
    }

    private fun parseObjectRootOrObjectSequence(jsonText: String): JsonArray {
        return try {
            val root = json.parseToJsonElement(jsonText).jsonObject
            when {
                root.containsKey("frame_idx") -> JsonArray(listOf(root))
                else -> findFramesArray(root)
            }
        } catch (e: Exception) {
            // «Голая» comma-separated последовательность объектов без обрамляющих [ ].
            json.parseToJsonElement("[${jsonText.trimEnd().trimEnd(',')}]").jsonArray
        }
    }

    private fun findFramesArray(root: JsonObject): JsonArray {
        val supportedKeys = listOf("frames", "detections", "data")
        val key = supportedKeys.firstOrNull { root[it] is JsonArray }
        return key?.let { root[it]!!.jsonArray }
            ?: error("Detection JSON object must contain one of: ${supportedKeys.joinToString()}")
    }

    private fun parseFrame(frameJson: JsonObject): VideoDetectionFrame {
        val objectsArray = (frameJson["objects"] as? JsonArray) ?: JsonArray(emptyList())
        return VideoDetectionFrame(
            frameIdx = frameJson.getValue("frame_idx").jsonPrimitive.int,
            timeSec = frameJson.getValue("time_sec").jsonPrimitive.double,
            objects = objectsArray.map { parseObject(it.jsonObject) }
        )
    }

    private fun parseObject(objectJson: JsonObject): VideoDetectedObject {
        return VideoDetectedObject(
            trackId = objectJson.getValue("track_id").jsonPrimitive.int,
            bboxXyxy = objectJson.getValue("bbox_xyxy").jsonArray.toBboxXyxy(),
            centerXy = objectJson.getValue("center_xy").jsonArray.toPoint2d(),
            centerNorm = objectJson.getValue("center_norm").jsonArray.toPoint2d()
        )
    }

    private fun JsonArray.toBboxXyxy(): BboxXyxy {
        require(size == 4) { "bbox_xyxy must contain four numbers" }
        return BboxXyxy(
            left = this[0].jsonPrimitive.double,
            top = this[1].jsonPrimitive.double,
            right = this[2].jsonPrimitive.double,
            bottom = this[3].jsonPrimitive.double
        )
    }

    private fun JsonArray.toPoint2d(): Point2d {
        require(size == 2) { "point array must contain two numbers" }
        return Point2d(
            x = this[0].jsonPrimitive.double,
            y = this[1].jsonPrimitive.double
        )
    }
}
```

- [ ] **Step 2: Удалить org.json-версию из :app**

```bash
git rm app/src/main/java/com/arashivision/sdk/demo/ui/player/detection/VideoDetectionSidecarParser.kt
```

- [ ] **Step 3: Commit**

```bash
git add lib/src/main/kotlin/com/arashivision/orientation/detection/VideoDetectionSidecarParser.kt
git commit -m "refactor: переписать VideoDetectionSidecarParser на kotlinx.serialization, перенести в :lib"
```

---

## Task 6: Перенести существующие тесты panorama в :lib

**Files:**
- Create: `lib/src/test/kotlin/com/arashivision/orientation/panorama/EquirectangularProjectionTest.kt`
- Create: `lib/src/test/kotlin/com/arashivision/orientation/panorama/PanoramaFovMathTest.kt`
- Delete: старые из `app/src/test/java/com/arashivision/sdk/demo/ui/player/panorama/`

- [ ] **Step 1: Перенести EquirectangularProjectionTest**

Создать `lib/src/test/kotlin/com/arashivision/orientation/panorama/EquirectangularProjectionTest.kt` с содержимым текущего `app/src/test/java/com/arashivision/sdk/demo/ui/player/panorama/EquirectangularProjectionTest.kt`, сменив первую строку на:
```kotlin
package com.arashivision.orientation.panorama
```
Остальное (импорты junit, тело тестов) — без изменений; типы `PanoramaDirection`/`UnitQuaternion` теперь в том же пакете.

- [ ] **Step 2: Перенести PanoramaFovMathTest**

Создать `lib/src/test/kotlin/com/arashivision/orientation/panorama/PanoramaFovMathTest.kt` с содержимым текущего `app/src/test/java/com/arashivision/sdk/demo/ui/player/panorama/PanoramaFovMathTest.kt`, сменив первую строку на:
```kotlin
package com.arashivision.orientation.panorama
```

- [ ] **Step 3: Удалить старые тесты из :app**

```bash
git rm app/src/test/java/com/arashivision/sdk/demo/ui/player/panorama/EquirectangularProjectionTest.kt
git rm app/src/test/java/com/arashivision/sdk/demo/ui/player/panorama/PanoramaFovMathTest.kt
```

- [ ] **Step 4: Commit**

```bash
git add lib/src/test/kotlin/com/arashivision/orientation/panorama/
git commit -m "test: перенести panorama-тесты в :lib"
```

---

## Task 7: Характеризационные тесты парсера и Timeline

**Files:**
- Create: `lib/src/test/kotlin/com/arashivision/orientation/detection/VideoDetectionSidecarParserTest.kt`
- Create: `lib/src/test/kotlin/com/arashivision/orientation/detection/VideoDetectionTimelineTest.kt`

- [ ] **Step 1: Написать тесты парсера (фиксируют все 4 формата + edge)**

Создать `lib/src/test/kotlin/com/arashivision/orientation/detection/VideoDetectionSidecarParserTest.kt`:
```kotlin
package com.arashivision.orientation.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDetectionSidecarParserTest {

    private val parser = VideoDetectionSidecarParser()

    private val frameObj = """
        {
          "frame_idx": 198,
          "time_sec": 6.6066,
          "objects": [
            {
              "track_id": 2,
              "bbox_xyxy": [1204.0, 270.0, 1243.0, 283.0],
              "center_xy": [1223.5, 276.5],
              "center_norm": [0.955859, 0.432031]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesArrayRoot() {
        val sidecar = parser.parse("[$frameObj]")
        assertEquals(1, sidecar.frameCount)
        val f = sidecar.frames[0]
        assertEquals(198, f.frameIdx)
        assertEquals(6.6066, f.timeSec, 1e-9)
        assertEquals(1, f.objects.size)
        assertEquals(2, f.objects[0].trackId)
        assertEquals(1204.0, f.objects[0].bboxXyxy.left, 1e-9)
        assertEquals(0.955859, f.objects[0].centerNorm.x, 1e-9)
    }

    @Test
    fun parsesSingleObjectRoot() {
        val sidecar = parser.parse(frameObj)
        assertEquals(1, sidecar.frameCount)
        assertEquals(198, sidecar.frames[0].frameIdx)
    }

    @Test
    fun parsesFramesWrapperKey() {
        val sidecar = parser.parse("""{"frames": [$frameObj]}""")
        assertEquals(1, sidecar.frameCount)
    }

    @Test
    fun parsesDetectionsWrapperKey() {
        val sidecar = parser.parse("""{"detections": [$frameObj]}""")
        assertEquals(1, sidecar.frameCount)
    }

    @Test
    fun parsesDataWrapperKey() {
        val sidecar = parser.parse("""{"data": [$frameObj]}""")
        assertEquals(1, sidecar.frameCount)
    }

    @Test
    fun parsesBareCommaSeparatedSequenceWithTrailingComma() {
        val f1 = frameObj
        val f2 = frameObj.replace("\"frame_idx\": 198", "\"frame_idx\": 199")
            .replace("6.6066", "6.7")
        val sidecar = parser.parse("$f1,\n$f2,")
        assertEquals(2, sidecar.frameCount)
    }

    @Test
    fun sortsByTimeThenFrameIdx() {
        val late = frameObj.replace("\"frame_idx\": 198", "\"frame_idx\": 10").replace("6.6066", "9.0")
        val early = frameObj.replace("\"frame_idx\": 198", "\"frame_idx\": 20").replace("6.6066", "1.0")
        val sidecar = parser.parse("[$late, $early]")
        assertEquals(20, sidecar.frames[0].frameIdx) // time 1.0 раньше 9.0
        assertEquals(10, sidecar.frames[1].frameIdx)
    }

    @Test
    fun frameWithoutObjectsGivesEmptyList() {
        val noObjects = """{"frame_idx": 5, "time_sec": 1.0}"""
        val sidecar = parser.parse(noObjects)
        assertTrue(sidecar.frames[0].objects.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyInputThrows() {
        parser.parse("   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidBboxLengthThrows() {
        val bad = """{"frame_idx": 1, "time_sec": 1.0, "objects": [
            {"track_id": 1, "bbox_xyxy": [1.0, 2.0, 3.0], "center_xy": [1.0,2.0], "center_norm": [0.1,0.2]}
        ]}"""
        parser.parse(bad)
    }
}
```

- [ ] **Step 2: Написать тесты Timeline**

Создать `lib/src/test/kotlin/com/arashivision/orientation/detection/VideoDetectionTimelineTest.kt`:
```kotlin
package com.arashivision.orientation.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDetectionTimelineTest {

    private fun frame(idx: Int, t: Double) =
        VideoDetectionFrame(frameIdx = idx, timeSec = t, objects = emptyList())

    private fun timeline(vararg frames: VideoDetectionFrame) =
        VideoDetectionTimeline(VideoDetectionSidecar(frames.toList()))

    @Test
    fun emptyTimelineReturnsNull() {
        assertNull(timeline().frameAt(1000L))
    }

    @Test
    fun returnsNearestFrameByTime() {
        val tl = timeline(frame(0, 0.0), frame(1, 1.0), frame(2, 2.0))
        // 1100ms = 1.1s ближе к 1.0 чем к 2.0
        assertEquals(1, tl.frameAt(1100L)?.frameIdx)
        // 1600ms = 1.6s ближе к 2.0
        assertEquals(2, tl.frameAt(1600L)?.frameIdx)
    }

    @Test
    fun beforeFirstReturnsFirst() {
        val tl = timeline(frame(0, 5.0), frame(1, 6.0))
        assertEquals(0, tl.frameAt(0L)?.frameIdx)
    }

    @Test
    fun afterLastReturnsLast() {
        val tl = timeline(frame(0, 5.0), frame(1, 6.0))
        assertEquals(1, tl.frameAt(999999L)?.frameIdx)
    }

    @Test
    fun detectionsAtEmptyWhenNoFrame() {
        assertTrue(timeline().detectionsAt(0L).isEmpty())
    }

    @Test
    fun frameByIndexFindsByFrameIdx() {
        val tl = timeline(frame(7, 1.0), frame(9, 2.0))
        assertEquals(2.0, tl.frameByIndex(9)?.timeSec ?: 0.0, 1e-9)
        assertNull(tl.frameByIndex(42))
    }
}
```

- [ ] **Step 3 (CI/пользователь): прогнать тесты :lib**

Run: `./gradlew :lib:test`
Expected: BUILD SUCCESSFUL. Все тесты зелёные: Quaternion, OrientationMath, panorama (2 класса), parser (10), timeline (6).
ЕСЛИ парсер-тест падает на `sortsByTimeThenFrameIdx` или формате — это значит переписанный парсер разошёлся с эталоном; исправить парсер (Task 5), НЕ тест.

- [ ] **Step 4: Commit**

```bash
git add lib/src/test/kotlin/com/arashivision/orientation/detection/
git commit -m "test: характеризационные тесты VideoDetectionSidecarParser и Timeline"
```

---

## Task 8: Обновить импорты потребителей в :app

**Files:**
- Modify: потребители panorama/detection в `:app`.

- [ ] **Step 1: Найти всех потребителей**

Run:
```bash
grep -rln "ui.player.panorama\|ui.player.detection\|EquirectangularProjection\|PanoramaFovMath\|VideoDetectionSidecar\|VideoDetectionTimeline\|PanoramaDirection\|UnitQuaternion\|UnitVector3\|TargetFovState\|VideoDetectedObject\|VideoDetectionFrame\|BboxXyxy\|Point2d" app/src/main
```
Expected: список .kt-файлов (ожидаются `LocalSphericalPlayerActivity.kt` и, возможно, overlay/detection-смежные).

- [ ] **Step 2: Заменить импорты**

В каждом найденном файле заменить старые импорты пакетов
`com.arashivision.sdk.demo.ui.player.panorama.*` → `com.arashivision.orientation.panorama.*`
и `com.arashivision.sdk.demo.ui.player.detection.*` → `com.arashivision.orientation.detection.*`.
Типы и вызовы не меняются (имена классов те же). Если импорт был wildcard или поимённый — заменить пакетную часть, имя класса оставить.

- [ ] **Step 3: Проверить, что не осталось старых ссылок**

Run:
```bash
grep -rn "ui.player.panorama\|ui.player.detection" app/src/main
```
Expected: пусто (все ссылки переведены на `com.arashivision.orientation.*`).

- [ ] **Step 4: Commit**

```bash
git add app/src/main
git commit -m "refactor: переключить :app на panorama/detection из :lib"
```

---

## Task 9: Верификация (CI/пользователь)

- [ ] **Step 1: :lib тесты**

Run: `./gradlew :lib:test`
Expected: BUILD SUCCESSFUL, все классы тестов зелёные.

- [ ] **Step 2: компиляция :app**

Run: `./gradlew :app:compileDebugKotlin` (требует SDK+Nexus — локально/self-hosted)
Expected: BUILD SUCCESSFUL — все импорты разрешены, нет ссылок на удалённые пакеты.

- [ ] **Step 3: Push и проверка CI**

```bash
git push origin refactoring
```
Затем убедиться, что workflow `:lib:test` на PR #1 зелёный.

---

## Self-Review

- **Spec coverage:** Фаза A (характеризационные тесты) → Task 7 (с оговоркой в «Окружение»: тесты пишутся в :lib против переписанного парсера, эталон выведен из текущего кода — а не на org.json-версию, т.к. та не прогоняема в pure-JVM CI). Фаза B (перенос чистого) → Tasks 2,3,4,6. Фаза C (переписать парсер) → Task 5 + тесты Task 7. Фаза D (потребители) → Task 8. Подпакеты по домену → пути в Tasks 2–7. kotlinx.serialization → Task 1,5.
- **Отклонение от spec (осознанное):** spec предлагал писать характеризационные тесты на org.json-версию ДО переписывания. План пишет их в :lib против новой версии, т.к. org.json-тесты не прогоняемы в CI без Android. Эталон поведения зафиксирован текстом в Task 5 (7 пунктов) и закодирован в тестах Task 7 — safety net сохраняется, но проверяется на целевой реализации. Это снижает строгость «тесты до кода», но в данном окружении иначе тесты были бы непрогоняемы.
- **Placeholder scan:** код приведён полностью во всех шагах; команды конкретны.
- **Type consistency:** имена пакетов `com.arashivision.orientation.panorama`/`.detection` едины во всех задачах; типы (`PanoramaDirection`, `VideoDetectionSidecar`, `VideoDetectionFrame`, `BboxXyxy`, `Point2d`, `VideoDetectionTimeline`) совпадают между переносом (Task 4) и тестами (Task 7).
- **Риск:** парсер — единственная смена логики; покрыт 10 тестами на 4 формата + edge. Промежуточные коммиты (Task 2–5) оставляют :app некомпилируемым до Task 8 — это нормально для последовательной ветки, итоговая компиляция проверяется в Task 9 перед push.
