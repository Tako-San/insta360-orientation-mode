# Insta360 Test Safety-Net Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Вернуть ветку `refactoring` в собираемое из clone состояние, покрыть чистую математику ориентации/проекций характеризационными JVM-тестами и завести GitHub Actions CI — как safety net перед глубоким рефакторингом.

**Architecture:** Воссоздать заигноренную Gradle-инфраструктуру (settings/version-catalog/wrapper/properties) и разигнорить её; сделать один поведение-нейтральный extract чистой функции из `GyroOrientationController.onSensorChanged`; добавить JUnit4-тесты на уже-чистую математику (`Quaternion`, извлечённую функцию, проекции); добавить CI workflow с тестами+lint+debug-APK (SDK тянется с приватного Nexus по секретам).

**Tech Stack:** Gradle 8.13, AGP 8.12.3, Kotlin 2.0.21, JDK 17, Android compileSdk 35 / minSdk 29, JUnit 4, Insta360 SDK 1.8.1_build_06 (private Nexus), Media3 1.5.1, GitHub Actions.

---

## Окружение и предпосылки (прочитать перед стартом)

- **Nexus Insta360:** `http://nexus.arashivision.com:9999/repository/maven-releases/`, `isAllowInsecureProtocol = true` (HTTP), basic auth — креды задокументированы в `AGENTS.md` репозитория (НЕ дублировать их в build-файлах/плане в открытом виде). Версия SDK `1.8.1_build_06`.
- **Обращение с кредами:** username/password Nexus берутся из внешнего источника — env-переменных `ORG_GRADLE_PROJECT_instaNexusUser`/`...Password` либо из `~/.gradle/gradle.properties` (вне репо). В версионируемые файлы (`gradle.properties`, `settings.gradle.kts`) пароль НЕ пишем. (Замечание о долге: креды уже утекли в `AGENTS.md` в истории git — отдельной задачей стоит их ротация и вынос из репо; в рамках этого плана мы лишь не усугубляем.)
- **Версии:** Gradle **8.13**, AGP 8.12.3, Kotlin 2.0.21, NDK 25.2.9519653, Media3 1.5.1 (объявляется прямо в `app/build.gradle.kts`, не через catalog).
- **Модуль `:lib`** из `AGENTS.md` в коде **отсутствует** — вся математика и тесты живут в `:app`. Мы НЕ создаём `:lib`; тестируем математику на месте.
- **Эта сессия не может верифицировать сборку:** в окружении нет Android SDK (`ANDROID_HOME` пуст) и нет `gradle`. Шаги «Run: ./gradlew …» — это **чекпоинты для пользователя** на его машине (где `JAVA_HOME=Android Studio jbr`, есть SDK и сеть до Nexus). Агент пишет код и коммитит; пользователь прогоняет и подтверждает.
- Все коммиты — в ветку `refactoring`, **локально, без push** (push — отдельное явное согласование).
- `glide_transformations.jar` (заигноренный `/libs`, отсутствует) → заменяем Maven-зависимостью `jp.wasabeef:glide-transformations`.

## File Structure

- Create: `settings.gradle.kts` — подключение модуля `:app`, репозитории (google/mavenCentral/Nexus).
- Create: `gradle/libs.versions.toml` — version catalog со всеми алиасами из `app/build.gradle.kts`.
- Create: `gradle/wrapper/gradle-wrapper.properties` — Gradle 8.13 wrapper.
- Create: `gradle/wrapper/gradle-wrapper.jar` — генерируется `gradle wrapper` (пользователем) либо берётся из дистрибутива.
- Create: `gradle.properties` — AndroidX/Kotlin флаги + Nexus креды как properties.
- Modify: `.gitignore` — разигнорить settings/gradle/gradle.properties/wrapper.
- Modify: `app/.gitignore` — оставить `/build`, но убрать `/libs` уже неактуально (jar заменён Maven).
- Modify: `app/build.gradle.kts` — заменить `files("libs/glide_transformations.jar")` на Maven, поправить repositories при необходимости.
- Modify: `app/src/main/java/com/arashivision/sdk/demo/ui/capture/GyroOrientationController.kt` — extract `computeTargetOrientation`.
- Create: `app/src/test/java/com/arashivision/sdk/demo/ui/capture/GyroOrientationControllerMathTest.kt` — тесты Quaternion + computeTargetOrientation.
- Create: `.github/workflows/ci.yml` — CI.

