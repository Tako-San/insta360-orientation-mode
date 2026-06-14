# 360 Panorama Player v2 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build, from scratch, a testable, modifiable, jank-free Android app that plays local equirectangular 360 videos with gyroscope look-around, an out-of-FOV direction arrow, and VR split-screen — per the approved design spec.

**Architecture:** Three Gradle modules, strictly one-way deps `:app → :android → :core`. `:core` is pure-JVM (math via `kotlin-math`, all calibration/detection/FOV/stereo logic, ~100% unit-tested, blocking CI gate). `:android` owns GL ES 2 renderer + SensorManager + ExoPlayer→SurfaceTexture + adapters. `:app` is Compose + Hilt. Smoothness via 3 threads + lock-free `AtomicReference<GazeState>`, Choreographer-driven `WHEN_DIRTY` rendering, predictive rotation, and single-context VR dual-viewport. Calibration confined to two tunable sites + a release-gating golden-image device test.

**Tech Stack:** Kotlin 2.3.21, AGP 9.2.1, Java 17, minSdk 29 / targetSdk 35 / compileSdk 36, `dev.romainguy:kotlin-math` 1.6.0 (see pin rationale), media3 1.10.1, Hilt 2.59.2 (KSP 2.3.9), Compose BOM 2026.05.01, Kotest 6.1.11 (property), MockK 1.14.11, Robolectric 4.16.1, Turbine 1.2.1, Kover 0.9.8.

**Spec:** `docs/superpowers/specs/2026-06-14-v2-from-scratch-design.md`. **Branch:** `rewrite-v2`. **Project root:** `panorama-v2/` (new Gradle build inside this repo).

---

## Version pin rationale (READ FIRST)

KSP has **no release for Kotlin 2.4.0** yet (verified 2026-06-14). Hilt's compiler needs KSP. To avoid a build blocker we pin the **last stable KSP-ready line**: **Kotlin 2.3.21 + KSP 2.3.9 + Compose-compiler plugin 2.3.21**. Do NOT bump Kotlin to 2.4.0 until a matching KSP ships. AGP 9.2.1 requires Gradle 9.x + JDK 17 (we have 17). Compose compiler version MUST equal the Kotlin version (plugin `org.jetbrains.kotlin.plugin.compose`).

**kotlin-math pinned to 1.6.0 (NOT 1.8.0).** Verified at bootstrap: kotlin-math 1.7.0/1.8.0 are published as **Java-21 bytecode with inline functions** — consuming them forces the whole project onto JDK 21 (and a non-portable downloaded toolchain). 1.6.0 is the last release with Java-8 bytecode, inlines into any target, and its API (Quaternion, Float3, fromAxisAngle [degrees], `q * v`, top-level slerp/dot/length/normalize) is confirmed compatible by the Task 1.2 boundary test. The whole project stays on **Java 17** as planned.

**Bootstrap reality (AGP 9, verified):** AGP 9.0+ has **built-in Kotlin** — Android modules do NOT apply `org.jetbrains.kotlin.android` (it conflicts). Kotlin is configured via a top-level `kotlin { jvmToolchain(17) }` block; the root build pins KGP 2.3.21 + KSP 2.3.9 via `buildscript { dependencies { classpath ... } }`. **compileSdk = 36** (androidx.core 1.18.0 + media3 1.10.1 require ≥36; androidx.core 1.19.0 would require 37 — kept at 1.18.0). Gradle wrapper is **9.4.1** (AGP 9.2.1 minimum).

**Testing convention (verified):** `:core` tests run on the **JUnit5 platform with Kotest** — write them as `class X : FunSpec({ test("...") { ... } })`, NOT JUnit4 `@Test` (plain `@Test` silently produces "0 tests found" because only `kotest-runner-junit5` is on the classpath). The code blocks in Tasks 1.3–1.9 below are written `@Test`-style for readability — **translate them to Kotest FunSpec** when implementing. `:android` Robolectric tests (Phase 2) deliberately stay **JUnit4** (`@RunWith(RobolectricTestRunner::class)` + `@Test`) — that is AGP's separate Android-unit test runtime and is correct; do not "unify" it onto Kotest.

---

## File structure

```
panorama-v2/
├── settings.gradle.kts                 includes :core, :android, :app; repos
├── build.gradle.kts                    root: plugin versions via `apply false`
├── gradle.properties                   AndroidX, JVM args, kotlin.code.style
├── gradle/
│   ├── libs.versions.toml              the single version catalog
│   └── wrapper/gradle-wrapper.properties   Gradle 9.x
├── .github/workflows/ci.yml            lib+android tests, kover gate on :core
│
├── core/                               pure JVM (java-library + kotlin.jvm)
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/com/panorama/core/
│       │   ├── math/           MeshData, GazeState, value types (algebra = kotlin-math)
│       │   ├── orientation/    OrientationProcessor, OrientationSmoothing, GazePredictor
│       │   ├── calibration/    AxisConvention, ViewCalibration   ← Site A
│       │   ├── projection/     ProjectionModel, EquirectProjection
│       │   ├── fov/            PanoramaFov, ArrowResolver, ArrowState
│       │   ├── detection/      Detection, SidecarParser, DetectionTimeline,
│       │   │                   SidecarDetectionSource, DetectionSource (port)
│       │   └── vr/             StereoEyeLayout, stereoGaze
│       └── test/kotlin/...     JUnit + Kotest property; ~100% of core logic
│
├── android/                            com.android.library
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/com/panorama/android/
│       │   ├── sensor/         SensorReader (HandlerThread), RemapConfig  ← Site B
│       │   ├── gl/             PanoramaRenderer, PanoramaGlView, ChoreographerDriver
│       │   ├── media/          ExoVideoPlayer (ExoPlayer→SurfaceTexture)
│       │   └── detection/      SidecarLoader (content-URI bytes → core parser)
│       └── test/  + androidTest/   Robolectric/MockK; golden-image (device)
│
└── app/                                com.android.application (Compose, Hilt)
    ├── build.gradle.kts
    └── src/main/kotlin/com/panorama/app/
        ├── PanoramaApp.kt              @HiltAndroidApp
        ├── di/                         Hilt modules (bind 2 ports + fakes)
        ├── player/                     PlayerViewModel, PlayerScreen, ArrowOverlay
        ├── library/                    LibraryScreen (SAF pick)
        └── settings/                   SettingsScreen (sensitivity/IPD/FOV)
```

---

## Phases (execution order)

- **Phase 0 — Bootstrap** (Tasks 0.1–0.4): Gradle, version catalog, 3 empty modules, CI gate. No app logic yet; gate is "everything configures and `:core:test` runs".
- **Phase 1 — `:core`** (Tasks 1.1–1.9): all pure logic, TDD, Kover ≥90%. **Blocking gate for everything downstream.** This is the bulk and the highest-value phase.
- **Phase 2 — `:android`** (Tasks 2.1–2.6): sensors+remap, GL renderer, render loop, ExoPlayer, sidecar loader. Robolectric where possible; GL bring-up is device-tier.
- **Phase 3 — `:app`** (Tasks 3.1–3.5): Hilt, screens, ViewModel, arrow overlay, wiring.
- **Phase 4 — Device gate** (Tasks 4.1–4.3): golden-image calibration test, on-device sign/feel confirmation, frame-pacing check. **Calibration signs are tuned here.**

Phases 1→2→3 are sequential (downstream needs upstream). Within Phase 1, tasks after 1.1/1.2 are largely independent and can be parallelized by the controller.

---
---

## Phase 0 — Bootstrap

### Task 0.1: Gradle project skeleton + version catalog

