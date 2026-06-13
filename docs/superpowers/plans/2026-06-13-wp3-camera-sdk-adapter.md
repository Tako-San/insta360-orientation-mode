# WP3: CameraSDKAdapter + CaptureViewModel split — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`).

**Goal:** Isolate every Insta360 SDK touchpoint behind a `CameraSDKAdapter` interface (hiding SDK
types behind domain wrappers as far as practical) and split the 649-line god-object
`CaptureViewModel` into focused controllers, with behavior preserved and verified on a real
360 camera over Wi-Fi.

**Architecture:** Ports & Adapters. `CameraSDKAdapter` (interface, `:app`) wraps
`InstaCameraManager` + the 5 SDK callback interfaces, exposing suspend functions returning
`Result`/Boolean and domain wrappers (`PreviewParams`, `StreamResolution`, `CaptureWindowCrop`,
`PlayerOffsets`) instead of raw SDK objects. `CaptureViewModel` becomes a thin coordinator over
three controllers — `CaptureConnectionController` (init/sensor/options/preview lifecycle),
`CaptureControlController` (record/photo/live start-stop + mode switch), `PreviewParamsController`
(stream-param change → player update events). Controllers depend only on the adapter and emit
domain events.

**Tech Stack:** Kotlin, Insta360 SDK, kotlinx-coroutines, MockK, JUnit4. Verified on a Wi-Fi
camera (online mode cannot be unit-tested end to end).

**Risk note:** This touches the live capture path. Behavior is ported VERBATIM from the current
`CaptureViewModel`. Each phase is verified on the camera before the next. Baseline (pre-refactor)
is confirmed working: Wi-Fi connect, preview, settings, VR, capture.

---

## File Structure

**`:app` `ui/capture/camera/` (new):**
- `CameraSDKAdapter.kt` — interface + domain wrappers (`PreviewParams`, `StreamResolution`,
  `CaptureWindowCrop`, `PlayerOffsets`, `CameraInitConfig`) + callback-event sealed types.
- `InstaCameraSDKAdapter.kt` — real impl wrapping `InstaCameraManager` + `supportConfig` +
  `connectivityManager` network binding; registers the 5 listeners and forwards as a callback/Flow.

**`:app` `ui/capture/` (new controllers):**
- `CaptureConnectionController.kt` — `initCapture()` flow, sensor check, options fetch, support
  config, open/close/reopen preview. Emits `InitCaptureEvent`, `CameraWiFiDisconnectEvent`.
- `CaptureControlController.kt` — `startCapture()`, record/photo/live start-stop, `switchCaptureMode`.
  Emits `SwitchCaptureModeEvent`, `CameraCaptureEvent`, `CameraLiveEvent`.
- `PreviewParamsController.kt` — `cameraPreviewStreamParamsChanged`, window-crop/offset/resolution
  diffing. Emits `RestartPlayerViewEvent`, `CameraPreviewStreamParamsChangedEvent`,
  `UpdatePlayerViewParamsEvent`.

**Modify:**
- `CaptureViewModel.kt` — slim coordinator: owns `cameraOfflineData`, `isSingleClickAction`,
  wires controllers, re-emits their events, forwards SDK callbacks to controllers.
- `CaptureActivity.kt` — consume domain wrappers from `UpdatePlayerViewParamsEvent` instead of raw
  SDK `WindowCropInfo`/`OffsetData` (apply mapping at the view boundary).

**Tests (`:app` src/test):**
- `CaptureConnectionControllerTest.kt`, `CaptureControlControllerTest.kt`,
  `PreviewParamsControllerTest.kt` — MockK `CameraSDKAdapter`, verify event sequences and dispatch.

---

## Phase A — CameraSDKAdapter interface + real impl (Task 1–2)

### Task 1: Define `CameraSDKAdapter` interface + domain wrappers

**Files:** Create `app/.../ui/capture/camera/CameraSDKAdapter.kt`

- [ ] **Step 1: Write the interface and wrappers**

Capture exactly the surface `CaptureViewModel` uses today. Domain wrappers replace raw SDK objects
in the interface signatures; `CaptureMode`/`CaptureSetting` stay (already domain enums).