---

## Task 1: Воссоздать version catalog

**Files:**
- Create: `gradle/libs.versions.toml`

- [ ] **Step 1: Написать version catalog**

Содержит ВСЕ алиасы, на которые ссылается `app/build.gradle.kts` и корневой `build.gradle.kts`.

```toml
[versions]
agp = "8.12.3"
kotlin = "2.0.21"
insta = "1.8.1_build_06"
coreKtx = "1.13.1"
appcompat = "1.7.0"
material = "1.12.0"
constraintlayout = "2.1.4"
recyclerview = "1.3.2"
preference = "1.2.1"
swiperefreshlayout = "1.1.0"
lifecycle = "2.8.7"
coroutines = "1.9.0"
junit = "4.13.2"
androidxJunit = "1.2.1"
espresso = "3.6.1"
glide = "4.16.0"
lottie = "6.5.2"
xlog = "1.11.1"
immersionbar = "3.2.2"
xxpermissions = "18.6"
flowlayout = "1.1.2"
filepicker = "2.1.0"
glideTransformations = "4.3.0"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-appcompat = { module = "androidx.appcompat:appcompat", version.ref = "appcompat" }
material = { module = "com.google.android.material:material", version.ref = "material" }
constraintlayout = { module = "androidx.constraintlayout:constraintlayout", version.ref = "constraintlayout" }
recyclerview = { module = "androidx.recyclerview:recyclerview", version.ref = "recyclerview" }
preference = { module = "androidx.preference:preference", version.ref = "preference" }
preference-ktx = { module = "androidx.preference:preference-ktx", version.ref = "preference" }
androidx-viewbinding = { module = "androidx.databinding:viewbinding", version.ref = "agp" }
swiperefreshlayout = { module = "androidx.swiperefreshlayout:swiperefreshlayout", version.ref = "swiperefreshlayout" }
lifecycle-viewmodel-ktx = { module = "androidx.lifecycle:lifecycle-viewmodel-ktx", version.ref = "lifecycle" }
lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycle" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
glide = { module = "com.github.bumptech.glide:glide", version.ref = "glide" }
glide-compiler = { module = "com.github.bumptech.glide:compiler", version.ref = "glide" }
glide-transformations = { module = "jp.wasabeef:glide-transformations", version.ref = "glideTransformations" }
lottie = { module = "com.airbnb.android:lottie", version.ref = "lottie" }
xlog = { module = "com.elvishew:xlog", version.ref = "xlog" }
immersionbar = { module = "com.geyifeng.immersionbar:immersionbar", version.ref = "immersionbar" }
xx-permissions = { module = "com.github.getActivity:XXPermissions", version.ref = "xxpermissions" }
flowlayout = { module = "com.nex3z:flow-layout", version.ref = "flowlayout" }
filepicker = { module = "com.github.angads25:filepicker", version.ref = "filepicker" }
insta-camera = { module = "com.arashivision.sdk:sdkcamera", version.ref = "insta" }
insta-media = { module = "com.arashivision.sdk:sdkmedia", version.ref = "insta" }
androidx-junit = { module = "androidx.test.ext:junit", version.ref = "androidxJunit" }
androidx-espresso-core = { module = "androidx.test.espresso:espresso-core", version.ref = "espresso" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
jetbrains-kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
```

