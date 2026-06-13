# AGENTS.md

Read `CLAUDE.md` first — it documents the app architecture thoroughly. This file covers what it leaves out.

## Build quirks

- **JAVA_HOME** must be `C:\Program Files\Android\Android Studio\jbr` before any Gradle command
- **ABI filter**: `arm64-v8a` only (line 21 of `app/build.gradle.kts`)
- **MultiDex** enabled
- **Release builds**: `isMinifyEnabled = false` (ProGuard config exists but unused); signing creds hardcoded in build file (storePassword/alias/keyPassword all `insta360`)
- **APK output**: renamed to `insta_sdk_demo_${buildType}_${versionName}.apk`
- **Gradle 8.13**, AGP 8.12.3, Kotlin 2.0.21
- **NDK version**: `25.2.9519653`

## Modules

- **`:app`** — Android application (all UI, camera, player)
- **`:lib`** — pure JVM library (Java 11 + Kotlin, no Android dependencies)

## Testing

- **JUnit 4** only — no mock libraries (Mockito/MockK absent)
- Unit tests: `app/src/test/java/` — 2 real test classes (`PanoramaFovMathTest`, `EquirectangularProjectionTest`)
- Instrumented tests: `app/src/androidTest/java/` — only a placeholder
- Run: `./gradlew testDebugUnitTest`
- Single class: `./gradlew testDebugUnitTest --tests "*ClassName*"`

## Repository & credentials

Insta360 SDK (`sdkcamera`/`sdkmedia`) lives in Insta360's public Maven repo `https://androidsdk.insta360.com/repository/maven-public/` with guest basic auth `insta360guest` / `EXMSjSo8OeOrjU7d` (from the official SDK-demo V1.10.1; hardcoded as fallback in `settings.gradle.kts`, override via `instaNexusUser`/`instaNexusPassword`). The old `nexus.arashivision.com:9999` host is dead. SDK version `1.8.1_build_06` via the version catalog (`libs.versions.insta`).

**Media3 ExoPlayer** (`1.5.1`) is declared directly in `app/build.gradle.kts`, not via the version catalog.

## No code quality tooling

No detekt, ktlint, spotless, editorconfig, or typecheck configured. No CI pipeline.

## Manifest details

- **MainActivity** is the launcher, `singleInstance` launch mode
- **CaptureActivity** declared twice in the manifest (both `singleInstance`)
- **ConnectService** is a foreground service (type `connectedDevice`) for camera connection persistence
- **InstaApp** (Application class) initializes `InstaCameraSDK`, `InstaMediaSDK`, and `UsbMgr` on startup
