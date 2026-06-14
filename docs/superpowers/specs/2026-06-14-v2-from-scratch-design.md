# 360 Panorama Player v2 — From-Scratch Design

**Status:** Design (approved pending user review)
**Branch:** `rewrite-v2` (off `master`, not the `refactoring` fork)
**Date:** 2026-06-14
**Supersedes:** the Insta360-SDK-demo fork (`insta360-orientation-mode` on `refactoring`)

---

## 1. Goal

A from-scratch Android app that plays **local equirectangular 360 videos** smoothly, lets the
user look around by **moving the phone (gyroscope)**, shows a **direction arrow** to the nearest
detected object outside the field of view, and offers a **VR split-screen** mode — built so it is
**testable, modifiable, and jank-free**.

This replaces a fork of the Insta360 Camera SDK demo. The fork's pain points (god-object
ViewModel, reflection into a closed player, SDK types leaking everywhere, VR via PixelCopy
frame-copy, scattered axis-calibration signs, gyro feeding `requestRender` with no rate-match)
are explicitly designed out.

### Scope (locked with the user)

| Decision | Choice |
|---|---|
| Online live camera stream | **OUT** of v2. `CameraSource` port deferred (no second consumer yet). |
| Real Insta360 SDK | **OUT** of v2, isolated to a future `:adapter-insta` module. The seam is enough. |
| Video format | **Equirectangular** now; dual-fisheye `.insv` later behind a projection seam. |
| UI stack | **Jetpack Compose** (GL via `AndroidView`). |
| Pure core | **Rewritten from scratch** (domain logic + axis calibration from first principles); quaternion/matrix *algebra* delegated to `kotlin-math`, not hand-rolled (see §4.1). |
| Priorities | **Equal**: smoothness AND testability AND clean architecture. No skew. |
| VR | **Two `glViewport` passes in one GL context** (no PixelCopy, no second player). |
| Detections | `DetectionSource` port: JSON sidecar now, on-device realtime later. |
| Predictive rotation | **YES, from the start** (motion-to-photon budget + 1-frame-ahead gaze). |
| MVI | **No** formal reducer. Thin ViewModel + plain methods + StateFlow. |
| DI | **Hilt.** |
| Verification | User builds APK and confirms on a real phone; CI is device-free. |
| Placement | New project subfolder in this repo, on branch `rewrite-v2`. |

This design is the synthesis of a 3-architect panel (smoothness-first / testability-first /
pragmatic) judged and then adversarially critiqued. The pragmatic 3-module base won; the best
ideas from the other two were grafted in; **six concrete fixes from the adversarial review are
baked into this spec** (see §9 — they are the difference between "believes it solved v1's bugs"
and "actually solved them").

---

## 2. Module structure

Three Gradle modules. Dependencies point **strictly one way: `:app` → `:android` → `:core`**.
`:core` has zero Android on its classpath.

```
panorama-v2/                 (new Gradle project root, settings.gradle.kts)
├── core/                    pure JVM  (java-library + org.jetbrains.kotlin.jvm)  — NO android, NO SDK
├── android/                 com.android.library                      — GL, sensors, media3, adapters
└── app/                     com.android.application                  — Compose, Hilt, screens
```

- **`:core`** — all math, calibration signs, detection parsing/timeline, stereo + predictive
  gaze, the two real port *interfaces*. ~100% unit-tested. **Blocking CI gate.**
- **`:android`** — the only module touching Android/GL/media3/SensorManager. GLES2 renderer,
  `SensorReader`, ExoPlayer-backed video → `SurfaceTexture`, the **content-URI byte loader** that
  feeds `:core`'s pure sidecar parser (see §4.6), `RemapConfig` (native display-rotation remap),
  Hilt adapters. **No Compose. No Insta360 SDK.**
- **`:app`** — Compose screens, thin ViewModels, navigation, Hilt composition root. No domain
  logic, no signs.

**Why 3 and not 5–7:** a per-feature/per-platform module graph adds module boundaries with a
single consumer and no independent build need — exactly the speculative abstraction the project's
rules forbid for a 2-screen app. The only future module that earns its keep is `:adapter-insta`
(SDK isolation), and it is *not built now*; the `CameraSource` interface marks the seam.