```kotlin
package com.arashivision.sdk.demo.ui.capture.camera

import com.arashivision.sdkcamera.camera.model.CaptureMode
import com.arashivision.sdkcamera.camera.model.CaptureSetting

/** Domain wrapper for the player preview stream resolution (hides StreamResolution). */
data class StreamResolution(val width: Int, val height: Int, val fps: Int)

/** Domain wrapper for window-crop parameters (hides AssetInfo + WindowCropInfo fields). */
data class CaptureWindowCrop(
    val srcWidth: Int, val srcHeight: Int,
    val dstWidth: Int, val dstHeight: Int,
    val offsetX: Int, val offsetY: Int
)

/** Domain wrapper for player offsets (hides InstaCapturePlayerView.getPlayerOffsetData). */
data class PlayerOffsets(val offsetV1: String)

/** Snapshot of the current preview params needed to decide a player update. */
data class PreviewUpdateInputs(
    val windowCrop: CaptureWindowCrop,
    val playerOffset: PlayerOffsets,
    val stabOffset: String,
    val resolution: StreamResolution?
)

/**
 * Port over the Insta360 SDK for the capture screen. Hides InstaCameraManager, supportConfig,
 * the 5 SDK callback interfaces and the SDK rendering types behind suspend funcs + domain wrappers.
 */
interface CameraSDKAdapter {
    // --- lifecycle / connection ---
    val isWifiConnected: Boolean
    val supportsNewCaptureControlFlow: Boolean
    val supportCaptureModes: List<CaptureMode>

    fun setLockScreen(locked: Boolean)
    fun registerListeners(callbacks: CameraCallbacks)
    fun unregisterListeners()

    suspend fun ensurePanoramaSensor(): Boolean
    suspend fun fetchCameraOptions(): Boolean
    suspend fun initSupportConfig(): Boolean
    suspend fun openPreviewStream(): Boolean
    fun closePreviewStream()
    suspend fun reopenPreviewStream(): Boolean
    fun applyStreamEncode()

    // --- settings ---
    fun supportValueList(mode: CaptureMode, setting: CaptureSetting): List<Any>
    fun supportSettingsForMode(mode: CaptureMode): List<CaptureSetting>
    suspend fun commitSettings(mode: CaptureMode, values: Map<CaptureSetting, Any>): Boolean

    // --- capture control ---
    val isSdCardEnabled: Boolean
    fun isCameraWorking(mode: CaptureMode? = null): Boolean
    fun startRecord(mode: CaptureMode)
    fun stopRecord(mode: CaptureMode)
    fun takePhoto(mode: CaptureMode)
    fun startLive(rtmp: String, listener: LiveCallbacks)
    fun stopLive()

    // --- preview-param change inputs (hides AssetInfo/supportConfig) ---
    val isStreamH265: Boolean
    val isPreviewOpened: Boolean
    fun previewUpdateInputs(mode: CaptureMode, currentCrop: CaptureWindowCrop?,
                            currentStabOffset: String?, current: StreamResolution?): PreviewUpdateInputs?
    fun isPreviewFileTypeChanged(mode: CaptureMode, currentFileType: Int): Boolean
    fun isEncodeMismatch(): Boolean

    // --- timelapse readout (kept; pure math already in :lib TimelapseMath) ---
    fun timelapseIntervalNative(mode: CaptureMode): Int
    fun timelapseFps(mode: CaptureMode): Int
}

/** Capture/preview status callbacks, SDK-free. */
interface CameraCallbacks {
    fun onWifiDisconnected()
    fun onPreviewOpened()
    fun onPreviewError()
    fun onCaptureStarting()
    fun onCaptureWorking()
    fun onCaptureStopping()
    fun onCaptureFinish()
    fun onCaptureError(code: Int)
    fun onCaptureTime(ms: Long)
    fun onCaptureCount(count: Int)
    fun onPreviewStreamParamsChanged()
}

/** Live streaming callbacks, SDK-free. */
interface LiveCallbacks {
    fun onStarted()
    fun onFinished()
    fun onError(code: Int, desc: String?)
}
```

- [ ] **Step 2: Compile** — `:app:compileDebugKotlin` → BUILD SUCCESSFUL (interface only).
- [ ] **Step 3: Commit** — `feat(app): CameraSDKAdapter interface + domain wrappers (#6)`

### Task 2: `InstaCameraSDKAdapter` real implementation

**Files:** Create `app/.../ui/capture/camera/InstaCameraSDKAdapter.kt`

- [ ] **Step 1: Implement against InstaCameraManager**

Port the SDK calls VERBATIM from the current `CaptureViewModel` private methods into the adapter:
- `ensurePanoramaSensor` ← `checkCameraSensorMode` (currentSensorMode + switchPanoramaSensorMode).
- `fetchCameraOptions` ← same (isFetchingOptions flag stays inside adapter).
- `initSupportConfig` ← `initCameraSupportConfig` incl. the `connectivityManager.bindProcessToNetwork`
  bind/unbind around the HTTP call (network binding moves into the adapter).
