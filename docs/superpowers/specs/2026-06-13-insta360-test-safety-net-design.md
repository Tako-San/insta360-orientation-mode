# Spec: восстановить сборку → safety-net тесты → CI

Дата: 2026-06-13
Ветка: `refactoring` (основана на `upstream/offline-video`)
Статус: одобрено к реализации

## Цель

Зафиксировать наблюдаемое поведение чистой математической логики ориентации/проекций
юнит-тестами и автоматизировать их прогон в GitHub Actions, чтобы последующий глубокий
рефакторинг опирался на регрессионную сетку.

Это **не** e2e: камера, нативный плеер и рефлексивные `setYaw`/`setPitch` остаются вне
покрытия (их нельзя проверить без железа). Это явная граница, а не имитация моками —
имитация камеры/плеера дала бы тесты, проверяющие моки, а не реальное поведение.

## Контекст разведки

Ветка `offline-video` **не собирается из clone**. `.gitignore` исключает, и физически
отсутствуют (нет ни в git ни в одной ветке/истории, ни на диске, ни у автора):

- `settings.gradle.kts` — без него Gradle не знает про модуль `:app`;
- `gradle/libs.versions.toml` — version catalog, на который ссылается `build.gradle.kts`
  (`libs.plugins.*`, `libs.insta.camera`, `libs.versions.insta` и ~25 алиасов);
- `gradle/wrapper/gradle-wrapper.{jar,properties}` — без них `./gradlew` не работает;
- `gradle.properties`, `gradlew.bat`;
- `/libs` (в `app/.gitignore`) — локальный `glide_transformations.jar` отсутствует.

Проект собирался только на машине автора (`samfrompizza`), где эти заигноренные файлы
лежат локально. Для любого clone и для CI это блокер. Insta360 SDK тянется из **публичного
Maven Insta360**, значит CI сможет собрать APK без секретов.

Артефакты SDK (по импортам): `com.arashivision.sdkcamera.*`, `com.arashivision.insta360.basecamera.*`,
`com.arashivision.insta360.basemedia.*` → `sdkcamera` + `sdkmedia`, версия `1.8.1_build_06`.

## Фаза 0 — восстановить build-инфраструктуру (предусловие)

Воссоздать и **разигнорить** (убрать соответствующие строки из `.gitignore` / `app/.gitignore`):

- `settings.gradle.kts` — `pluginManagement` + `dependencyResolutionManagement` с
  репозиториями `google()`, `mavenCentral()` и публичным Maven Insta360 (точный URL
  уточнить из доков Insta360 Open Platform; верификация — сборкой).
- `gradle/libs.versions.toml` — все алиасы из `app/build.gradle.kts`:
  плагины (AGP 8.12.3 `android.application`, Kotlin 2.0.21 `kotlin.android`, `jetbrains.kotlin.jvm`);
  androidx core-ktx/appcompat/constraintlayout/recyclerview/preference(+ktx)/material/
  viewbinding/swiperefreshlayout/lifecycle-viewmodel-ktx/lifecycle-runtime-ktx/
  coroutines-core/coroutines-android; xx-permissions, flowlayout, lottie, glide(+compiler),
  immersionbar, xlog, filepicker; androidx-junit, espresso-core;
  `insta-camera`/`insta-media`; `versions.insta = "1.8.1_build_06"`.
- `gradle/wrapper/gradle-wrapper.{jar,properties}` + исполняемый `gradlew` — Gradle,
  совместимый с AGP 8.12.3 (Gradle 8.9+).
- `gradle.properties` — `android.useAndroidX=true`, `kotlin.code.style=official`.
- `local.properties` остаётся заигноренным; в CI `sdk.dir` обеспечивает `setup-android`.

Решения по двум препятствиям (оба согласованы):

- **`glide_transformations.jar`** → заменить Maven-зависимостью `jp.wasabeef:glide-transformations`
  (чище для CI, чем коммитить бинарь). Удалить `implementation(files("libs/glide_transformations.jar"))`.
- **release `signingConfig`** с путём `G:\camerasdk\...` — не трогаем; CI собирает только
  `assembleDebug`. Помечается как известный технический долг.

Критерий: `./gradlew assembleDebug` и `./gradlew testDebugUnitTest` проходят локально на
чистом дереве.

## Фаза 1 — единственный безопасный extract

В `GyroOrientationController` вынести инлайн-математику из `onSensorChanged`
(строки ~203–213: скейлинг по `sensivity`, инверсия осей, клампинг) в чистую функцию:

```kotlin
data class TargetOrientation(val yawDeg: Float, val pitchDeg: Float)

fun computeTargetOrientation(
    eulerYawDeg: Float, eulerPitchDeg: Float,
    sensivity: Float, invertYaw: Boolean, invertPitch: Boolean
): TargetOrientation
```

Тело — дословно текущая арифметика; `yawFactor=0.04f`, `pitchFactor=0.02f`,
`maxYaw=360f`, `maxPitch=270f` переезжают внутрь функции. `onSensorChanged` вызывает её.
Поведение байт-в-байт прежнее.

НЕ двигаем: `Quaternion` остаётся вложенным в `GyroOrientationController` (используется
4 файлами — `LocalSphericalPlayerActivity`, `EquirectangularProjection`, `PanoramaFovMath`,
сам контроллер); рефлексия `setYaw`/`setPitch` не трогается; `OrientationSink` не вводится.

## Фаза 2 — характеризационные тесты (safety net)

JVM-юнит-тесты (`app/src/test`, JUnit4), фиксирующие поведение **как есть сейчас**
(включая текущие конвенции знаков), а не «как правильно»:

- `GyroOrientationController.Quaternion`: `normalize`/`conjugate`/`multiply`/`dot`;
  `fromRotationMatrix` (все 4 ветки: trace>0 и три диагональных);
  `slerp` (t=0, t=1, dot<0 короткий путь);
  `toEulerAngles` (нормальные углы, gimbal-lock pitch=±90°, unwrap через previous*).
- `computeTargetOrientation` из Фазы 1: знаки инверсии, скейл, клампинг на границах.
- Догрузка к существующим `PanoramaFovMathTest`/`EquirectangularProjectionTest`, если
  есть непокрытые ветки (`fromNormalized`, `fromYawPitch`, `resolveTargetQuat`).

Проверка, что тесты не пустышки: внести заведомую ошибку в формулу и убедиться, что падают.

## Фаза 3 — CI (GitHub Actions)

`.github/workflows/ci.yml`: триггеры `push` + `pull_request`;
`actions/checkout`; `actions/setup-java` (temurin 17); `android-actions/setup-android`;
`gradle/actions/setup-gradle`; шаги — `./gradlew testDebugUnitTest`, `./gradlew lint`,
`./gradlew assembleDebug`. APK/lint-репорт как artifacts (опционально).

## Границы (что НЕ делаем)

Не двигаем `Quaternion`; не вводим `OrientationSink`; не трогаем рефлексию, Vr-копирование
кадров, ViewModel/lifecycle/UI; не пишем Robolectric/instrumented; не правим release-keystore
(только помечаем как долг).

## Критерий готовности

- `./gradlew testDebugUnitTest`, `lint`, `assembleDebug` зелёные локально;
- новые тесты доказанно ловят регрессию (заведомая ошибка → падение);
- workflow валиден;
- всё в ветке `refactoring`, без push.
