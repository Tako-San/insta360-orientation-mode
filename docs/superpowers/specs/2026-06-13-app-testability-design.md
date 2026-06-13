# :app Testability & Test Strategy — Design

**Date:** 2026-06-13
**Goal:** Make the `:app` module developable and verifiable without a physical phone, by isolating every Android/SDK edge behind a narrow Kotlin port and moving pure logic into testable units. Raise meaningful coverage of `:app` core logic and enforce a coverage gate on new code.

**Status:** Approved approach (aggressive refactor; interfaces + MockK with targeted Robolectric; all four work packages; gate-on-new-code).

---

## 1. Principles

- **Ports & Adapters.** Each nondeterministic / hardware / SDK edge (`SensorManager`, media3 `ExoPlayer`/`SphericalGLSurfaceView`, `InstaCameraManager`) is hidden behind a narrow interface (a "port"). Production wires the real adapter; tests wire a MockK fake or hand fake.
- **Push pure logic down.** Math and state machines (gyro calibration/remap/gaze, adaptive smoothing, detection-by-time selection) live in `:lib` (pure-JVM) or in pure classes, tested with plain JVM tests — fast, deterministic, no runtime.
- **Robolectric only where unavoidable.** The native Android math functions `SensorManager.getRotationMatrixFromVector`, `remapCoordinateSystem`, `getOrientation` cannot be meaningfully mocked. They are isolated behind a single tiny port whose one real implementation is covered by a focused Robolectric test. Everything else is plain JVM.
- **No behavior change.** Refactors are structure-only; the phone-verified gyro behavior (probe A) must be preserved. Final on-device check by the user gates the gyro/player packages.

## 2. Ports (interfaces) and placement

| Port | Hides | Defined in | Prod adapter | Test double |
|---|---|---|---|---|
| `RotationMatrixMath` | native `getRotationMatrixFromVector`, `remapCoordinateSystem`, `getOrientation` | `:app` | `AndroidRotationMatrixMath` | Robolectric test on the real impl; pure tests inject precomputed matrices |
| `SensorSource` | `SensorManager`, listener registration, `TYPE_ROTATION_VECTOR`, delay | `:app` | `AndroidSensorSource` | MockK / hand fake feeding `FloatArray` values |
| `OrientationApplier` | media3 sphere rotation (reflection `onScrollChange`) | `:app` | `Media3SphericalOrientationSink` (already exists; implement the interface) | fake recording applied yaw/pitch |
| `CameraSDKAdapter` | `InstaCameraManager` singleton + SDK callbacks/settings | `:app` | `InstaCameraSDKAdapter` | MockK, emulate callbacks/state |
| `OrientationProcessor` (pure) | gyro math on already-extracted inputs: remap-axis selection, quaternion, calibrate, gaze yaw/pitch (native matrix/orientation extraction stays in `RotationMatrixMath`) | **`:lib`** | — | direct JVM tests |

## 3. Work packages

### WP1 — Gyro: pure `OrientationProcessor` in `:lib` + `SensorSource` port (#2 core)

Split `GyroOrientationController` into:
- **`OrientationProcessor`** (`:lib`, pure): input `(rotationMatrix3x3: FloatArray, displayRotation: Int, now: Long)`; owns calibration state, SLERP smoothing, gaze yaw/pitch computation, rate-limit decision. Output: `GazeAngles(yawDeg, pitchDeg)` + raw euler. All current logic (remap-axis selection per rotation, `getGazeYawDeg/PitchDeg`, calibration offsets, the probe-A fix) moves here verbatim. **Exception:** the three native `SensorManager` calls do not run in `:lib`; instead the processor receives the already-built rotation matrix and already-extracted orientation from `RotationMatrixMath`. So the processor takes matrices/orientation as input, keeping it 100% pure.
- **`RotationMatrixMath`** port (`:app`): wraps the three native calls. One real impl `AndroidRotationMatrixMath`. Robolectric test verifies it against known sensor vectors.
- **`SensorSource`** port (`:app`): registers/unregisters listener, forwards `FloatArray` values + timestamp.
- **`GyroOrientationController`** becomes thin glue: `SensorSource` → `RotationMatrixMath` → `OrientationProcessor` → `applyOrientation` callback. Lifecycle only.

Tests: `OrientationProcessorTest` (`:lib`, JVM) — calibration recenters to zero, remap per ROTATION_0/90/180/270, gaze follows input, rate-limit gating, probe-A sign/axis correctness, wrap ±180°. `AndroidRotationMatrixMathTest` (`:app`, Robolectric) — one focused test.

### WP2 — Player orientation: `OrientationApplier` port (#2)

