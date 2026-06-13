# Architecture Audit — Follow-up (post-refactoring session)

Date: 2026-06-13. This updates the original 44-finding audit
(`2026-06-13-architecture-audit.md`) after the refactoring session that landed
the gyro fix, the pure-`:lib` extraction, the Ports & Adapters test strategy,
and the comment/commit/PR English migration. Analysis only — no code changed by
this document.

## What changed since the original audit

The original audit's top recommendations have largely been implemented, in a
slightly different (and in one case better-informed) shape than first proposed.

### Closed

- **#1 Extract pure panorama math + detection parsing into `:lib` + tests** — DONE.
  `:lib` now holds Quaternion, OrientationMath, OrientationSmoothing,
  OrientationProcessor, PlayerOrientationCoordinator, SensorOrientation,
  panorama/ (EquirectangularProjection, PanoramaFovMath), detection/ (models,
  parser, timeline, DetectionArrowResolver), capture/TimelapseMath. 85 tests,
  ~97% line coverage (Kover).
- **#2 Quaternion test coverage (fromRotationMatrix branches, slerp)** — DONE
  (OrientationMathTest expanded to 24 cases incl. m11/m22 branches, slerp dot<0,
  gimbal, unwrap).
- **#3 Type-safe interface around setYaw/setPitch reflection** — DONE, and the
  premise was corrected: media3 `SphericalGLSurfaceView` has **no** setYaw/setPitch
  at all (that was the root cause of the broken offline view control). Offline now
  rotates via `OrientationApplier` → `Media3SphericalOrientationSink` (reflects the
  private GL renderer's `onScrollChange`). Online camera keeps `ReflectiveOrientationSink`
  on `InstaCapturePlayerView` (which does have setYaw/setPitch).
- **#4 Remove static companion sensivity/invert from GyroOrientationController** — DONE.
  Now instance state, proxied through the pure OrientationProcessor; the
  cross-module capture↔player coupling and race window are gone.
- **#9 Harden ViewBinding reflection** — DONE. `util/ViewBindingUtils` caches the
  inflate Method per class (ConcurrentHashMap), preserves the cause on wrapped
  exceptions, and validates the ParameterizedType with meaningful checks.
- **#10 Hygiene** — MOSTLY DONE. CaptureText extracted from CaptureConst; silent
  catches in VR/calibration now log; gyro logger made lazy.

### Partially addressed

- **#8 Detections off Handler-polling onto Flow** — the *valuable* part is done:
  the pure arrow-selection logic is now `DetectionArrowResolver` in `:lib` with
  tests. The 200ms `Handler` polling was deliberately kept (rewriting it to Flow
  changes UI timing with no unit-testable gain). Empty `onEvent` still present.
- **#6/#7 CameraSDKAdapter + split god-object CaptureViewModel** — only the safe
  pure slice extracted (`TimelapseMath`). The full adapter + split is **deferred**
  because it touches the live camera online path, which cannot be verified without
  a physical 360 camera, and several SDK types (CaptureParamsBuilderV2,
  InstaCapturePlayerView) would still have to leak into the adapter interface.

### Still open (unchanged)

- **#5 VR duplication** — VrManager (609 lines) and LocalVrManager (340 lines)
  remain two copies of the PixelCopy loop, settings dialog, and applyVrAdjustments,
  with diverging error handling. ~949 lines total. Highest-value remaining cleanup.

## New findings introduced or surfaced by the session

These are observations that did not exist (or were not framed) in the original audit.

### N1 — [medium] Offline view rotation depends on a media3 *private* GL field

- Files: `ui/player/Media3SphericalOrientationSink.kt`
- The offline sphere is rotated by reflecting `GLSurfaceView.mRenderer` and calling
  the private renderer's `onScrollChange(PointF)`. This works and is now the live
  mechanism, but it is the same class of fragility the audit flagged for setYaw:
  a media3 upgrade can silently break it (caught + logged, not crash). This is
  explicitly a "probe A" — the intended permanent fix is the custom GL renderer
  (variant C) with a single orientation source and no reflection. Tracked.

### N2 — [low] Probe-A diagnostic logging left in the hot path

- Files: `ui/player/Media3SphericalOrientationSink.kt`
- `apply()` logs every 60th call ("apply yaw=… → onScrollChange"). Useful during
  bring-up; should be removed or gated behind a debug flag once variant C lands.

### N3 — [low] `:app` is not a blocking CI gate (by design, but worth recording)

- Files: `.github/workflows/ci.yml`
- `:app` compilation pulls dozens of Insta360 SDK / native artifacts from external
  mirrors (aliyun, insta360 Nexus) that intermittently 502/timeout from cloud
  runners. The `app-unit-tests` job is `continue-on-error: true`; `lib-tests` is the
  hard gate. Consequence: an `:app`-only regression (e.g. a broken `:app` unit test)
  will NOT turn CI red. Mitigation if this matters later: a self-hosted runner with
  the SDK cached, or vendoring the SDK artifacts.

### N4 — [low] Calibration state still lives in the controller stack, not the ViewModel

- Files: `lib/.../OrientationProcessor.kt`, `ui/capture/GyroOrientationController.kt`
- Original finding (calibration ephemeral, lost on controller recreation) still
  holds — it just moved from the controller into OrientationProcessor. Surviving
  process death / config change would require lifting calibration into the
  ViewModel/SavedState. Low priority.

## Recommended next directions (re-prioritized for current state)

1. **Variant C — custom GL panorama renderer** (closes N1/N2 and the last reflection
   fragility). Largest value now that the math is proven and test-covered. Needs
   on-device verification. Spec/plan pending.
2. **#5 VR de-duplication** — extract a `VrManagerBase` (PixelCopy loop, settings
   dialog, applyVrAdjustments) parameterized by sourceView; lift the pure stereo
   geometry (eyeScale/spacing/IPD) into a `:lib` data class with tests. Pure-ish
   parts testable; the View-tree parts need a device. ~949 lines → ~one base + two
   thin subclasses.
3. **#6/#7 CameraSDKAdapter + CaptureViewModel split** — when a 360 camera is
   available to verify. Map already produced (66 SDK calls, 5 callback interfaces,
   5 suspend bridges; adapter-leaking types: CaptureParamsBuilderV2,
   InstaCapturePlayerView, CaptureMode, CaptureSetting, WindowCropInfo).
4. **On-device verification of WP1** (the ports refactor of the gyro path) — behavior
   was ported verbatim from the verified probe A, but the live path was restructured.

## Health snapshot

- `:lib`: 85 tests, ~97% line coverage, no Android/SDK dependency, hard CI gate.
- `:app`: 11 tests (gyro glue via MockK + RotationMatrixMath via Robolectric),
  Kover gate ≥80% on the gyro controller (currently 97%); non-blocking CI job.
- Public layer (commits, PR, code comments) fully English.
- Largest files: DiscreteScrollLayoutManager.java 793 (vendored, leave),
  CaptureViewModel.kt 650 (god-object, deferred), VrManager.kt 609 (dup, #5).