---

## 3. Ports (exactly two)

Interfaces live in `:core`, expressed in core/primitive types. We add an interface **only where a
second implementation is genuinely coming.** Everything else is a concrete class (a `SensorReader`
or a GL renderer with one impl forever does not get an interface just to be mockable — the math it
feeds is already pure and tested in `:core`).

```kotlin
// :core — second impl genuinely coming: on-device realtime detector.
interface DetectionSource {
    fun detectionsAt(positionMs: Long): List<Detection>   // nearest-frame lookup; cheap, pure
    val available: Boolean
}

// :core — second impl genuinely coming: dual-fisheye .insv.
// NOTE: NO shaderSource() here in v2 (see §9 fix 4) — the GLSL string would leak GL-dialect
// detail into pure :core with zero current payoff. The shader stays in the :android renderer
// until the second projection actually arrives; this port is added to that future PR.
interface ProjectionModel {
    fun buildMesh(stacks: Int, slices: Int): MeshData          // pure geometry, testable
    fun directionToTexUv(dir: UnitVector3): Vec2               // for arrow/detection math
}
```

**Deferred (NOT in v2):**
- `CameraSource` — only impl would be local-video, and the GL thread pulls frames via
  `updateTexImage()`, not via a flow. No second consumer to justify it. Added in the PR that
  brings the Insta360 adapter.
- `ProjectionModel.shaderSource()` — added in the fisheye PR.

**Concrete (deliberately no interface):** `SensorReader`, `PanoramaRenderer`, the ExoPlayer
wrapper. One impl forever; inventing a port is the over-engineering we reject.

---

## 4. Pure core (`:core`, rewritten from scratch)

Package `com.panorama.core`. All pure JVM, no `android.*`.

### 4.1 Math — use `kotlin-math` (do NOT hand-roll quaternions/matrices)
Quaternion/vector/matrix algebra comes from **`dev.romainguy:kotlin-math`** (Romain Guy / Google,
Apache-2.0, Kotlin-idiomatic, KMP, alive in 2026). It provides `Quaternion` (slerp,
`fromEulerAngles`, `toMatrix`, Hamilton convention), `Float3`/`Float2`, `Mat4`. This kills a whole
class of subtle axis-order / Hamilton-convention bugs that hand-rolled quaternions invite, and
keeps `:core` pure-JVM (the library is pure-JVM too).
- `:core` does NOT define its own `Quaternion`/`Mat4`. It uses the library types directly.
- `:core` still owns the small wrappers that the library does NOT give us: `MeshData(positions,
  texCoords, indices)` (GL buffer layout) and any domain value types (`GazeState`, etc.).
- **Calibration stays ours (see §6).** The library gives the *operations*; it cannot pick our
  *convention* (which way yaw is signed, V-flip). `ViewCalibration`/`AxisConvention` operate on top
  of `kotlin-math` types — the sign decisions are still confined to one place and property-pinned.
- One library-boundary test: assert the library's quaternion `slerp`/`fromEulerAngles` behave as we
  expect (endpoints, normalization, axis order) so a future library bump can't silently shift the
  convention under us.

### 4.2 Orientation pipeline
- `OrientationProcessor` — calibration (store reference quat; relative = current · ref⁻¹), SLERP.
- `OrientationSmoothing` — adaptive low-pass, alpha = f(angular velocity): high alpha when moving
  fast (responsive), low when still (kills jitter). **Parameterized by real `dt`** from sensor
  `timestampNs`, not per-tick (see §9 fix 3 — this is a genuine rewrite, not a port of v1).
- `GazeState(quaternion, yawDeg, pitchDeg, angularVelocityDegPerSec)` — immutable snapshot. The
  `angularVelocityDegPerSec` is the **smoothed** velocity (computed in `OrientationSmoothing` from
  the smoothed quaternion delta over real `dt`), so `GazePredictor` extrapolates clean motion, not
  raw jitter.