> ПРИМЕЧАНИЕ для исполнителя: точные версии транзитивных библиотек (lottie, xlog, immersionbar, xx-permissions, flowlayout, filepicker, glide) — это лучшая оценка по публичным координатам. Если на шаге верификации (Task 9) Gradle не разрешит какую-то версию, исполнитель поднимает/опускает её и повторяет; алиасы менять НЕЛЬЗЯ (на них завязан `app/build.gradle.kts`).

- [ ] **Step 2: Commit**

```bash
git add gradle/libs.versions.toml
git commit -m "build: воссоздать version catalog (libs.versions.toml)"
```

---

## Task 2: Воссоздать settings.gradle.kts с репозиториями

**Files:**
- Create: `settings.gradle.kts`

- [ ] **Step 1: Написать settings.gradle.kts**

```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven {
            isAllowInsecureProtocol = true
            url = uri("http://nexus.arashivision.com:9999/repository/maven-releases/")
            credentials {
                // Креды берутся из ~/.gradle/gradle.properties или env
                // ORG_GRADLE_PROJECT_instaNexusUser / ...Password. В репо НЕ хранятся.
                username = providers.gradleProperty("instaNexusUser").orNull
                password = providers.gradleProperty("instaNexusPassword").orNull
            }
        }
    }
}

rootProject.name = "insta360-orientation-mode"
include(":app")
```

> `jitpack.io` нужен для `com.github.*` зависимостей (XXPermissions, filepicker). Если какая-то из них на самом деле в mavenCentral — jitpack просто не помешает.

- [ ] **Step 2: Commit**

```bash
git add settings.gradle.kts
git commit -m "build: воссоздать settings.gradle.kts с репозиториями (вкл. Nexus)"
```

---

## Task 3: gradle.properties и wrapper

**Files:**
- Create: `gradle.properties`
- Create: `gradle/wrapper/gradle-wrapper.properties`

- [ ] **Step 1: Написать gradle.properties**

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

> Креды Nexus в этот (версионируемый) файл НЕ пишем. Пользователь добавляет их в
> `~/.gradle/gradle.properties` (вне репо):
> ```properties
> instaNexusUser=<см. AGENTS.md>
> instaNexusPassword=<см. AGENTS.md>
> ```
> либо экспортирует `ORG_GRADLE_PROJECT_instaNexusUser` / `ORG_GRADLE_PROJECT_instaNexusPassword`.

- [ ] **Step 2: Написать gradle-wrapper.properties**

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.13-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 3 (пользователь): сгенерировать gradle-wrapper.jar**

Run (на машине пользователя, где есть gradle/Android Studio):
```bash
gradle wrapper --gradle-version 8.13
```
Expected: создаются/обновляются `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`. (`gradlew` в репо уже есть; jar и .bat — нет.)

- [ ] **Step 4: Commit**

```bash
git add gradle.properties gradle/wrapper/gradle-wrapper.properties gradle/wrapper/gradle-wrapper.jar gradlew.bat
git commit -m "build: воссоздать gradle.properties и wrapper (Gradle 8.13)"
```

---

## Task 4: Разигнорить build-инфраструктуру

**Files:**
- Modify: `.gitignore`

- [ ] **Step 1: Убрать из `.gitignore` строки, прячущие build-файлы**

Удалить строки: `gradlew.bat`, `gradle.properties`, `settings.gradle.kts`, и заменить `/gradle` так, чтобы НЕ игнорировать catalog и wrapper, но игнорировать кэш `.gradle`.

Текущий `.gitignore`:
```
*.iml
.gradle
/.idea
/.kotlin
/build
/gradle
/lib
/local.properties
gradlew.bat
gradle.properties
settings.gradle.kts
local.properties
```

Новый `.gitignore`:
```
*.iml
.gradle
/.idea
/.kotlin
/build
/local.properties
local.properties
```

> Убрали `/gradle` (теперь catalog+wrapper версионируются), `/lib`, `gradlew.bat`, `gradle.properties`, `settings.gradle.kts`. Оставили `.gradle` (кэш), `/build`, `/.idea`, `local.properties`.