**Files:**
- Create: `panorama-v2/settings.gradle.kts`
- Create: `panorama-v2/build.gradle.kts`
- Create: `panorama-v2/gradle.properties`
- Create: `panorama-v2/gradle/libs.versions.toml`
- Create: `panorama-v2/gradle/wrapper/gradle-wrapper.properties`

- [ ] **Step 1: Write the version catalog**

`panorama-v2/gradle/libs.versions.toml`:
```toml
[versions]
agp = "9.2.1"
kotlin = "2.3.21"            # pinned: KSP has no 2.4.0 build yet (see plan header)
ksp = "2.3.9"
hilt = "2.59.2"
kover = "0.9.8"
coreKtx = "1.18.0"          # 1.19.0 requires compileSdk 37; 1.18.0 is the last that builds on 36
activityCompose = "1.13.0"
composeBom = "2026.05.01"
navigationCompose = "2.9.8"
lifecycle = "2.10.0"
coroutines = "1.11.0"
serialization = "1.11.0"
media3 = "1.10.1"
kotlinMath = "1.8.0"
junit = "4.13.2"
kotest = "6.1.11"
mockk = "1.14.11"
robolectric = "4.16.1"
turbine = "1.2.1"

[libraries]
kotlin-math = { group = "dev.romainguy", name = "kotlin-math", version.ref = "kotlinMath" }
kotlinx-coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serialization" }
media3-exoplayer = { group = "androidx.media3", name = "media3-exoplayer", version.ref = "media3" }
media3-common = { group = "androidx.media3", name = "media3-common", version.ref = "media3" }
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
kotest-runner-junit5 = { group = "io.kotest", name = "kotest-runner-junit5", version.ref = "kotest" }
kotest-property = { group = "io.kotest", name = "kotest-property", version.ref = "kotest" }
kotest-assertions-core = { group = "io.kotest", name = "kotest-assertions-core", version.ref = "kotest" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
mockk = { group = "io.mockk", name = "mockk", version.ref = "mockk" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
turbine = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
kover = { id = "org.jetbrains.kotlinx.kover", version.ref = "kover" }
```

- [ ] **Step 2: Write settings + root build + gradle.properties + wrapper**

`panorama-v2/settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "panorama-v2"
include(":core", ":android", ":app")
```

`panorama-v2/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kover) apply false
}
```

`panorama-v2/gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
org.gradle.caching=true
org.gradle.configuration-cache=true
```