- `openPreviewStream`/`reopenPreviewStream`/`closePreviewStream`/`applyStreamEncode` ← same.
- `commitSettings` ← `setOfflineCaptureSettingValueToCamera` (beginSettingOptions + loop +
  commitSettingOptions, both new/old flow branches).
- record/photo/live start-stop ← the `when(captureMode)` blocks verbatim.
- `previewUpdateInputs` ← the `getConvertAssetInfo`/`getStabConvertAssetInfo`/`getPlayerOffsetData`/
  `curFirstStreamResolution` logic, returning domain `PreviewUpdateInputs` (maps AssetInfo →
  `CaptureWindowCrop`, offset → `PlayerOffsets`, resolution → `StreamResolution`).
- `registerListeners` adapts the SDK `IPreviewStatusListener`/`ICaptureStatusListener` to
  `CameraCallbacks`; `startLive` adapts `ILiveStatusListener` to `LiveCallbacks`.

Reference the current `CaptureViewModel.kt` for exact bodies (this is a verbatim move). The adapter
holds `instaCameraManager`, `isFetchingOptions`, `isStreamOpened`, `openPreviewStreamListener`.

- [ ] **Step 2: Compile** — `:app:compileDebugKotlin` → BUILD SUCCESSFUL.
- [ ] **Step 3: Commit** — `feat(app): InstaCameraSDKAdapter wrapping InstaCameraManager (#6)`

---

## Phase B — Controllers (Task 3–5), each MockK-tested

### Task 3: `CaptureConnectionController` + test

**Files:** Create `app/.../ui/capture/CaptureConnectionController.kt`; Test
`app/src/test/.../CaptureConnectionControllerTest.kt`

- [ ] **Step 1: Write the failing test** (MockK adapter; verify the init event sequence)

```kotlin
// pseudocode shape — full bodies in implementation
// given adapter.ensurePanoramaSensor()=true, fetchCameraOptions()=true, initSupportConfig()=true,
//   openPreviewStream()=true
// when controller.initCapture() runs (TestScope)
// then events emitted in order: START, PROGRESS(CHECK_SENSOR), PROGRESS(FETCH_CAMERA_OPTIONS),
//   PROGRESS(INIT_SUPPORT_CONFIG), PROGRESS(OPEN_PREVIEW_STREAM), SUCCESS
// and a failing ensurePanoramaSensor() ⇒ FAILED(CHECK_SENSOR), no further calls
```

- [ ] **Step 2: Run, expect FAIL** (`:app:testDebugUnitTest --tests *CaptureConnectionControllerTest*`).
- [ ] **Step 3: Implement** — move `initCapture`, `checkCameraSensorMode`, `fetchCameraOptions`,
  `initCameraSupportConfig`, `openPreviewStream`, `reopenPreviewStream`, `closePreviewStream` from
  `CaptureViewModel` into the controller, calling the adapter instead of `instaCameraManager`. Emit
  the same `InitCaptureEvent` sequence (event emission via an injected `(BaseEvent) -> Unit`).
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** — `refactor(capture): extract CaptureConnectionController (#7) + tests`

### Task 4: `CaptureControlController` + test

**Files:** Create `app/.../ui/capture/CaptureControlController.kt`; Test
`CaptureControlControllerTest.kt`

- [ ] **Step 1: Write the failing test** — given mode RECORD_NORMAL and `isCameraWorking()=false`,
  `startCapture()` calls `adapter.startRecord(RECORD_NORMAL)`; SD disabled ⇒ emits SD_DISABLE; a
  photo mode + single-click ⇒ `adapter.takePhoto(...)`. Live mode toggles start/stop.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** — move `startCapture`, `startRecord`, `takePhotos`, `stopRecord`,
  `startLive`, `stopLive`, `switchCaptureMode` verbatim, via adapter; emit same events. `isSingleClickAction`
  comes from `cameraOfflineData` (passed in).
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** — `refactor(capture): extract CaptureControlController (#7) + tests`

### Task 5: `PreviewParamsController` + test

**Files:** Create `app/.../ui/capture/PreviewParamsController.kt`; Test
`PreviewParamsControllerTest.kt`

- [ ] **Step 1: Write the failing test** — given `adapter.isEncodeMismatch()=true` while opened ⇒
  `applyStreamEncode` + `RestartPlayerViewEvent`; given file-type changed ⇒ fetch + restart; given
  crop/offset/resolution change ⇒ `UpdatePlayerViewParamsEvent` with the domain wrappers.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement** — move `cameraPreviewStreamParamsChanged`, `shouldUpdateWindowCrop`,
  `createWindowCropInfo`, `isPreviewFileTypeChange`, `onCaptureFinishEnd`,
  `onCameraPreviewStreamParamsChanged` logic. The controller works on domain `PreviewUpdateInputs`
  from the adapter; the Activity maps the domain wrappers back to SDK view calls.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** — `refactor(capture): extract PreviewParamsController (#7) + tests`