- [ ] **Step 2: Commit**

```bash
git add .gitignore
git commit -m "build: версионировать settings/gradle catalog/wrapper/properties"
```

---

## Task 5: Заменить локальный glide jar Maven-зависимостью

**Files:**
- Modify: `app/build.gradle.kts:111`
- Modify: `app/.gitignore`

- [ ] **Step 1: Заменить files(...) на Maven-зависимость**

В `app/build.gradle.kts` заменить строку:
```kotlin
    implementation(files("libs/glide_transformations.jar"))
```
на:
```kotlin
    implementation(libs.glide.transformations)
```

> `jp.wasabeef:glide-transformations` — публичная замена vendored jar. Если код использует классы, которых нет в этой библиотеке (проверить импорты `jp.wasabeef.*` в Task 9), исполнитель сообщает пользователю — возможно, потребуется вернуть jar коммитом.

- [ ] **Step 2: Проверить, какие классы glide-transformations реально используются**

Run:
```bash
grep -rn "jp.wasabeef" app/src/main || echo "нет прямых импортов jp.wasabeef"
```
Expected: либо список импортов (тогда сверить с API библиотеки), либо «нет прямых импортов» (тогда jar был транзитивным/неиспользуемым — замена безопасна).

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts app/.gitignore
git commit -m "build: заменить vendored glide_transformations.jar Maven-зависимостью"
```

---

## Task 6: Extract computeTargetOrientation (поведение-нейтральный)

**Files:**
- Modify: `app/src/main/java/com/arashivision/sdk/demo/ui/capture/GyroOrientationController.kt`

- [ ] **Step 1: Добавить чистую функцию и data class в companion object**

В `companion object` (после полей sensivity/invert*) добавить:

```kotlin
data class TargetOrientation(val yawDeg: Float, val pitchDeg: Float)

/**
 * Чистое преобразование Euler-углов из сглаженного относительного кватерниона
 * в целевые (yaw, pitch) для плеера: масштаб по чувствительности, инверсия осей,
 * клампинг. Вынесено из onSensorChanged для тестируемости. Поведение идентично.
 */
fun computeTargetOrientation(
    eulerYawDeg: Float,
    eulerPitchDeg: Float,
    sensivity: Float,
    invertYaw: Boolean,
    invertPitch: Boolean
): TargetOrientation {
    val yawFactor = 0.04f
    val pitchFactor = 0.02f
    val maxYaw = 360f
    val maxPitch = 270f

    val targetYaw = eulerYawDeg * (yawFactor * sensivity) * if (invertYaw) -1f else 1f
    val targetPitch = eulerPitchDeg * (pitchFactor * sensivity) * if (invertPitch) -1f else 1f

    return TargetOrientation(
        yawDeg = targetYaw.coerceIn(-maxYaw, maxYaw),
        pitchDeg = targetPitch.coerceIn(-maxPitch, maxPitch)
    )
}
```

- [ ] **Step 2: Заменить инлайн-блок в onSensorChanged на вызов функции**

Заменить блок (текущие строки ~203–213):
```kotlin
            val targetYaw = yaw * yawSensitivity * if (invertYaw) -1f else 1f
            val targetPitch = pitch * pitchSensitivity * if (invertPitch) -1f else 1f

            val maxYaw = 360f
            val maxPitch = 270f

            val clampedYaw = targetYaw.coerceIn(-maxYaw, maxYaw)
            val clampedPitch = targetPitch.coerceIn(-maxPitch, maxPitch)

            smoothedYaw = clampedYaw
            smoothedPitch = clampedPitch
```
на:
```kotlin
            val target = computeTargetOrientation(yaw, pitch, sensivity, invertYaw, invertPitch)
            smoothedYaw = target.yawDeg
            smoothedPitch = target.pitchDeg