`panorama-v2/gradle/wrapper/gradle-wrapper.properties`:
```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.4.1-bin.zip
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 3 (user): generate the Gradle wrapper jar**

The wrapper jar/scripts (`gradlew`, `gradlew.bat`, `gradle-wrapper.jar`) cannot be created from text. Ask the user to run, from `panorama-v2/`:
`gradle wrapper --gradle-version 9.0`
(or copy the wrapper from an existing project). The agent stops and requests this if the jar is absent.

- [ ] **Step 4: Commit**
```bash
git add panorama-v2/settings.gradle.kts panorama-v2/build.gradle.kts panorama-v2/gradle.properties panorama-v2/gradle/libs.versions.toml panorama-v2/gradle/wrapper/gradle-wrapper.properties
git commit -m "build(v2): gradle skeleton + version catalog"
```

---

### Task 0.2: `:core` module shell (pure JVM)

**Files:**
- Create: `panorama-v2/core/build.gradle.kts`

- [ ] **Step 1: Write the build file**
```kotlin
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { jvmToolchain(17) }
dependencies {
    api(libs.kotlin.math)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotest.assertions.core)
}
tasks.test { useJUnitPlatform() }   // Kotest runs on JUnit5 platform
kover {
    reports { verify { rule { minBound(90) } } }
}
```

- [ ] **Step 2 (CI/user): verify `:core` configures**
Run: `./gradlew :core:dependencies --configuration testRuntimeClasspath`
Expected: resolves `kotlin-math`, kotest; no Android on classpath.

- [ ] **Step 3: Commit** — `build(v2): :core pure-JVM module shell`

---

### Task 0.3: `:android` and `:app` module shells

**Files:**
- Create: `panorama-v2/android/build.gradle.kts`
- Create: `panorama-v2/android/src/main/AndroidManifest.xml` (empty `<manifest/>`)
- Create: `panorama-v2/app/build.gradle.kts`
- Create: `panorama-v2/app/src/main/AndroidManifest.xml`

- [ ] **Step 1: `:android` build file**
```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.panorama.android"
    compileSdk = 36          // androidx.core 1.19 + media3 1.10 require compileSdk >= 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    testOptions { unitTests.isIncludeAndroidResources = true }   // Robolectric
}
dependencies {
    api(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
```

- [ ] **Step 2: `:app` build file** (Compose + Hilt + KSP)
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}
android {
    namespace = "com.panorama.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.panorama.app"
        minSdk = 29; targetSdk = 35; versionCode = 1; versionName = "2.0.0"   // compileSdk = 36 above
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
}
dependencies {
    implementation(project(":core"))
    implementation(project(":android"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.media3.exoplayer)
}
```

`panorama-v2/app/src/main/AndroidManifest.xml`: minimal `<manifest>` with `<application>` (no activity yet; added Task 3.x).

- [ ] **Step 3 (CI/user): verify both configure**
Run: `./gradlew :android:assembleDebug :app:help`
Expected: BUILD SUCCESSFUL (empty modules compile).

- [ ] **Step 4: Commit** — `build(v2): :android + :app module shells`

---

### Task 0.4: CI workflow (device-free gate)

**Files:**
- Create: `panorama-v2/.github/workflows/ci.yml`

- [ ] **Step 1: Write the workflow**

Two jobs. `core-tests` is the blocking gate; `android-app` is best-effort (no device, GL not runnable in CI).
```yaml
name: v2 CI
on:
  push: { paths: ['panorama-v2/**'] }
  pull_request: { paths: ['panorama-v2/**'] }
defaults: { run: { working-directory: panorama-v2 } }
jobs:
  core-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew :core:test :core:koverVerify
  android-app:
    runs-on: ubuntu-latest
    continue-on-error: true
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew :android:testDebugUnitTest :app:assembleDebug
```

- [ ] **Step 2: Commit** — `ci(v2): core-tests blocking gate + best-effort android/app`

---
---

## Phase 1 — `:core` (pure JVM, the blocking gate)

> All Phase-1 code is pure Kotlin/JVM. TDD: write the failing test first, run it red, implement, run green, commit. Algebra (`Quaternion`, `Float3`, `Mat4`) comes from `kotlin-math` — DO NOT redefine it. Kover gate ≥90% line+branch on `:core`.

### Task 1.1: Core value types + `GazeState`

**Files:**
- Create: `core/src/main/kotlin/com/panorama/core/math/MeshData.kt`
- Create: `core/src/main/kotlin/com/panorama/core/math/GazeState.kt`
- Test: `core/src/test/kotlin/com/panorama/core/math/GazeStateTest.kt`

- [ ] **Step 1: Write the failing test**
```kotlin
package com.panorama.core.math
import dev.romainguy.kotlin.math.Quaternion
import io.kotest.matchers.shouldBe
import org.junit.Test

class GazeStateTest {
    @Test fun `identity gaze has zero yaw pitch and velocity`() {
        val g = GazeState(Quaternion(), 0f, 0f, 0f)
        g.yawDeg shouldBe 0f
        g.pitchDeg shouldBe 0f
        g.angularVelocityDegPerSec shouldBe 0f
    }
}
```
- [ ] **Step 2: Run, expect FAIL** — `./gradlew :core:test --tests "*GazeStateTest*"` → unresolved `GazeState`.
- [ ] **Step 3: Implement**
```kotlin
// MeshData.kt
package com.panorama.core.math
class MeshData(val positions: FloatArray, val texCoords: FloatArray, val indices: ShortArray)

// GazeState.kt
package com.panorama.core.math
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
/** Immutable orientation snapshot handed from the sensor thread to the GL thread.
 *  `angularAxis` + `angularVelocityDegPerSec` are the smoothed rotation rate that GazePredictor
 *  (Task 1.7) extrapolates along. `angularAxis` defaults to +Y so 4-arg construction stays valid. */
data class GazeState(
    val quaternion: Quaternion,
    val yawDeg: Float,
    val pitchDeg: Float,
    val angularVelocityDegPerSec: Float,
    val angularAxis: Float3 = Float3(0f, 1f, 0f),
)
```
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** — `feat(core): MeshData + GazeState value types`

---

### Task 1.2: `kotlin-math` boundary test

**Files:**
- Test: `core/src/test/kotlin/com/panorama/core/math/KotlinMathBoundaryTest.kt`

Guards against a silent convention shift on a future library bump. No production code.

- [ ] **Step 1: Write the tests** — assert the library behaves as we rely on:
```kotlin
package com.panorama.core.math
import dev.romainguy.kotlin.math.*
import io.kotest.matchers.floats.shouldBeWithinPercentageOf
import org.junit.Test
import kotlin.math.abs

class KotlinMathBoundaryTest {
    @Test fun `slerp endpoints return the inputs`() {
        val a = Quaternion.fromAxisAngle(Float3(0f,1f,0f), 0f)
        val b = Quaternion.fromAxisAngle(Float3(0f,1f,0f), 90f)
        // t=0 -> a, t=1 -> b (component-wise within eps, accounting for sign ambiguity)
        val s0 = slerp(a, b, 0f); val s1 = slerp(a, b, 1f)
        assert(abs(dot(s0, a)) > 0.999f)
        assert(abs(dot(s1, b)) > 0.999f)
    }
    @Test fun `normalized quaternion has unit length`() {
        val q = normalize(Quaternion(1f, 2f, 3f, 4f))
        length(q).shouldBeWithinPercentageOf(1.0f, 0.01)
    }
    @Test fun `rotating a vector by yaw quaternion is length preserving`() {
        val q = Quaternion.fromAxisAngle(Float3(0f,1f,0f), 37f)
        val v = Float3(1f, 0f, 0f)
        val r = q * v        // verify the lib's quaternion*vector rotation API & convention
        length(r).shouldBeWithinPercentageOf(1.0f, 0.01)
    }
    @Test fun `euler round-trip - build from yaw,pitch then recover within eps`() {
        // The Task 1.5 round-trip + Task 1.4 smoothing depend on Euler<->Quaternion. PIN IT HERE.
        // Confirm the actual kotlin-math 1.6.0 spelling; the helper below isolates it.
        val q = quatFromYawPitch(40f, -15f)              // see helper note
        val (yaw, pitch) = yawPitchOf(q)
        assert(abs(yaw - 40f) < 0.5f && abs(pitch - (-15f)) < 0.5f)
    }
}
```
> **NOTE to implementer (the convention contract — do this FIRST):** confirm the exact `kotlin-math`
> 1.8.0 API names against the resolved jar: `slerp`, `dot`, `length`, `normalize`, `inverse`,
> `Quaternion.fromAxisAngle`, `q * v` (vector rotation), and the **Euler factory + extraction**.
> The plan never relies on `fromEulerAngles`/`toMatrix` by name — instead define two tiny helpers
> in core (`quatFromYawPitch`, `yawPitchOf`) that are the SINGLE place Euler↔quaternion happens:
> - If the library exposes a Euler factory/extraction, wrap it.
> - **Fallback (no Euler API):** `quatFromYawPitch(yaw,pitch) = fromAxisAngle(+Y, yaw) * fromAxisAngle(+X, pitch)`;
>   `yawPitchOf` recovers angles by rotating the forward vector and reading `atan2`. This keeps the
>   whole project off any uncertain library Euler call. Tasks 1.4/1.5/1.7 use these helpers, not raw
>   library Euler calls. This test IS the convention contract — keep the assertions, adjust spelling.

- [ ] **Step 2: Run, expect PASS** (it tests the dependency).
- [ ] **Step 3: Commit** — `test(core): kotlin-math convention boundary test`

---

### Task 1.3: `OrientationProcessor` (calibration + relative quaternion)

**Files:**
- Create: `core/src/main/kotlin/com/panorama/core/orientation/OrientationProcessor.kt`
- Test: `core/src/test/kotlin/com/panorama/core/orientation/OrientationProcessorTest.kt`

Responsibility: store a calibration reference quaternion; produce a *relative* gaze quaternion (`current * reference⁻¹`). NO signs/sensitivity here (those are calibration, Task 1.5). NO smoothing (Task 1.4).

- [ ] **Step 1: Write the failing test**
```kotlin
package com.panorama.core.orientation
import dev.romainguy.kotlin.math.*
import io.kotest.matchers.floats.shouldBeWithinPercentageOf
import org.junit.Test
import kotlin.math.abs

class OrientationProcessorTest {
    @Test fun `before calibration relative equals current`() {
        val p = OrientationProcessor()
        val q = Quaternion.fromAxisAngle(Float3(0f,1f,0f), 30f)
        val rel = p.relativeTo(q)
        assert(abs(dot(rel, q)) > 0.999f)
    }
    @Test fun `after calibrating at q, relativeTo(q) is identity`() {
        val p = OrientationProcessor()
        val q = Quaternion.fromAxisAngle(Float3(0f,1f,0f), 30f)
        p.calibrate(q)
        val rel = p.relativeTo(q)
        assert(abs(dot(rel, Quaternion())) > 0.999f)  // ~identity
    }
}
```
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** — `calibrate(q)` stores `q`; `relativeTo(current)` returns
  `current * reference.inverse()` (use `kotlin-math` `inverse`/`conjugate` for unit quats); default
  reference = identity.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** — `feat(core): OrientationProcessor calibration + relative quaternion`

---

### Task 1.4: `OrientationSmoothing` (adaptive, real-dt) + smoothed angular velocity

**Files:**
- Create: `core/src/main/kotlin/com/panorama/core/orientation/OrientationSmoothing.kt`
- Test: `core/src/test/kotlin/com/panorama/core/orientation/OrientationSmoothingTest.kt`

**Spec §9 fix 3: this is a real dt-based design, NOT a per-tick port.** Alpha is computed from
angular velocity (deg/sec) using real `dt` (seconds) so behavior is frame-rate-independent. Also
emits the smoothed angular velocity used by `GazePredictor`.

Model: time-constant low-pass. `alpha = 1 - exp(-dt / tau(speed))`, where `tau` is large (heavy
smoothing) at low speed and small (responsive) at high speed:
`tau = lerp(tauStill, tauFast, clamp(speed / speedFull, 0, 1))` with defaults
`tauStill = 0.20f` s, `tauFast = 0.02f` s, `speedFull = 90f` deg/s. Smoothed value =
`slerp(prev, target, alpha)`. Smoothed speed = `angleBetween(prev, smoothed) / dt`.

- [ ] **Step 1: Write the failing tests**
```kotlin
package com.panorama.core.orientation
import dev.romainguy.kotlin.math.*
import org.junit.Test
import kotlin.math.abs

class OrientationSmoothingTest {
    @Test fun `first update returns input and zero velocity`() {
        val s = OrientationSmoothing()
        val q = Quaternion.fromAxisAngle(Float3(0f,1f,0f), 10f)
        val out = s.update(q, dtSec = 0.016f)
        assert(abs(dot(out.quaternion, q)) > 0.999f)
        assert(out.angularVelocityDegPerSec == 0f)
    }
    @Test fun `at rest, jitter is attenuated (output moves less than input delta)`() {
        val s = OrientationSmoothing()
        val a = Quaternion.fromAxisAngle(Float3(0f,1f,0f), 0f)
        s.update(a, 0.016f)
        val jitter = Quaternion.fromAxisAngle(Float3(0f,1f,0f), 1f) // 1 deg jitter
        val out = s.update(jitter, 0.016f)
        // smoothed should land well short of the full 1 deg
        val moved = angleDeg(a, out.quaternion)
        assert(moved < 0.6f) { "expected heavy damping at rest, moved=$moved" }
    }
    @Test fun `fast turn passes through with low lag`() {
        val s = OrientationSmoothing()
        var q = Quaternion.fromAxisAngle(Float3(0f,1f,0f), 0f)
        s.update(q, 0.016f)
        // simulate 300 deg/s for several frames
        repeat(6) {
            q = Quaternion.fromAxisAngle(Float3(0f,1f,0f), (it+1) * 300f * 0.016f)
            s.update(q, 0.016f)
        }
        val out = s.update(Quaternion.fromAxisAngle(Float3(0f,1f,0f), 7 * 300f * 0.016f), 0.016f)
        assert(out.angularVelocityDegPerSec > 150f) { "fast turn should report high velocity" }
    }
    // helper
    private fun angleDeg(a: Quaternion, b: Quaternion) =
        Math.toDegrees(2.0 * Math.acos(abs(dot(a,b)).coerceAtMost(1f).toDouble())).toFloat()
}
```
> `angleDeg`/`angleBetween` helper: derive from `kotlin-math` `dot`. Implementer adds an internal
> `angleDeg` util in core (small, tested implicitly here).
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** per the model above. Returns `Smoothed(quaternion, angularVelocityDegPerSec, angularAxis: Float3)`.
  The axis comes from the **same delta** used for speed: `delta = smoothed * prev.inverse()`; extract
  its rotation axis (normalize the `(x,y,z)` vector part; fall back to `Float3(0f,1f,0f)` when the
  delta is ~identity / speed≈0). This axis is what `GazeState.angularAxis` carries to `GazePredictor`
  (Task 1.7) — Task 1.4 is the sole producer of both smoothed velocity AND axis.
- [ ] **Step 4: Run, expect PASS.** Tune `tau*`/`speedFull` only to satisfy the qualitative asserts; final feel is tuned on-device (Phase 4).
- [ ] **Step 5: Commit** — `feat(core): adaptive dt-based OrientationSmoothing + smoothed velocity`

---

### Task 1.5: `AxisConvention` + `ViewCalibration` (Site A) — sign-free builders pinned by property tests

**Files:**
- Create: `core/src/main/kotlin/com/panorama/core/calibration/AxisConvention.kt`
- Create: `core/src/main/kotlin/com/panorama/core/calibration/ViewCalibration.kt`
- Test: `core/src/test/kotlin/com/panorama/core/calibration/ViewCalibrationTest.kt`
- Test: `core/src/test/kotlin/com/panorama/core/calibration/ViewCalibrationPropertyTest.kt`

**This is the load-bearing calibration task (spec §6).** ALL math signs are fields of `AxisConvention`. `ViewCalibration` is the ONLY core type producing a view matrix from gaze and mapping detection direction to world. Mesh/matrix builders elsewhere are sign-free.

`AxisConvention(yawSign: Float = -1f, pitchSign: Float = 1f, glForwardIsMinusZ: Boolean = true)`
— defaults are a starting guess; tuned on-device in Phase 4 by editing THIS file.

- [ ] **Step 1: Write the failing unit tests** (invariants + the NEGATIVE guard)
```kotlin
package com.panorama.core.calibration
import com.panorama.core.math.GazeState
import dev.romainguy.kotlin.math.*
import org.junit.Test
import kotlin.math.abs

class ViewCalibrationTest {
    private val conv = AxisConvention()  // defaults

    @Test fun `identity gaze yields identity view (sign-free builder invariant)`() {
        val m = Mat4()                                   // reused buffer (no-alloc hot path, §5.1)
        ViewCalibration.viewMatrix(gaze0(), conv, m)
        // identity gaze => view maps forward to forward; assert near-identity rotation block
        assert(isApproxIdentityRotation(m)) { "identity gaze must give identity view, got $m" }
    }

    @Test fun `increasing yaw moves a fixed world point in a consistent screen direction`() {
        val world = Float3(0f, 0f, -1f) // straight ahead
        val xAt0 = projectScreenX(world, gazeYaw(0f), conv)
        val xAt10 = projectScreenX(world, gazeYaw(10f), conv)
        // with default yawSign, +yaw should move the point one consistent way; lock the sign:
        assert(xAt10 < xAt0) { "yaw monotonicity broke: x0=$xAt0 x10=$xAt10" }
    }

    @Test fun `NEGATIVE - a wrong yawSign breaks yaw monotonicity`() {
        val wrong = conv.copy(yawSign = -conv.yawSign)
        val world = Float3(0f, 0f, -1f)
        val xAt0 = projectScreenX(world, gazeYaw(0f), wrong)
        val xAt10 = projectScreenX(world, gazeYaw(10f), wrong)
        // proves the suite actually guards the sign: the monotonicity assertion above must FLIP
        assert(xAt10 > xAt0) { "negative test: flipping yawSign must reverse the motion" }
    }
    // helpers gaze0/gazeYaw/projectScreenX/isApproxIdentityRotation defined in test util
}
```

- [ ] **Step 2: Write the round-trip property test**
```kotlin
package com.panorama.core.calibration
import io.kotest.core.spec.style.StringSpec
import io.kotest.property.Arb
import io.kotest.property.arbitrary.float
import io.kotest.property.checkAll

class ViewCalibrationPropertyTest : StringSpec({
    "gaze -> view -> screen -> recovered yaw/pitch round-trips within eps" {
        checkAll(Arb.float(-180f, 180f), Arb.float(-85f, 85f)) { yaw, pitch ->
            val recovered = roundTrip(yaw, pitch, AxisConvention())
            assert(angularError(recovered, yaw, pitch) < 0.5f)
        }
    }
})
```
> Implementer writes `roundTrip`/`angularError` test utils: build gaze from (yaw,pitch) via
> `kotlin-math` Euler→Quaternion, run `ViewCalibration.viewMatrix`, transform the forward vector,
> recover yaw/pitch, compare. These utils are the executable definition of "correct".

- [ ] **Step 3: Run, expect FAIL.**
- [ ] **Step 4: Implement** `AxisConvention` (data class) + `ViewCalibration` (object):
  - `viewMatrix(gaze: GazeState, conv: AxisConvention, out: Mat4)` — writes into `out` (no-alloc,
    §5.1); builds `Rx(pitchSign*pitch) * Ry(yawSign*yaw)` (or from the gaze quaternion) using
    `kotlin-math`; the ONLY place signs are applied. Test/util helpers (`projectScreenX`) reuse one
    `Mat4` buffer and read it.
  - `detectionDirToWorld(centerNorm: Float2, conv: AxisConvention): Float3` — equirect normalized
    center → world unit vector in the SAME basis the view matrix uses. (Uses `kotlin-math`
    `Float2`/`Float3`; the spec's `Vec2`/`UnitVector3` are aliases for these — plan naming wins.)
  - Helper builders (`SphereMesh` etc., Task 1.6) take NO sign — they build one canonical basis.
- [ ] **Step 5: Run, expect PASS** (all unit + property tests).
- [ ] **Step 6: Commit** — `feat(core): AxisConvention + ViewCalibration (Site A) with sign-guard property tests`

---

### Task 1.6: `ProjectionModel` port + `EquirectProjection` (sign-free mesh + dir→UV)

**Files:**
- Create: `core/src/main/kotlin/com/panorama/core/projection/ProjectionModel.kt`
- Create: `core/src/main/kotlin/com/panorama/core/projection/EquirectProjection.kt`
- Test: `core/src/test/kotlin/com/panorama/core/projection/EquirectProjectionTest.kt`

Port (no `shaderSource()` in v2 — spec §3/§9 fix 4): `buildMesh(stacks, slices): MeshData` and
`directionToTexUv(dir: Float3): Float2`. Mesh is an inward UV-sphere in ONE canonical basis, NO
V-flip parameter (flip is owned by runtime stMatrix — spec §6.3).

- [ ] **Step 1: Write the failing test**
```kotlin
package com.panorama.core.projection
import dev.romainguy.kotlin.math.Float3
import org.junit.Test
class EquirectProjectionTest {
    @Test fun `mesh has expected vertex and index counts`() {
        val m = EquirectProjection.buildMesh(stacks = 32, slices = 64)
        assert(m.positions.size == (32 + 1) * (64 + 1) * 3)
        assert(m.indices.size == 32 * 64 * 6)
    }
    @Test fun `mesh top vertex is at +Y (canonical, sign-free)`() {
        val m = EquirectProjection.buildMesh(2, 2)
        // first stack row is the +Y pole in the canonical basis
        assert(m.positions[1] > 0.99f) { "top vertex must be +Y" }
    }
    @Test fun `forward direction maps to horizontal-center UV`() {
        val uv = EquirectProjection.directionToTexUv(Float3(0f, 0f, -1f))
        assert(kotlin.math.abs(uv.x - 0.5f) < 0.01f)
    }
}
```
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** the port interface + equirect impl (mesh gen + dir→UV). No sign opinions.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** — `feat(core): ProjectionModel port + EquirectProjection (sign-free)`

---

### Task 1.7: `GazePredictor` (motion-to-photon lead)

**Files:**
- Create: `core/src/main/kotlin/com/panorama/core/orientation/GazePredictor.kt`
- Test: `core/src/test/kotlin/com/panorama/core/orientation/GazePredictorTest.kt`

Extrapolate gaze forward by `leadTimeMs` along current angular velocity (spec §4.3, §5.4). Default
lead used by the renderer is ~30 ms (tunable 10–60).

- [ ] **Step 1: Write the failing tests**
```kotlin
package com.panorama.core.orientation
import com.panorama.core.math.GazeState
import dev.romainguy.kotlin.math.*
import org.junit.Test
import kotlin.math.abs
class GazePredictorTest {
    @Test fun `zero velocity predicts identity (no change)`() {
        val g = GazeState(Quaternion.fromAxisAngle(Float3(0f,1f,0f),20f), 20f, 0f, 0f)
        val p = GazePredictor.predict(g, leadTimeMs = 30f)
        assert(abs(dot(p.quaternion, g.quaternion)) > 0.999f)
    }
    @Test fun `constant velocity leads forward proportionally to leadTime`() {
        val g = GazeState(Quaternion.fromAxisAngle(Float3(0f,1f,0f),0f), 0f, 0f, 90f) // 90 deg/s
        val p30 = GazePredictor.predict(g, 30f)   // +2.7 deg
        val p60 = GazePredictor.predict(g, 60f)   // +5.4 deg
        val a30 = angleDeg(g.quaternion, p30.quaternion)
        val a60 = angleDeg(g.quaternion, p60.quaternion)
        assert(a60 > a30 && abs(a60 - 2*a30) < 0.3f) { "lead must be ~linear in time" }
    }
    private fun angleDeg(a: Quaternion, b: Quaternion) =
        Math.toDegrees(2.0*Math.acos(abs(dot(a,b)).coerceAtMost(1f).toDouble())).toFloat()
}
```
> The predictor uses `GazeState.angularAxis` + `angularVelocityDegPerSec` — both already produced by
> `OrientationSmoothing` (Task 1.4) and carried on `GazeState` (Task 1.1, default +Y). No new field
> work here; just consume them. The scalar velocity also feeds the arrow/debug HUD.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** — slerp the gaze forward by `angle = velocity * leadTimeMs/1000` about the smoothed axis.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** — `feat(core): GazePredictor motion-to-photon lead`

---

### Task 1.8a: Detection model + `SidecarParser` + `DetectionTimeline`

**Files:**
- Create: `core/src/main/kotlin/com/panorama/core/detection/Detection.kt`
- Create: `core/src/main/kotlin/com/panorama/core/detection/SidecarParser.kt`
- Create: `core/src/main/kotlin/com/panorama/core/detection/DetectionTimeline.kt`
- Test: `core/src/test/kotlin/com/panorama/core/detection/SidecarParserTest.kt`
- Test: `core/src/test/kotlin/com/panorama/core/detection/DetectionTimelineTest.kt`

Sidecar JSON (kotlinx.serialization): per-frame list of objects with normalized centers. Timeline:
binary-search nearest frame by `positionMs`.

- [ ] **Step 1: Write failing parser + timeline tests**
```kotlin
// SidecarParserTest.kt
package com.panorama.core.detection
import org.junit.Test
class SidecarParserTest {
    private val json = """
      {"frames":[
        {"timeMs":0,"objects":[{"centerNorm":[0.5,0.5],"label":"drone"}]},
        {"timeMs":100,"objects":[]}
      ]}""".trimIndent()
    @Test fun `parses frames and objects`() {
        val s = SidecarParser.parse(json)
        assert(s.frames.size == 2)
        assert(s.frames[0].objects[0].label == "drone")
    }
    @Test fun `empty or malformed json yields empty sidecar, not crash`() {
        assert(SidecarParser.parse("").frames.isEmpty())
        assert(SidecarParser.parse("{garbage").frames.isEmpty())
    }
}
// DetectionTimelineTest.kt
package com.panorama.core.detection
import org.junit.Test
class DetectionTimelineTest {
    private fun tl() = DetectionTimeline(SidecarParser.parse(
      """{"frames":[{"timeMs":0,"objects":[]},{"timeMs":100,"objects":[{"centerNorm":[0.1,0.2]}]},{"timeMs":200,"objects":[]}]}"""))
    @Test fun `before first frame returns first`() { assert(tl().frameAt(-50).timeMs == 0L) }
    @Test fun `after last frame returns last`() { assert(tl().frameAt(9999).timeMs == 200L) }
    @Test fun `nearest frame chosen`() { assert(tl().frameAt(120).timeMs == 100L) }
    @Test fun `empty timeline returns empty list`() {
        assert(DetectionTimeline(SidecarParser.parse("")).detectionsAt(0).isEmpty())
    }
}
```
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** — `@Serializable` wire models (JSON `centerNorm` as `[x,y]`); the public
  domain type is `Detection(centerNorm: Float2, bboxNorm: Rect? = null, label: String? = null)`
  using `kotlin-math` `Float2` (parser converts the wire `[x,y]` list → `Float2`). Lenient Json
  `{ ignoreUnknownKeys = true; isLenient = true }`, wrap parse in try/catch → empty
  `Sidecar(frames=[])`. Binary-search `DetectionTimeline.frameAt`/`detectionsAt`.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** — `feat(core): detection model + sidecar parser + timeline`

---

### Task 1.8b: `DetectionSource` port + `SidecarDetectionSource`

**Files:**
- Create: `core/src/main/kotlin/com/panorama/core/detection/DetectionSource.kt`
- Create: `core/src/main/kotlin/com/panorama/core/detection/SidecarDetectionSource.kt`
- Test: `core/src/test/kotlin/com/panorama/core/detection/SidecarDetectionSourceTest.kt`

Port: `detectionsAt(positionMs: Long): List<Detection>` + `available: Boolean`.
`SidecarDetectionSource` wraps a `DetectionTimeline` (Task 1.8a); `available` = timeline non-empty.

- [ ] **Step 1: Write the failing test** — source over a 2-frame timeline returns the right list at a position; `available` true; an empty-sidecar source returns `[]` and `available=false`.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** the port interface + the timeline-backed impl.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** — `feat(core): DetectionSource port + SidecarDetectionSource`

---

### Task 1.9a: FOV + arrow (`PanoramaFov`, `ArrowResolver`, `ArrowState`) + cross-path trip-wire

**Files:**
- Create: `core/src/main/kotlin/com/panorama/core/fov/PanoramaFov.kt`
- Create: `core/src/main/kotlin/com/panorama/core/fov/ArrowResolver.kt` (incl. `ArrowState`)
- Test: `core/src/test/kotlin/com/panorama/core/fov/PanoramaFovTest.kt`
- Test: `core/src/test/kotlin/com/panorama/core/fov/ArrowResolverTest.kt`

`PanoramaFov.isInsideFov(gaze, targetDir, hFovRad, vFovRad)` + `arrowAngle(gaze, targetDir)` (screen
angle of an out-of-FOV target, quaternion method only). `ArrowResolver.resolve(detections, gaze,
fov, conv): ArrowState(visible, angleRad)` selects the nearest out-of-FOV detection (maps each
`Detection.centerNorm` → world via `ViewCalibration.detectionDirToWorld`, Task 1.5).

- [ ] **Step 1: Write the failing tests** (concrete asserts — including the cross-path trip-wire)
```kotlin
// PanoramaFovTest.kt
package com.panorama.core.fov
import com.panorama.core.math.GazeState
import dev.romainguy.kotlin.math.*
import org.junit.Test
class PanoramaFovTest {
    private val h = Math.toRadians(90.0).toFloat(); private val v = Math.toRadians(60.0).toFloat()
    private fun gaze0() = GazeState(Quaternion(), 0f, 0f, 0f)
    @Test fun `target straight ahead is inside fov`() {
        assert(PanoramaFov.isInsideFov(gaze0(), Float3(0f,0f,-1f), h, v))
    }
    @Test fun `target directly behind is outside fov`() {
        assert(!PanoramaFov.isInsideFov(gaze0(), Float3(0f,0f,1f), h, v))
    }
}
// ArrowResolverTest.kt
package com.panorama.core.fov
import com.panorama.core.calibration.AxisConvention
import com.panorama.core.detection.Detection
import com.panorama.core.math.GazeState
import dev.romainguy.kotlin.math.*
import org.junit.Test
class ArrowResolverTest {
    private val conv = AxisConvention()
    private val h = Math.toRadians(90.0).toFloat(); private val v = Math.toRadians(60.0).toFloat()
    private fun gaze0() = GazeState(Quaternion(), 0f, 0f, 0f)
    @Test fun `all detections inside fov - arrow hidden`() {
        val center = Detection(centerNorm = Float2(0.5f, 0.5f)) // equirect center == forward
        val s = ArrowResolver.resolve(listOf(center), gaze0(), h, v, conv)
        assert(!s.visible)
    }
    @Test fun `one detection outside fov - arrow visible`() {
        val behind = Detection(centerNorm = Float2(0.0f, 0.5f)) // far edge == behind
        val s = ArrowResolver.resolve(listOf(behind), gaze0(), h, v, conv)
        assert(s.visible && s.angleRad != null)
    }
    @Test fun `CROSS-PATH trip-wire - detection at gaze center is ALWAYS inside fov`() {
        // exercises detectionDirToWorld (calibration) + isInsideFov together; this is the property
        // that would have caught v1's two-convention bug (spec §6.4). If calibration conventions
        // disagree, the centered detection wrongly reads as outside and the arrow flickers on.
        val center = Detection(centerNorm = Float2(0.5f, 0.5f))
        val s = ArrowResolver.resolve(listOf(center), gaze0(), h, v, conv)
        assert(!s.visible) { "centered detection must be inside FOV — convention mismatch!" }
    }
}
```
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** `PanoramaFov` (quaternion FOV test + arrow angle) + `ArrowResolver` + `ArrowState(visible: Boolean, angleRad: Float?)`.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** — `feat(core): PanoramaFov + ArrowResolver + cross-path trip-wire test`

---

### Task 1.9b: VR (`StereoEyeLayout`, `stereoGaze`) + Phase 1 gate

**Files:**
- Create: `core/src/main/kotlin/com/panorama/core/vr/StereoEyeLayout.kt`
- Test: `core/src/test/kotlin/com/panorama/core/vr/StereoEyeLayoutTest.kt`

`StereoEyeLayout` (eye scale, spacing, seek-mapping helpers) + `stereoGaze(base: GazeState,
ipdYawDeg: Float): Pair<GazeState, GazeState>` splitting yaw by ∓ipd/2 (left/right).

- [ ] **Step 1: Write the failing tests** — `stereoGaze` left yaw = base − ipd/2, right = base + ipd/2 (symmetric); eye-scale ↔ seek-progress and spacing ↔ seek-progress are inverse mappings (round-trip).
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS. Then run the full Phase-1 gate:** `./gradlew :core:test :core:koverVerify` → BUILD SUCCESSFUL, coverage ≥90%.
- [ ] **Step 5: Commit** — `feat(core): StereoEyeLayout + stereoGaze (Phase 1 complete)`

---
---

## Phase 2 — `:android` (sensors, GL, media)

> Android glue. Robolectric/MockK where it pays; GL is device-tier (Phase 4). Reads/writes the
> lock-free `AtomicReference<GazeState>`. No Compose, no SDK.

### Task 2.1: `RemapConfig` (Site B) + Robolectric test

**Files:**
- Create: `android/src/main/kotlin/com/panorama/android/sensor/RemapConfig.kt`
- Test: `android/src/test/kotlin/com/panorama/android/sensor/RemapConfigTest.kt`

The native display-rotation remap (spec §6.2). `fromRotationVector(values: FloatArray, displayRotation: Int): Quaternion`
using `SensorManager.getRotationMatrixFromVector` + `remapCoordinateSystem` per `Surface.ROTATION_*`,
then matrix→quaternion (`kotlin-math`). This is the SECOND tunable calibration site; do NOT
reimplement the native remap in pure Kotlin.

- [ ] **Step 1: Write the failing Robolectric test**
```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemapConfigTest {
    @Test fun `flat device facing north yields near-zero yaw pitch for ROTATION_0`() {
        // synthetic rotation vector for identity orientation
        val values = floatArrayOf(0f, 0f, 0f, 1f)
        val q = RemapConfig.fromRotationVector(values, Surface.ROTATION_0)
        // assert resulting gaze ~ identity
    }
    @Test fun `landscape ROTATION_90 selects the correct pitch axis`() { /* ... */ }
}
```
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** the remap table + matrix→quaternion.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** — `feat(android): RemapConfig (Site B) display-rotation remap`

---

### Task 2.2: `SensorReader` (HandlerThread → AtomicReference pipeline)

**Files:**
- Create: `android/src/main/kotlin/com/panorama/android/sensor/SensorReader.kt`
- Create: `android/src/main/kotlin/com/panorama/android/sensor/OrientationEngine.kt`
- Test: `android/src/test/kotlin/com/panorama/android/sensor/OrientationEngineTest.kt`

`SensorReader`: registers `TYPE_ROTATION_VECTOR` @ `SENSOR_DELAY_FASTEST` on a dedicated
`HandlerThread`; emits `(values, timestampNs)`. `OrientationEngine`: wires SensorReader →
RemapConfig → OrientationProcessor → OrientationSmoothing → ViewCalibration-ready GazeState into
`AtomicReference<GazeState>`; exposes `gazeRef` and `calibrate()`. Test `OrientationEngine` with a
FAKE sensor source (feed canned `(values, dt)`), assert `gazeRef` updates and `calibrate()` zeroes gaze.

- [ ] **Step 1: Write the failing test** (fake sensor source feeding the engine; assert AtomicReference + calibrate).
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.** `SensorReader` takes an injectable `SensorManager`; `OrientationEngine` takes an injectable sensor-sample callback so it's testable without Android sensors.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** — `feat(android): SensorReader + OrientationEngine into lock-free GazeState`

---

### Task 2.3: `PanoramaRenderer` (GL ES 2, OES external, sign-free draw)

**Files:**
- Create: `android/src/main/kotlin/com/panorama/android/gl/PanoramaRenderer.kt`
- Create: `android/src/main/kotlin/com/panorama/android/gl/Shaders.kt`

The `GLSurfaceView.Renderer`. Uploads `MeshData` from `EquirectProjection`; OES-external sampler;
applies the runtime `stMatrix` (owns texture flip — spec §6.3); builds MVP from
`ViewCalibration.viewMatrix(predictedGaze)`. In `onDrawFrame`: if `pendingFrame` →
`updateTexImage()`+`getTransformMatrix`; read `gazeRef`; `GazePredictor.predict`; draw. VR mode: two
`glViewport` passes with `stereoGaze`. NO sign literals in this file (all via core).

Not unit-tested (GL/GPU). Compiled + device-tested (Phase 4). Keep it thin and declarative.

- [ ] **Step 1: Implement renderer + shaders** (vertex: `uMvp`,`uStMatrix`; fragment: `samplerExternalOES`). Preallocate FloatArrays; no per-frame allocation; no `synchronized`.
- [ ] **Step 2: Compile** — `./gradlew :android:compileDebugKotlin` → BUILD SUCCESSFUL.
- [ ] **Step 3: Commit** — `feat(android): PanoramaRenderer GL ES 2 (OES, predicted gaze, VR dual-viewport)`

---

### Task 2.4: `PanoramaGlView` + `ChoreographerDriver` (render loop lifecycle)

**Files:**
- Create: `android/src/main/kotlin/com/panorama/android/gl/PanoramaGlView.kt`
- Create: `android/src/main/kotlin/com/panorama/android/gl/ChoreographerDriver.kt`
- Test: `android/src/test/kotlin/com/panorama/android/gl/ChoreographerDriverTest.kt`

`PanoramaGlView`: `GLSurfaceView` (ES2, `RENDERMODE_WHEN_DIRTY`), owns the renderer + the
`SurfaceTexture`, exposes `onVideoSurfaceReady`, `setVrEnabled`, `gazeRef`. `ChoreographerDriver`
(spec §5.2, §9 fix 2): `start()` posts a self-reposting `FrameCallback` (`requestRender()` +
`postFrameCallback(this)`); `stop()` removes it; `renderOnce()` single-shot. The
**pacing logic is unit-testable** with a fake frame-callback scheduler — test that start→N ticks
→ N requestRenders, stop halts, renderOnce fires exactly one.

- [ ] **Step 1: Write the failing driver test** (fake scheduler; assert tick→render count, stop, single-shot).
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** driver (injectable scheduler abstraction over `Choreographer`) + the GL view.
- [ ] **Step 4: Wire the play/pause/seek lifecycle explicitly (spec §9 fix 2).** `PanoramaGlView`
  exposes `onPlaybackStateChanged(isPlaying: Boolean)` and `renderNow()`. The render-loop policy:
  - playback **playing** → `driver.start()` (render every VSYNC; coalesces gaze + decode);
  - playback **paused** → `driver.stop()` (the ONLY idle state — battery win is paused-only);
  - **seek while paused** → `driver.renderOnce()` (show the new frame without starting the loop).
  This binding is driven from `PlayerViewModel`/`PanoramaGlView` off `ExoVideoPlayer.isPlaying`
  (Task 2.5) — wire it where the GL view meets playback state. Add a driver test asserting the
  three transitions (playing→continuous, paused→halt, seek-paused→exactly one renderOnce).
- [ ] **Step 5: Run, expect PASS** (driver + lifecycle test). Compile the GL view.
- [ ] **Step 6: Commit** — `feat(android): PanoramaGlView + Choreographer driver + play/pause/seek render lifecycle`

---

### Task 2.5: `ExoVideoPlayer` (ExoPlayer → SurfaceTexture)

**Files:**
- Create: `android/src/main/kotlin/com/panorama/android/media/ExoVideoPlayer.kt`
- Test: `android/src/test/kotlin/com/panorama/android/media/ExoVideoPlayerTest.kt`

Wraps media3 ExoPlayer: `open(uri)`, `play()/pause()/seekTo(ms)`, `setVideoSurface(Surface)`,
`positionMs: StateFlow<Long>`, `durationMs`, `isPlaying: StateFlow<Boolean>`, `release()`. The
renderer's `SurfaceTexture`→`Surface` is attached here. Handle the first-frame/surface-ready race
(attach surface only once GL texture is ready). Test the state machine with a MockK ExoPlayer.

- [ ] **Step 1: Write the failing state-machine test** (MockK player: play/pause/seek → flows; setVideoSurface called once).
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** (ExoPlayer injectable for the test).
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** — `feat(android): ExoVideoPlayer wrapper (decode → SurfaceTexture)`

---

### Task 2.6: `SidecarLoader` (content-URI bytes → core parser)

**Files:**
- Create: `android/src/main/kotlin/com/panorama/android/detection/SidecarLoader.kt`
- Test: `android/src/test/kotlin/com/panorama/android/detection/SidecarLoaderTest.kt`

Thin: reads bytes from a `content://`/file URI (`ContentResolver`), calls `SidecarParser.parse`,
returns a `:core` `SidecarDetectionSource`. Robolectric test against a backed file. (Boundary per
spec §4.6: IO here, parsing+lookup in `:core`.)

- [ ] **Step 1: Write the failing Robolectric test** (write a temp JSON, load via URI, assert detectionsAt).
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** — `feat(android): SidecarLoader content-URI → core DetectionSource`

---
---

## Phase 3 — `:app` (Compose + Hilt)

### Task 3.1: Hilt graph + Application + bindings (2 ports + fakes)

**Files:**
- Create: `app/src/main/kotlin/com/panorama/app/PanoramaApp.kt` (`@HiltAndroidApp`)
- Create: `app/src/main/kotlin/com/panorama/app/di/AppModule.kt`
- Create: `app/src/main/kotlin/com/panorama/app/di/FakeModule.kt` (debug/test bindings)
- Update: `app/src/main/AndroidManifest.xml` (android:name=".PanoramaApp", permissions)

Bind `DetectionSource → SidecarDetectionSource` (via loader), `ProjectionModel → EquirectProjection`.
`SensorReader`/renderer/`ExoVideoPlayer` constructor-injected concretes. A `FakeDetectionSource`
for tests/demo. `CalibrationConfig`/`AxisConvention` provided as a single `@Singleton` knob.

- [ ] **Step 1: Implement** Application + modules.
- [ ] **Step 2: Compile** — `./gradlew :app:compileDebugKotlin` (KSP/Hilt processes).
- [ ] **Step 3: Commit** — `feat(app): Hilt graph + Application + port bindings`

---

### Task 3.2: `PlayerViewModel` (thin pump, StateFlow, no reducer)

**Files:**
- Create: `app/src/main/kotlin/com/panorama/app/player/PlayerViewModel.kt`
- Create: `app/src/main/kotlin/com/panorama/app/player/PlayerUiState.kt`
- Test: `app/src/test/kotlin/com/panorama/app/player/PlayerViewModelTest.kt`

Exposes `state: StateFlow<PlayerUiState>` (playbackPosMs, isPlaying, vrEnabled, arrow,
calibrationNonce) + plain methods `play/pause/seek/toggleVr/recalibrate/selectMedia`. Collects
`ExoVideoPlayer` flows + drives a throttled (~30Hz) arrow recompute on `Dispatchers.Default` from
`gazeRef` + `DetectionSource.detectionsAt` + `ArrowResolver`. NO GL/sensor/sign code. Test with
fakes + Turbine + coroutines-test.

- [ ] **Step 1: Write the failing test** (fake video source + fake detection source; method → state transitions; arrow appears for an out-of-FOV detection).
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** — `feat(app): thin PlayerViewModel + arrow throttle`

---

### Task 3.3: `PlayerScreen` + `ArrowOverlay` (Compose)

**Files:**
- Create: `app/src/main/kotlin/com/panorama/app/player/PlayerScreen.kt`
- Create: `app/src/main/kotlin/com/panorama/app/player/ArrowOverlay.kt`
- Create: `app/src/main/kotlin/com/panorama/app/player/PlayerControls.kt`

`Box { AndroidView(PanoramaGlView) ; ArrowOverlay(Canvas) ; PlayerControls }`. `ArrowOverlay` reads
`gazeRef` in a `withFrameNanos` loop (uses **unpredicted** gaze — spec §5.5) + `state.arrow`; draws
rotated arrow (two arrows at 25%/75% in VR). Controls: play/pause/seek/Recalibrate/VR-toggle wired
to VM methods. Surface lifecycle handled across VR toggle. Not unit-tested (Compose UI; visual in Phase 4).

- [ ] **Step 1: Implement** the three composables; wire AndroidView factory to pass `gazeRef`/`projectionModel`.
- [ ] **Step 2: Compile** — `./gradlew :app:compileDebugKotlin`.
- [ ] **Step 3: Commit** — `feat(app): PlayerScreen + ArrowOverlay + controls`

---

### Task 3.4: `LibraryScreen` (SAF pick video + optional sidecar)

**Files:**
- Create: `app/src/main/kotlin/com/panorama/app/library/LibraryScreen.kt`
- Create: `app/src/main/kotlin/com/panorama/app/library/LibraryViewModel.kt`

SAF `OpenDocument` to pick a local equirect `.mp4`; detect a sibling/picked `.json` sidecar. On
select → navigate to PlayerScreen with the URIs.

- [ ] **Step 1: Implement.**
- [ ] **Step 2: Compile.**
- [ ] **Step 3: Commit** — `feat(app): LibraryScreen (SAF video + sidecar pick)`

---

### Task 3.5: Navigation + `SettingsScreen` + `MainActivity`

**Files:**
- Create: `app/src/main/kotlin/com/panorama/app/MainActivity.kt` (`@AndroidEntryPoint`, NavHost)
- Create: `app/src/main/kotlin/com/panorama/app/settings/SettingsScreen.kt`
- Update: `app/src/main/AndroidManifest.xml` (launcher activity)

NavHost: Library → Player; Settings reachable. Settings sliders write sensitivity / IPD yaw / FOV /
VR scale (drive `AxisConvention`/`StereoEyeLayout`). The only UI touching calibration funnels
through the one config.

- [ ] **Step 1: Implement** activity + nav + settings.
- [ ] **Step 2: Build the APK** — `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 3: Commit** — `feat(app): MainActivity + navigation + SettingsScreen`

---
---

## Phase 4 — Device gate (calibration tuned here)

> The irreducible visual/sensor checks. **Calibration signs are confirmed/tuned in this phase** by
> editing `AxisConvention` (Site A) and `RemapConfig` (Site B) ONLY, validated by the golden-image
> test and on-device feel. This is the single planned on-device bring-up.

### Task 4.1: Golden-image GPU calibration test (release gate)

**Files:**
- Create: `android/src/androidTest/kotlin/com/panorama/android/gl/GoldenImageCalibrationTest.kt`
- Create: `android/src/androidTest/assets/equirect_chart.png` (numbered-quadrant chart, marker at yaw=0/pitch=0)

Render the chart through a fake video source on a real GPU; screenshot-assert the yaw=0/pitch=0
marker lands at screen center and quadrants are not mirrored (spec §6.4, §9 fix 1). **This is the
only net that catches a sign added in the un-unit-tested GL adapter** → it gates releases.

- [ ] **Step 1 (user/device): provide a device or emulator with GPU; run** `./gradlew :android:connectedDebugAndroidTest`.
- [ ] **Step 2:** If the marker is off-center or mirrored, fix by editing `AxisConvention` (Site A) and/or `RemapConfig` (Site B) — ONE file each. Re-run. Record the final signs in `CALIBRATION.md`.
- [ ] **Step 3: Commit** — `test(android): golden-image GPU calibration gate + CALIBRATION.md`

---

### Task 4.2: On-device feel + sign confirmation

- [ ] **Step 1 (user, real phone):** install (`:app:installDebug`), load a 360 video + sidecar, confirm:
  - look-around tracks the phone in the correct directions (yaw left/right, pitch up/down — not inverted);
  - video is smooth (no stutter) on slow and fast turns; predictive lead feels locked, not swimming or overshooting;
  - arrow points toward an out-of-FOV detection and hides when it's in view;
  - VR toggle shows two correct viewports; stereo looks right.
- [ ] **Step 2:** Tune ONLY: `AxisConvention` signs (Site A), `RemapConfig` (Site B), `leadTimeMs` (§5.4 range 10–60), smoothing `tau*`/`speedFull` (Task 1.4). Each change re-validated by `:core:test` (signs) + on-device.
- [ ] **Step 3: Commit** — `tune: on-device calibration + smoothing + lead time`

---

### Task 4.3: Frame-pacing verification

- [ ] **Step 1 (user/device):** while playing + turning, capture `adb shell dumpsys gfxinfo com.panorama.app` (or Perfetto); confirm no sustained janky frames.
- [ ] **Step 2:** If jank: check (a) no allocation in `onDrawFrame`, (b) VR fill cost (drop sphere stacks/slices if needed), (c) Choreographer driver re-posts exactly once/frame.
- [ ] **Step 3:** Record results. **Branch complete** → use superpowers:finishing-a-development-branch.

---

## Self-review notes (for the implementer)

- **Calibration leakage is the #1 risk.** If video looks wrong on-device, the fix is ALWAYS in
  `AxisConvention` (Site A, Task 1.5) or `RemapConfig` (Site B, Task 2.1) or the runtime stMatrix
  flip owner — never a new sign in the renderer/mesh. The sign-free-builder invariant test
  (Task 1.5) + cross-path trip-wire (Task 1.9a) + golden-image gate (Task 4.1) enforce this.
- **`GazeState` already carries `angularAxis: Float3`** (Task 1.1, default +Y), produced by
  `OrientationSmoothing` (Task 1.4) and consumed by `GazePredictor` (Task 1.7). No mid-stream field
  surgery — the data class is complete from Task 1.1.
- **`kotlin-math` API names** (`slerp`, `dot`, `length`, `normalize`, `inverse`,
  `Quaternion.fromAxisAngle`, `q * v`) and the **Euler↔quaternion helpers** (`quatFromYawPitch` /
  `yawPitchOf`, defined once in core) MUST be confirmed against the resolved 1.8.0 jar **first**
  (Task 1.2); the boundary test is the contract, and the fallback (compose `fromAxisAngle`) removes
  any dependence on an uncertain library Euler API.
- **`viewMatrix(gaze, conv, out: Mat4)` is no-alloc** (writes into a reused buffer) — matches spec
  §5.1 / Task 2.3 "no per-frame allocation". Do not switch it to a returning variant.
- **Phase 1 is the gate.** Do not start Phase 2 until `:core:test :core:koverVerify` is green at ≥90%.
- **Wrapper jar** (Task 0.1 Step 3) and **device** (Phase 4) are the only user-in-the-loop steps.