- Extract interface `OrientationApplier { fun apply(yawDeg: Float, pitchDeg: Float) }`.
- `Media3SphericalOrientationSink` implements it (reflection impl unchanged).
- `LocalSphericalPlayerActivity` depends on the interface; `tryApplyOrientation` orchestration (read gaze → smooth via `OrientationSmoothing` → update `currentGazeDirection` → apply) extracted into a pure `PlayerOrientationCoordinator` taking `OrientationApplier` + `OrientationSmoothing` + gaze provider.

Tests: `PlayerOrientationCoordinatorTest` (JVM, MockK fake applier) — smoothing applied, gaze updated, applier receives inverted-sign angles, disabled-state short-circuit.

### WP3 — Camera SDK: `CameraSDKAdapter` + split `CaptureViewModel` (#6, #7)

- Define `CameraSDKAdapter` interface covering what `CaptureViewModel` actually uses: connection state, preview stream open/close (suspend), capture start/stop, settings get/set, status callbacks as a `Flow`/listener registration. Real impl `InstaCameraSDKAdapter` wraps `InstaCameraManager.getInstance()` and the `suspendCancellableCoroutine` callback bridging.
- Split `CaptureViewModel` (649 lines) by responsibility into focused units (e.g. `ConnectionController`, `CaptureController`, `SettingsController`) that depend on `CameraSDKAdapter`, coordinated by a slimmed `CaptureViewModel`. Pure state/transform logic separated from SDK calls.

Tests: controller tests (JVM, MockK adapter + `kotlinx-coroutines-test`) — preview open success/failure/camera-error paths, capture state transitions, settings dispatch. ViewModel state via `turbine` if it exposes `StateFlow`.

### WP4 — Detections via Flow (#8)

- Replace `uiHandler.postDelayed` 200ms polling in `LocalSphericalPlayerActivity` with a cold `Flow` that, given a position provider, emits the current `VideoDetectionFrame?` at the cadence. The mapping (position → frame → arrow visibility/angle) is pure and uses `:lib` `VideoDetectionTimeline` + `PanoramaFovMath`.
- A pure `DetectionArrowState(frame, gaze, fov) → ArrowState` function (likely in `:lib`) computes arrow angle / hidden.

Tests: Flow emission with `coroutines-test` virtual time + `turbine`; `DetectionArrowState` pure tests (already partly covered by `PanoramaFovMath`).

## 4. Test infrastructure (`:app`)

Add to version catalog and `:app` `testImplementation`:
- `mockk`
- `robolectric`
- `kotlinx-coroutines-test`
- `turbine` (Flow assertions)
- `junit` (already)

`:app/build.gradle.kts`: `testOptions.unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true }` for Robolectric. Apply Kover to `:app`.

## 5. Coverage gate (gate-on-new-code)

- Kover `verify` rule scoped to the new/extracted packages: `com.arashivision.orientation.*` (the `:lib` pure logic) and the new `:app` pure/coordinator packages — **minimum 80% line coverage**; build fails below threshold.
- Legacy Android-coupled code (Activities, VrManager pixel-copy, views) is **excluded** from the gate (can't be unit-tested without device/GL) but still reported.
- CI: `:lib:koverXmlReport` already wired; add `:app:testDebugUnitTest` + `:app` Kover. `:app` unit tests run in CI (they no longer need SDK/Nexus because SDK is behind the mocked port — verify the test source set does not transitively require the Insta360 artifact at test compile; if it does, keep adapter interfaces SDK-free and only the real adapter references the SDK).

## 6. Risks & mitigations

- **SDK type leakage into tests.** If `CameraSDKAdapter`'s interface exposes SDK enums, test compile pulls the SDK. Mitigation: adapter interface speaks in app-domain types; SDK types stay inside the real adapter. May need small mapping layer.
- **Behavior regression in gyro/player.** Mitigation: `OrientationProcessor` moves logic verbatim; on-device user check gates WP1/WP2 before merge.
- **Robolectric flakiness.** Mitigation: only ONE tiny Robolectric test (`RotationMatrixMath`); everything else plain JVM.
- **Scope creep in WP3.** `CaptureViewModel` split is the largest. If it balloons, WP3 can ship in two steps: (a) adapter + tests against current VM, (b) the split.

## 7. Order & success criteria

Order: WP1 → WP2 → WP4 → WP3 (gyro first = highest value + already understood; WP3 largest, last).

Done when: `:lib` + `:app` pure/coordinator packages ≥80% lines (Kover gate green in CI); `:app` unit tests run in CI without SDK/Nexus; gyro & player behavior re-verified on device by the user; no behavior change observed.