```

> Эквивалентность: `yawSensitivity = yawFactor * sensivity` (0.04f), `pitchSensitivity = pitchFactor * sensivity` (0.02f) — функция воспроизводит ту же арифметику. После замены приватные `yawFactor`/`pitchFactor`/`yawSensitivity`/`pitchSensitivity` в companion остаются используемыми только если на них ссылается что-то ещё; проверить grep и удалить осиротевшие (см. Step 3).

- [ ] **Step 3: Убрать осиротевшие приватные поля, если больше не используются**

Run:
```bash
grep -n "yawSensitivity\|pitchSensitivity\|yawFactor\|pitchFactor" app/src/main/java/com/arashivision/sdk/demo/ui/capture/GyroOrientationController.kt
```
Expected: если `yawSensitivity`/`pitchSensitivity`/`yawFactor`/`pitchFactor` больше нигде не читаются — удалить их объявления из companion (строки ~51–56). Если используются (напр. в логировании строки ~225) — оставить как есть.

> ВАЖНО: на строке ~225 в логе есть `yaw * yawSensitivity` и `pitch * pitchSensitivity`. Значит поля используются в логировании — НЕ удалять, оставить. Тогда Step 3 сводится к подтверждению, что поля ещё нужны.

- [ ] **Step 4 (пользователь): убедиться, что компилируется**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (требует SDK+Nexus — чекпоинт пользователя).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/arashivision/sdk/demo/ui/capture/GyroOrientationController.kt
git commit -m "refactor: вынести computeTargetOrientation из onSensorChanged (поведение неизменно)"
```

---

## Task 7: Тесты на computeTargetOrientation

**Files:**
- Create: `app/src/test/java/com/arashivision/sdk/demo/ui/capture/GyroOrientationControllerMathTest.kt`

- [ ] **Step 1: Написать падающий тест-класс (часть 1: computeTargetOrientation)**

```kotlin
package com.arashivision.sdk.demo.ui.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class GyroOrientationControllerMathTest {

    private val eps = 1e-4f

    @Test
    fun targetScalesYawByPointZeroFourTimesSensitivity() {
        val t = GyroOrientationController.computeTargetOrientation(
            eulerYawDeg = 100f, eulerPitchDeg = 0f,
            sensivity = 1f, invertYaw = false, invertPitch = false
        )
        // 100 * 0.04 * 1 = 4.0
        assertEquals(4.0f, t.yawDeg, eps)
        assertEquals(0.0f, t.pitchDeg, eps)
    }

    @Test
    fun targetScalesPitchByPointZeroTwoTimesSensitivity() {
        val t = GyroOrientationController.computeTargetOrientation(
            eulerYawDeg = 0f, eulerPitchDeg = 100f,
            sensivity = 1.2f, invertYaw = false, invertPitch = false
        )
        // 100 * 0.02 * 1.2 = 2.4
        assertEquals(2.4f, t.pitchDeg, eps)
    }

    @Test
    fun invertYawNegatesYaw() {
        val t = GyroOrientationController.computeTargetOrientation(
            eulerYawDeg = 100f, eulerPitchDeg = 0f,
            sensivity = 1f, invertYaw = true, invertPitch = false
        )
        assertEquals(-4.0f, t.yawDeg, eps)
    }

    @Test
    fun invertPitchNegatesPitch() {
        val t = GyroOrientationController.computeTargetOrientation(
            eulerYawDeg = 0f, eulerPitchDeg = 100f,
            sensivity = 1f, invertYaw = false, invertPitch = true
        )
        // 100 * 0.02 * 1 * -1 = -2.0
        assertEquals(-2.0f, t.pitchDeg, eps)
    }

    @Test
    fun yawIsClampedToPlusMinus360() {
        val hi = GyroOrientationController.computeTargetOrientation(
            eulerYawDeg = 100000f, eulerPitchDeg = 0f,
            sensivity = 10f, invertYaw = false, invertPitch = false
        )
        assertEquals(360.0f, hi.yawDeg, eps)

        val lo = GyroOrientationController.computeTargetOrientation(
            eulerYawDeg = -100000f, eulerPitchDeg = 0f,
            sensivity = 10f, invertYaw = false, invertPitch = false
        )
        assertEquals(-360.0f, lo.yawDeg, eps)
    }

    @Test
    fun pitchIsClampedToPlusMinus270() {
        val hi = GyroOrientationController.computeTargetOrientation(
            eulerYawDeg = 0f, eulerPitchDeg = 100000f,
            sensivity = 10f, invertYaw = false, invertPitch = false
        )
        assertEquals(270.0f, hi.pitchDeg, eps)
    }
}
```