---

## Phase C — Slim CaptureViewModel + Activity boundary (Task 6–7)

### Task 6: Rewire `CaptureViewModel` as a thin coordinator

**Files:** Modify `CaptureViewModel.kt`

- [ ] **Step 1:** Replace the body: construct `InstaCameraSDKAdapter`, the three controllers (pass
  `emitEvent` and `cameraOfflineData`), register adapter listeners that fan out to controllers.
  Keep public API identical: `getCaptureSettingSupportValueList`, `getCaptureParams`,
  `switchCaptureMode`, `startCapture`, `closePreviewStream`, `cameraPreviewStreamParamsChanged`,
  `cameraOfflineData`, `isSingleClickAction`. Each delegates to a controller. `onCleared` →
  `adapter.unregisterListeners()` + `setLockScreen(false)`.
- [ ] **Step 2: Compile** — `:app:compileDebugKotlin` → BUILD SUCCESSFUL.
- [ ] **Step 3: Commit** — `refactor(capture): CaptureViewModel as thin coordinator over controllers (#7)`

### Task 7: Move SDK rendering types out of events at the Activity boundary

**Files:** Modify `CaptureActivity.kt`, `CaptureEvent.kt`

- [ ] **Step 1:** Change `UpdatePlayerViewParamsEvent` to carry the domain wrappers
  (`CaptureWindowCrop?`, `PlayerOffsets?`, `stabOffset: String?`, `StreamResolution?`) instead of raw
  SDK `WindowCropInfo`/`OffsetData`. In `CaptureActivity`, map the domain wrappers to the SDK view
  calls (`binding.capturePlayerView.setOffset(...)`, window-crop) at the point of use — the only
  place SDK rendering types remain.
- [ ] **Step 2: Compile** — `:app:compileDebugKotlin` → BUILD SUCCESSFUL.
- [ ] **Step 3: Commit** — `refactor(capture): events carry domain wrappers, SDK types confined to Activity boundary (#6)`

---

## Phase D — Verification (Task 8)

### Task 8: On-device verification (gates WP3)

- [ ] **Step 1: Build + install**
```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/farid/android-sdk ./gradlew :app:assembleDebug --no-daemon
/home/farid/android-sdk/platform-tools/adb install -r app/build/outputs/apk/debug/insta_sdk_demo_debug_1.8.1_build_06.apk
```
- [ ] **Step 2: Manual check (user, Wi-Fi camera)** — confirm the SAME baseline still works:
  1. Connect by Wi-Fi → camera connects, options sync.
  2. Capture screen shows live preview.
  3. Switch capture mode works.
  4. Start/stop a recording; take a photo.
  5. VR split-screen on the camera stream still works.
  6. Gyro rotates the live view.
  7. No crash, no `setYaw not found`, no SDK error spam in logcat.

---

## Self-Review notes

- **Spec coverage:** `CameraSDKAdapter` interface (Task 1) + impl (Task 2) = #6; controllers
  (Tasks 3–5) + thin VM (Task 6) = #7; domain wrappers hide WindowCropInfo/AssetInfo/OffsetData/
  Resolution (Tasks 1,2,7); MockK controller tests (Tasks 3–5); device verification (Task 8).
- **Behavior preserved:** every SDK call and event emission is moved verbatim; the new/old
  capture-control-flow branches, the H264/H265 reopen bug workaround, and the network bind/unbind
  are kept inside the adapter.
- **Type consistency:** `CameraSDKAdapter` method names used identically in adapter impl and
  controllers; domain wrappers (`StreamResolution`, `CaptureWindowCrop`, `PlayerOffsets`,
  `PreviewUpdateInputs`) defined in Task 1, consumed in Tasks 2,5,7.
- **What is NOT hidden (honest):** `CaptureMode`/`CaptureSetting` stay in signatures (domain enums);
  `CaptureParamsBuilderV2` from `getCaptureParams()` and `InstaCapturePlayerView` passed to
  `cameraPreviewStreamParamsChanged` remain SDK types at the Activity/player boundary — fully hiding
  them would mean wrapping the player view itself, out of scope here.
- **Tests caveat:** controllers are unit-testable with a MockK adapter; the adapter impl and the
  online flow are device-verified only (Task 8) — same constraint as the rest of `:app`.
- **Kover:** controllers live in `ui.capture.*` (already in the `:app` Kover report); the gate
  currently targets only `GyroOrientationController`, so no gate change needed, but coverage is
  reported.