### 4.3 Predictive rotation (NEW vs v1)
- `GazePredictor.predict(gaze: GazeState, leadTimeMs: Float): GazeState` — extrapolates the gaze
  quaternion forward by `leadTimeMs` along the current angular velocity (slerp past the current
  orientation). The GL thread renders the **predicted** gaze for the frame's scan-out time, so the
  panorama is locked to the head on fast turns instead of swimming behind it. `leadTimeMs` comes
  from the motion-to-photon budget (§5.4). Pure, property-tested (zero velocity ⇒ identity;
  constant velocity ⇒ linear-in-angle lead).

### 4.4 Projection
- `EquirectProjection : ProjectionModel` — mesh gen (inward UV-sphere) + `directionToTexUv`.
  Built in **one fixed canonical basis with NO sign opinions** (see §6).

### 4.5 FOV / arrow
- `PanoramaFov` — `isInsideFov(gaze, target, hFov, vFov)` and screen-angle of an out-of-FOV
  target, via the **quaternion method only** (robust, frame-independent — v1's Euler-subtraction
  variant is dropped to avoid two paths).
- `ArrowResolver` — nearest out-of-FOV detection → `ArrowState(visible, angleRad)`.

### 4.6 Detection
- `Detection(centerNorm, bboxNorm?, label?)`, `SidecarParser` (bytes → model, kotlinx.serialization),
  `DetectionTimeline` (binary-search nearest frame), `SidecarDetectionSource : DetectionSource`
  (pure — wraps a `DetectionTimeline`, just nearest-frame lookup; lives in `:core`).
- **Boundary:** `:core` owns parsing + lookup. Reading the bytes from a `content://` URI is an
  Android concern — a thin `SidecarLoader` in `:android` reads bytes and calls `SidecarParser`,
  then constructs the `:core` `SidecarDetectionSource`. The Robolectric test in §8 Tier 2 targets
  `SidecarLoader` (the IO), not `SidecarDetectionSource` (which is pure, Tier 1).

### 4.7 VR
- `StereoEyeLayout` (eye scale, spacing) + `stereoGaze(base, ipdYawDeg): Pair<GazeState,GazeState>`
  (pure eye-yaw split for the two viewports).

### 4.8 Calibration — see §6 (the load-bearing section).

---

## 5. Rendering & threading (smoothness)