- [ ] **Step 2 (пользователь): прогнать — тесты проходят**

Run: `./gradlew testDebugUnitTest --tests "*GyroOrientationControllerMathTest*"`
Expected: PASS (6 тестов). Если FAIL — extract в Task 6 исказил арифметику; сверить со Step 1 Task 6.

- [ ] **Step 3: Проверка, что тесты не пустышки**

Временно поменять в `computeTargetOrientation` `0.04f` на `0.05f`, прогнать — `targetScalesYawByPointZeroFourTimesSensitivity` должен УПАСТЬ. Вернуть `0.04f`. (Это ручная проверка исполнителем/пользователем, не коммитится.)

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/arashivision/sdk/demo/ui/capture/GyroOrientationControllerMathTest.kt
git commit -m "test: характеризационные тесты computeTargetOrientation"
```

---

## Task 8: Тесты на Quaternion

**Files:**
- Modify: `app/src/test/java/com/arashivision/sdk/demo/ui/capture/GyroOrientationControllerMathTest.kt`

- [ ] **Step 1: Добавить тесты Quaternion в тот же класс**

Тип: `GyroOrientationController.Quaternion`. Добавить методы в существующий класс (перед закрывающей `}`):

```kotlin
    private val qeps = 1e-4f

    @Test
    fun identityQuaternionHasZeroEulerAngles() {
        val q = GyroOrientationController.Quaternion(1f, 0f, 0f, 0f)
        val (yaw, pitch, roll) = q.toEulerAngles()
        assertEquals(0.0f, yaw, qeps)
        assertEquals(0.0f, pitch, qeps)
        assertEquals(0.0f, roll, qeps)
    }

    @Test
    fun conjugateNegatesVectorPart() {
        val q = GyroOrientationController.Quaternion(0.5f, 0.1f, 0.2f, 0.3f)
        val c = q.conjugate()
        assertEquals(0.5f, c.w, qeps)
        assertEquals(-0.1f, c.x, qeps)
        assertEquals(-0.2f, c.y, qeps)
        assertEquals(-0.3f, c.z, qeps)
    }

    @Test
    fun multiplyByIdentityReturnsNormalizedSelf() {
        val q = GyroOrientationController.Quaternion(1f, 0f, 0f, 0f)
        val id = GyroOrientationController.Quaternion(1f, 0f, 0f, 0f)
        val r = q.multiply(id)
        assertEquals(1.0f, r.w, qeps)
        assertEquals(0.0f, r.x, qeps)
    }

    @Test
    fun multiplyByConjugateGivesIdentity() {
        // unit quaternion (rotation 90° about Z): w=cos45, z=sin45
        val s = kotlin.math.sqrt(0.5f)
        val q = GyroOrientationController.Quaternion(s, 0f, 0f, s)
        val r = q.multiply(q.conjugate())
        assertEquals(1.0f, r.w, qeps)
        assertEquals(0.0f, r.x, qeps)
        assertEquals(0.0f, r.y, qeps)
        assertEquals(0.0f, r.z, qeps)
    }

    @Test
    fun normalizeMakesUnitMagnitude() {
        val q = GyroOrientationController.Quaternion(2f, 0f, 0f, 0f).normalize()
        assertEquals(1.0f, q.magnitude(), qeps)
    }

    @Test
    fun fromRotationMatrixIdentityIsIdentityQuaternion() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val q = GyroOrientationController.Quaternion.fromRotationMatrix(identity)
        assertEquals(1.0f, q.w, qeps)
        assertEquals(0.0f, q.x, qeps)
        assertEquals(0.0f, q.y, qeps)
        assertEquals(0.0f, q.z, qeps)
    }

    @Test
    fun fromRotationMatrix180AboutXUsesDiagonalBranch() {
        // 180° about X: diag(1, -1, -1) → trace = -1, m00 largest
        val m = floatArrayOf(1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, -1f)
        val q = GyroOrientationController.Quaternion.fromRotationMatrix(m)
        // expect quaternion (0, 1, 0, 0) up to sign/normalization
        assertEquals(1.0f, kotlin.math.abs(q.x), qeps)
        assertEquals(0.0f, q.w, qeps)
    }

    @Test
    fun slerpAtZeroReturnsStart() {
        val a = GyroOrientationController.Quaternion(1f, 0f, 0f, 0f)
        val s = kotlin.math.sqrt(0.5f)
        val b = GyroOrientationController.Quaternion(s, 0f, 0f, s)
        val r = GyroOrientationController.Quaternion.slerp(a, b, 0f)
        assertEquals(a.w, r.w, qeps)
        assertEquals(a.z, r.z, qeps)
    }

    @Test
    fun slerpAtOneReturnsEnd() {
        val a = GyroOrientationController.Quaternion(1f, 0f, 0f, 0f)
        val s = kotlin.math.sqrt(0.5f)
        val b = GyroOrientationController.Quaternion(s, 0f, 0f, s)
        val r = GyroOrientationController.Quaternion.slerp(a, b, 1f)
        assertEquals(b.w, r.w, qeps)
        assertEquals(b.z, r.z, qeps)
    }

    @Test
    fun toEulerAnglesClampsGimbalLockPitch() {
        // pitch = +90°: 2(wy - zx) = 1 → e.g. rotation about Y by 90°
        val s = kotlin.math.sqrt(0.5f)
        val q = GyroOrientationController.Quaternion(s, 0f, s, 0f)
        val (_, pitch, _) = q.toEulerAngles()
        assertEquals(90.0f, pitch, 1e-2f)
    }
```

> Добавить недостающие импорты в начало файла: `import org.junit.Assert.assertEquals` уже есть; `kotlin.math.*` используются как fully-qualified, импорт не обязателен.

- [ ] **Step 2 (пользователь): прогнать весь класс**

Run: `./gradlew testDebugUnitTest --tests "*GyroOrientationControllerMathTest*"`
Expected: PASS (все тесты: 6 из Task 7 + 10 новых).

> Если `fromRotationMatrix180AboutXUsesDiagonalBranch` или `toEulerAnglesClampsGimbalLockPitch` упадут — это характеризационные тесты, фиксируй ФАКТИЧЕСКОЕ значение, которое выдаёт код (подставь реальный результат в assert), т.к. цель — зафиксировать текущее поведение, а не математический идеал.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/arashivision/sdk/demo/ui/capture/GyroOrientationControllerMathTest.kt
git commit -m "test: характеризационные тесты Quaternion (multiply/conjugate/slerp/fromRotationMatrix/euler)"
```

---

## Task 9: Верификация полной сборки и тестов (чекпоинт пользователя)

**Files:** нет (только прогон)

- [ ] **Step 1 (пользователь): чистая сборка**

Run: `./gradlew clean assembleDebug`
Expected: BUILD SUCCESSFUL. Если падает на разрешении зависимости — поправить версию в `gradle/libs.versions.toml` (НЕ алиас) и повторить. Если падает на `jp.wasabeef` API — вернуть vendored jar (откатить Task 5) и сообщить.

- [ ] **Step 2 (пользователь): все юнит-тесты**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, включая существующие `PanoramaFovMathTest`/`EquirectangularProjectionTest` и новый `GyroOrientationControllerMathTest`.