### 5.1 Three threads, lock-free handoff
- **Sensor thread** (dedicated `HandlerThread`, `TYPE_ROTATION_VECTOR` @ `SENSOR_DELAY_FASTEST`
  ~200 Hz): each event → `RemapConfig` native remap → `Quaternion` → `OrientationProcessor` →
  `OrientationSmoothing` (real `dt`) → writes ONE `GazeState` into an
  `AtomicReference<GazeState>` (single-writer/single-reader, lock-free). Does **no** GL work and
  **never** calls `requestRender`. (Kills v1's "orientation on main thread, no throttle" bug.)
- **GL thread** (`GLSurfaceView`): the only thread touching GL. Per frame (§5.2) it reads the
  latest `GazeState`, **predicts** it forward, builds the view matrix, draws.
- **Main/UI thread**: Compose only. Slow UI state (playback position, arrow, VR flag). Never does
  per-frame matrix math.

### 5.2 Render loop — `RENDERMODE_WHEN_DIRTY` + a self-reposting Choreographer callback
`RENDERMODE_WHEN_DIRTY`: the GL thread draws only when `requestRender()` is called. The driver is a
**self-reposting `Choreographer.FrameCallback`** on the UI thread, with an explicit lifecycle
(this is the mechanism the "render every VSYNC while playing" intent requires — a one-shot callback
would tick once and stop):

- `play()` → `Choreographer.postFrameCallback(driver)`. The driver's `doFrame()` does
  `glView.requestRender()` **and re-posts itself** (`postFrameCallback(this)`) → one render per
  VSYNC for as long as playback runs.
- `pause()` → `removeFrameCallback(driver)` → no further VSYNC renders. This is the **only** idle
  state, and the **only** place the battery win materializes (§9 fix 2 — stated plainly).
- `seek()` **while paused** → a single one-shot `requestRender()` so the new frame is shown without
  starting the playing loop.

**Resolved honestly (see §9 fix 2):** while playing we render **every** VSYNC, coalescing the
freshest gaze + the newest decoded frame. A frame with no new decoded image
(`pendingFrame == false`) is still a **valid render** — it redraws the current video frame with an
updated (predicted) gaze. That is the point: at 30 fps video on a 90/120 Hz display the head-look
stays smooth while the same decoded frame is reused. "No new frame" never means "skip the render."

`SurfaceTexture.OnFrameAvailableListener` **only sets a `pendingFrame` volatile flag** — it does
NOT call `requestRender` (the Choreographer driver owns cadence). `updateTexImage()` +
`getTransformMatrix()` run **on the GL thread inside `onDrawFrame`** (the only place with the
context current).

**Per frame in `onDrawFrame` (no allocation, no locks):**
1. if `pendingFrame`: `surfaceTexture.updateTexImage()` + `getTransformMatrix(stMatrix)`.
2. `gaze = gazeRef.get()` (one lock-free read).
3. `predicted = GazePredictor.predict(gaze, leadTimeMs)`.
4. `view = ViewCalibration.viewMatrix(predicted)`; `mvp = proj · view`.
5. draw sphere (OES-external sampler), applying `stMatrix` for the texture.

### 5.3 VR dual-viewport (one context, no PixelCopy)
One `GLSurfaceView`, one EGL context, one OES texture, one mesh. In VR mode `onDrawFrame` does two
passes: `glViewport(left half)` with `stereoGaze.left` view matrix, then `glViewport(right half)`
with `stereoGaze.right`. Two draw calls, one decoded frame, **zero GPU→CPU readback**. Mono mode =
one full-width viewport, one draw. (Replaces v1's duplicate-player + PixelCopy ~30 fps loop.)

### 5.4 Motion-to-photon budget + predictive lead
Target: keep perceived gaze locked to the head. `leadTimeMs` ≈ (sensor latency + one frame at the
display refresh + compositor latency). **Start value: ~30 ms** (a sane default for one VSYNC at
~90 Hz plus pipeline latency); tunable range **~10–60 ms**, tuned on-device with the frame-pacing
tools (§8 Tier 3). `GazePredictor` (§4.3) applies it. This is the #1 factor for "doesn't swim /
doesn't lurch" on fast turns and the main VR-nausea mitigation — budgeted from the start, not
bolted on. Over-prediction (too-large lead) overshoots and jitters; the start value errs small.

### 5.5 Arrow overlay (Compose Canvas, off the GL hot path)
The arrow is a **Compose `Canvas`** layered above the `AndroidView(GLSurfaceView)` in a `Box`
(normal Compose Z-order, no surface compositing tricks). It is NOT in GL (keeps the GL program
trivial) and NOT a CPU bitmap copy (v1's path). It reads the **same** `AtomicReference<GazeState>`
in a `withFrameNanos` loop, so arrow and sphere track gaze at frame cadence from one source.
Detection lookup runs on `Dispatchers.Default` keyed by playback position; only the resulting
`ArrowState` crosses to UI. In VR, two arrows at 25 % / 75 % width using the left/right stereo gaze.

**Deliberate choice:** the arrow reads the **unpredicted** smoothed gaze, while the sphere renders
the **predicted** gaze (§5.4). On fast turns this is a tiny heading difference, but the arrow is an
informational edge cue, not a registered overlay — using unpredicted gaze keeps it stable and
avoids amplifying prediction error into a twitchy arrow. Documented so it is not mistaken for a bug.

**Known limitation (honest, see §9 fix in critique):** SurfaceView GL content and the Compose
overlay are composited by SurfaceFlinger from separate buffers, so under load they can be 1–2
frames apart on very fast turns. Acceptable for an informational edge arrow; revisited only if it
proves visible on-device.

---

## 6. Calibration — TWO tunable sites + one runtime flip-owner + a device gate (honest)

This is the load-bearing section. v1's worst pain was scattered axis/sign decisions
(yaw negated in *two* files, V-flip in the mesh, invert flags in the processor, the native remap
table) and two coexisting coordinate conventions. **The adversarial review proved that "all signs
in ONE file" is not literally achievable** (the native remap depends on `Surface.ROTATION_*`
constants and cannot live in pure `:core`; the runtime `SurfaceTexture` transform can itself carry
a V-flip). So this spec states the honest version: **two tunable sites + one runtime-owned texture flip + a
device-tier gate**, with *structural* (not disciplinary) confinement. (Naming note: the `:core`
calibration types are `ViewCalibration` (the object) + `AxisConvention` (the data). Where the text
says "`:core Calibration`" it means this pair — there is no separate type named `Calibration`.)

### 6.1 Site A — `:core` `Calibration` (math signs)
```kotlin
data class AxisConvention(
    val yawSign: Float,        // +1 / -1
    val pitchSign: Float,
    val glForwardIsMinusZ: Boolean,
)
object ViewCalibration {       // the ONLY core type that turns gaze → view matrix
    fun viewMatrix(gaze: GazeState, conv: AxisConvention, out: Mat4)
    fun detectionDirToWorld(centerNorm: Vec2, conv: AxisConvention): UnitVector3
}
```
Every math sign is a **field of `AxisConvention`**. The only consumers are `ViewCalibration` and
the arrow/detection path. On-device math-sign tuning = edit constants in **one file**.

### 6.2 Site B — `:android` `RemapConfig` (native display-rotation remap)
The `SensorManager.remapCoordinateSystem` axis table (e.g. `ROTATION_90 → AXIS_Z, AXIS_MINUS_X`,
and the landscape `pitch = values[2]` gotcha) is a native call depending on Android constants. It
**cannot** be pure `:core`. It lives in **one** `:android` file, asserted by a Robolectric test
against the same intent. We do **not** reimplement `remapCoordinateSystem` in pure Kotlin (a pure
reimpl can silently diverge from the native function).

### 6.3 Structural confinement (mechanical, not discipline)
- `SphereMesh` / `ViewMatrix` builders are **sign-free**: no yaw-negate, no pitch-sign, no V-flip
  parameter. With no sign knob, no one can twist them "for convenience" (v1's failure mode).
- **Texture V-flip is owned by the runtime `stMatrix`** from `getTransformMatrix`, NOT by a
  `Calibration.textureVFlip` field (see §9 fix 6) — owning it in both places gives device-dependent
  upside-down video. One owner only.

### 6.4 How it's pinned (tests are the executable definition of "correct")
- **`:core` invariant test:** `viewMatrix(identityGaze) == identity`; mesh top vertex == +Y; UVs
  un-flipped. Fails if any builder reintroduces a sign.
- **`:core` round-trip property:** random yaw∈[-180,180], pitch∈[-85,85] →
  `dir → viewMatrix → screen → back` recovers the input within ε.
- **`:core` NEGATIVE property test** (best idea from the panel): a deliberately wrong `yawSign`
  **MUST fail** the monotonicity/round-trip test — proving the suite *guards* signs, not just
  asserts internal consistency.
- **`:core` cross-path property:** "a detection at the gaze center is always inside FOV" — exercises
  remap-intent + projection + detection→world together; this is the trip-wire that would have
  caught v1's actual two-convention bug.
- **`:android` Robolectric:** `RemapConfig` yields gaze ≈ (0,0) for a "device flat, facing north"
  rotation vector under each `Surface.ROTATION_*`.
- **DEVICE golden-image gate (release-blocking, see §9 fix 1):** render a numbered-quadrant equirect
  chart via the fake source on a real GPU; screenshot-assert the yaw=0/pitch=0 marker lands at
  screen center and quadrants are not mirrored. **This is the ONLY net that catches a sign added in
  the un-unit-tested GL adapter** — so it is a required release gate, not optional CI.

### 6.5 `CALIBRATION.md`
One doc naming each knob (Site A fields, Site B table, the stMatrix V-flip owner) and which test
pins it, so the next on-device session is a lookup, not archaeology.

---

## 7. UI layer (Compose + Hilt)

### 7.1 Screens (Navigation-Compose)
- **LibraryScreen** — pick a local equirect video (SAF/MediaStore) + optional `.json` sidecar.
- **PlayerScreen** — `Box { AndroidView(PanoramaGlView) ; ArrowOverlay(Canvas) ; PlayerControls }`.
  Controls: play/pause/seek, **Recalibrate**, **Mono/VR toggle**.
- **SettingsScreen** — sensitivity, IPD yaw, FOV, VR scale/spacing → drive `AxisConvention` /
  `StereoEyeLayout`. (The only UI that touches calibration funnels through the one config.)

### 7.2 State — thin ViewModel, NO formal reducer
`PlayerViewModel` exposes `val state: StateFlow<PlayerUiState>` (playbackPosMs, isPlaying,
vrEnabled, arrow, calibrationNonce) and plain methods (`play()`, `pause()`, `seek()`, `toggleVr()`,
`recalibrate()`). It is a **thin pump** between ports/StateFlows and the UI — **no GL, no sensors,
no decode, no sign math**. The anti-god-object win comes from those concerns living in `:android`,
not from formalizing five trivial transitions into a reducer (YAGNI).

**High-frequency gaze does NOT go through StateFlow** (would spam recomposition) — it lives in the
shared `AtomicReference<GazeState>` read by the GL thread and the arrow overlay. Only slow UI state
is in `StateFlow`, keeping recomposition cheap.

### 7.3 GL + overlay embedding
`AndroidView(factory = { PanoramaGlView(it, projectionModel, gazeRef) })`. GL view created once;
gaze/frames bypass Compose. `ArrowOverlay` reads `state.arrow` + `gazeRef`. VR/mono toggle handles
surface lifecycle explicitly (avoid transparent-hole flicker on surface recreate).

### 7.4 DI (Hilt, minimal)
`@HiltAndroidApp` + one `@Module` binding the two ports: `DetectionSource → SidecarDetectionSource`,
`ProjectionModel → EquirectProjection`. `SensorReader` and the renderer are constructor-injected
concretes. A test/debug module binds a **`FakeDetectionSource`** (and, for demo, a fake video
source). Swapping to a future realtime detector or Insta360 source = one `@Binds` change.

---

## 8. Test strategy (three tiers)

### Tier 1 — `:core` pure JVM (JUnit + **kotest-property**, the bulk, **blocking gate**)
`kotlin-math` boundary test (slerp/fromEulerAngles endpoints, normalization, axis order — guards a
library bump); `OrientationProcessor` calibration/relative math; `OrientationSmoothing`
dt-independence (at-rest jitter shrinks, fast turn passes through); `GazePredictor` (zero-velocity
identity, constant-velocity linear lead); `EquirectProjection` mesh/UV round-trip; `PanoramaFov` /
`ArrowResolver` boundaries + nearest selection; `SidecarParser` / `DetectionTimeline` edges;
`StereoEyeLayout` / `stereoGaze` symmetry; **the calibration property tests (round-trip, invariant,
NEGATIVE sign-guard, cross-path gaze-center-inside-FOV)**. **Kover gate: line+branch ≥ 90 %** (pure
core makes this achievable). PR dropping core coverage fails CI.

### Tier 2 — `:android` Robolectric + MockK (CI, no device)
`SensorReader → RemapConfig` wiring (canned rotation vectors → `AtomicReference` updates;
remap per `Surface.ROTATION_*`); ExoPlayer wrapper state machine with a fake (play/pause/seek →
position StateFlow); `SidecarDetectionSource` content-URI read; `PlayerViewModel` method →
state transitions (Turbine). Tests must pass; coverage tracked, not hard-gated (glue code).

### Tier 3 — device / instrumented (manual + nightly; **golden-image is a release gate**)
- **Golden-image GPU calibration test** (§6.4) — release-blocking.
- GL smoke (`eglGetError == 0`), VR two-viewport layout, real-gyro feel, frame pacing via
  `dumpsys gfxinfo` / Perfetto. Not in the per-PR gate (no guaranteed device in CI).

The leverage: the per-PR gate is almost entirely device-free; the one irreducible device check
(golden image) is automated and gates releases.

---

## 9. Adversarial-review fixes baked in

The panel's winning design was critiqued by an adversarial reviewer. These six fixes are part of
this spec (without them the design would repeat v1's bugs while believing it solved them):

1. **Honest calibration claim.** Not "all signs in one file" (v1 already tried that and failed).
   Instead: **two tunable sites** (`:core` `ViewCalibration`/`AxisConvention` + `:android`
   `RemapConfig`) + the texture V-flip owned by the runtime `stMatrix` (one owner, §6.3) +
   **golden-image device gate as the load-bearing net** for the un-unit-tested GL adapter, made
   release-blocking.
2. **Render-mode contradiction resolved.** WHEN_DIRTY + Choreographer, but **render every VSYNC
   while playing** (else video stalls when the head is still); idle only when paused. Battery win
   is paused-only — stated plainly.
3. **dt-smoothing is a rewrite, not a drop-in.** v1's alpha is per-*tick* (`speedFullDeg = 2.5
   deg/tick`); converting to real `dt` changes the whole tuning surface — re-derive constants and
   rewrite the affected property tests.
4. **Defer speculative seams.** No `ProjectionModel.shaderSource()` and no `CameraSource` port in
   v2 (GLSL-string-in-pure-core leaks dialect; camera port has no second consumer). Added in the
   PRs that actually bring fisheye / the Insta adapter. Keep `DetectionSource` (real second impl
   coming) and keep mesh-gen + dir→UV in `:core`.
5. **Predictive rotation + motion-to-photon budget** (the omission that causes VR nausea) — in from
   the start (§4.3, §5.4).
6. **stMatrix vs V-flip — one owner.** The runtime `SurfaceTexture` transform owns texture flip;
   `Calibration.textureVFlip` is removed. Owning it in both places gives device-dependent
   upside-down video.

---

## 10. Biggest risk

Calibration leakage into the GL adapter — a sign added there ("for convenience," exactly as v1
still does) passes every `:core` test including the negative one, because the adapter is not in
`:core`. **Mitigation:** sign-free builders (no knob to twist) + the golden-image device gate as a
**required release gate** (the only net that sees adapter-level flips) + `CALIBRATION.md`. The risk
is organizational, so the mitigation is structural and test-enforced, not disciplinary.

---

## 10a. Dependencies — buy vs build (verified June 2026)

A library survey (GitHub release dates checked 2026-06-14) settled what we reuse vs write:

| Area | Decision | Why |
|---|---|---|
| Quaternion / matrix algebra | **`dev.romainguy:kotlin-math`** | Alive (v1.8.0, Mar 2026), Apache-2.0, Kotlin-idiomatic, pure-JVM. Removes axis-order/Hamilton bugs. |
| Gyro → fused orientation | **Android `SensorManager` `TYPE_ROTATION_VECTOR`** | The OS already does sensor fusion and returns a unit quaternion. A 3rd-party lib (FSensor) solves a problem we don't have. |
| Sphere render / equirect / playback | **Own GL ES 2 renderer** (not media3 `SphericalGLSurfaceView`) | The media3 view is alive & not deprecated, but cannot do VR dual-viewport, predictive rotation, or our calibration/sensitivity — all of which are core features. Taking it would force forking its GL layer for VR anyway; simpler to own ~200 lines. |
| VR split-screen (2 viewports, IPD, lens) | **Own** (`StereoEyeLayout` + renderer) | No live Kotlin/Compose lib in 2026. Google Cardboard is the only live stereo+distortion source but is C++/NDK-only with no Kotlin API; lens distortion isn't needed for non-cardboard split-screen. |
| Direction arrow (FOV math) | **Own** (`PanoramaFov` / `ArrowResolver`) | Pure domain logic; no library exists. |
| Video decode → texture | **media3 ExoPlayer → `SurfaceTexture` (OES)** | Standard, alive; we own only the rendering, not the decode. |

Rejected as abandoned/unfit: Google VR (GVR) SDK (archived 2019, Services pulled Nov 2023),
Rajawali (no release since 2021), Pano360/MD360 (2022/2017), Filament (alive but overkill for one
equirect sphere). Insta360 SDK intentionally excluded (the whole point of v2).

## 11. Out of scope (explicit)

Online live camera stream; real Insta360 SDK integration; dual-fisheye `.insv`; on-device realtime
detection; stitching; audio spatialization. Each has a named seam (`CameraSource`,
`:adapter-insta`, `ProjectionModel` + `shaderSource`, `DetectionSource`) so it can be added later
without rewriting the player.