- [ ] **Step 3 (пользователь): lint**

Run: `./gradlew lint`
Expected: завершается (warnings допустимы; падать на error не должен — если падает, зафиксировать baseline отдельной задачей).

- [ ] **Step 4: Commit любых правок версий**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build: исправить версии зависимостей по результатам верификации"
```

---

## Task 10: GitHub Actions CI

**Files:**
- Create: `.github/workflows/ci.yml`

- [ ] **Step 1: Написать workflow**

```yaml
name: CI

on:
  push:
  pull_request:

jobs:
  build-and-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Unit tests
        run: ./gradlew testDebugUnitTest
        env:
          ORG_GRADLE_PROJECT_instaNexusUser: ${{ secrets.INSTA_NEXUS_USER }}
          ORG_GRADLE_PROJECT_instaNexusPassword: ${{ secrets.INSTA_NEXUS_PASSWORD }}

      - name: Lint
        run: ./gradlew lint
        env:
          ORG_GRADLE_PROJECT_instaNexusUser: ${{ secrets.INSTA_NEXUS_USER }}
          ORG_GRADLE_PROJECT_instaNexusPassword: ${{ secrets.INSTA_NEXUS_PASSWORD }}

      - name: Assemble debug APK
        run: ./gradlew assembleDebug
        env:
          ORG_GRADLE_PROJECT_instaNexusUser: ${{ secrets.INSTA_NEXUS_USER }}
          ORG_GRADLE_PROJECT_instaNexusPassword: ${{ secrets.INSTA_NEXUS_PASSWORD }}

      - name: Upload debug APK
        uses: actions/upload-artifact@v4
        with:
          name: debug-apk
          path: app/build/outputs/apk/debug/*.apk
          if-no-files-found: warn
```

> Секреты `INSTA_NEXUS_USER`/`INSTA_NEXUS_PASSWORD` пользователь заводит в Settings → Secrets репозитория (значения — из `AGENTS.md`). `ORG_GRADLE_PROJECT_*` env-переменные Gradle подхватывает как project properties `instaNexusUser`/`instaNexusPassword`. Nexus по HTTP должен быть доступен из GitHub-раннера; если firewall закрывает — APK-сборку в CI отключить, оставив только тесты на закешированных зависимостях (зафиксировать как ограничение).

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: GitHub Actions — тесты, lint, debug APK"
```

- [ ] **Step 3 (пользователь): завести секреты и проверить прогон**

Завести секреты `INSTA_NEXUS_USER`/`INSTA_NEXUS_PASSWORD` в Settings → Secrets репозитория (значения — из `AGENTS.md`). После push ветки (отдельное согласование) — проверить, что workflow зелёный.

---

## Self-Review

- **Spec coverage:** Фаза 0 → Tasks 1–5, 9; Фаза 1 (extract) → Task 6; Фаза 2 (тесты) → Tasks 7–8; Фаза 3 (CI) → Task 10. Догрузка тестов проекций из spec — опущена осознанно: существующие `PanoramaFovMathTest`/`EquirectangularProjectionTest` уже покрывают эти методы, добавлять кейсы без доступа к прогону рискованно; зафиксировано как возможная отдельная задача.
- **Границы spec:** `Quaternion` не двигается ✓, `OrientationSink` не вводится ✓, рефлексия/Vr/ViewModel не трогаются ✓, release-keystore не правится ✓.
- **Placeholder scan:** все шаги содержат конкретный код/команды; «лучшая оценка версий» помечена явно с процедурой исправления на верификации.
- **Type consistency:** `computeTargetOrientation`/`TargetOrientation` определены в Task 6, используются идентично в Tasks 6–7. `GyroOrientationController.Quaternion` — публичный путь к существующему вложенному типу, используется в Task 8.
- **Главный риск:** агент не может верифицировать сборку (нет SDK в окружении) — все `./gradlew` помечены как чекпоинты пользователя; версии в catalog — оценка, исправляется на Task 9.
