# Variant C — Custom GL Panorama Renderer — Design

**Date:** 2026-06-13
**Goal:** Replace media3 `SphericalGLSurfaceView` in the offline player with an own
OpenGL ES renderer, so panorama view rotation is driven directly by the gyro quaternion
(one source of truth for both the sphere and the direction arrow), with **no reflection**
into private media3 internals. Closes audit findings N1 (private `mRenderer.onScrollChange`
reflection) and N2 (probe-A debug logging).

**Approach:** Own `GLSurfaceView.Renderer` (chosen over forking media3's renderer — see
audit follow-up). Pure mesh/matrix math in `:lib` (JVM-tested); GL plumbing in `:app`
(device-verified).

---

## 1. Components

| Component | Module/Path | Responsibility | Tested |
|---|---|---|---|
| `PanoramaGLSurfaceView` | `:app` `ui/player/gl/PanoramaGLSurfaceView.kt` | `GLSurfaceView` subclass; owns the GL context, the `SurfaceTexture`, and the render loop; exposes `videoSurface: Surface` for ExoPlayer and `setOrientation(UnitQuaternion)` | device |
| `PanoramaRenderer` | `:app` `ui/player/gl/PanoramaRenderer.kt` | `GLSurfaceView.Renderer`: compiles shaders, uploads the mesh to VBOs, binds the OES texture, draws with the MVP matrix each frame; calls `surfaceTexture.updateTexImage()` | device |
| `SphereMesh` | **`:lib`** `panorama/SphereMesh.kt` | Pure generation of an inward-facing UV-sphere: interleaved position+UV vertices + triangle indices | **JVM** |
| `ViewMatrixMath` | **`:lib`** `panorama/ViewMatrixMath.kt` | Pure 4×4 view matrix from a gaze `UnitQuaternion`, and perspective projection matrix from vertical FOV + aspect | **JVM** |
| Shaders | `:app` inline strings | vertex (applies MVP), fragment (`samplerExternalOES` sample) | — |

## 2. Data flow

```
ExoPlayer ──renders video──▶ Surface ◀──created from── SurfaceTexture (GL_TEXTURE_EXTERNAL_OES)
                                                              │ onFrameAvailable → requestRender
GyroOrientationController ──gaze quaternion──▶ PanoramaGLSurfaceView.setOrientation(q)
                                                              │
                                          PanoramaRenderer.onDrawFrame:
                                            updateTexImage()
                                            view = ViewMatrixMath.viewFromQuaternion(q)
                                            mvp  = projection · view
                                            draw SphereMesh with OES texture
```

The SAME gaze quaternion that feeds `setOrientation` also produces `currentGazeDirection`
used by the direction arrow (`DetectionArrowResolver`). One source of truth — sphere and
arrow can no longer diverge.

## 3. `:lib` pure pieces (the testable core)

### `SphereMesh`
- `fun generate(stacks: Int = 32, slices: Int = 64, radius: Float = 1f): SphereMeshData`
- `SphereMeshData(positions: FloatArray, texCoords: FloatArray, indices: ShortArray)`.
- Inward-facing equirectangular UV sphere: longitude → U in [0,1], latitude → V in [0,1].
- Winding/normals oriented so the camera at the center sees the inner surface.
- Tests: vertex count == (stacks+1)*(slices+1); index count == stacks*slices*6; all U/V in
  [0,1]; every position on the unit sphere (|p| ≈ radius); poles present.

### `ViewMatrixMath`
- `fun viewFromQuaternion(q: UnitQuaternion, out: FloatArray)` — writes a column-major 4×4
  view matrix = inverse (conjugate) rotation of the gaze quaternion (rotating the world
  opposite to the gaze, camera fixed at origin).
- `fun perspective(fovYDeg: Float, aspect: Float, near: Float, far: Float, out: FloatArray)`
  — standard GL perspective.
- Tests: identity quaternion → identity rotation; 90° yaw → known basis mapping; matrix is
  orthonormal (rotation part), round-trip vector through view matrix matches manual rotation;
  perspective matrix matches reference values for a known FOV/aspect.

(`UnitQuaternion` already exists in `:lib` `EquirectangularProjection.kt` and has the
conjugate/rotation primitives; reuse it.)

## 4. `:app` GL plumbing

### `PanoramaGLSurfaceView`
- `setEGLContextClientVersion(2)`, `setRenderer(renderer)`, `renderMode = RENDERMODE_WHEN_DIRTY`.
- Creates `SurfaceTexture` on the renderer's GL texture id (created in `onSurfaceCreated`),
  wraps it in a `Surface` exposed as `val videoSurface: Surface`.
- `surfaceTexture.setOnFrameAvailableListener { requestRender() }`.
- `fun setOrientation(q: UnitQuaternion)` stores the latest quaternion (volatile) and
  `requestRender()`.
- Lifecycle: `onResume`/`onPause` forwarded to `GLSurfaceView`. Releases SurfaceTexture/Surface
  on detach.

### `PanoramaRenderer`
- `onSurfaceCreated`: gen OES texture, compile/link program, upload `SphereMesh` to VBO/EBO,
  cache attrib/uniform locations. Signals the view the texture id is ready (so it can build the
  SurfaceTexture).
- `onSurfaceChanged`: viewport + `ViewMatrixMath.perspective(...)` with the new aspect.
- `onDrawFrame`: `updateTexImage()`; `getTransformMatrix()` for the ST matrix (apply to UVs or
  in shader); compute view from the stored quaternion; mvp = projection·view; draw elements.

### Stereo / mono
- Phase 1: mono only (`STEREO_MODE_MONO` equivalent) — the offline source is monoscopic
  equirectangular; matches current `setDefaultStereoMode(C.STEREO_MODE_MONO)`.

## 5. Integration changes

- `res/layout/activity_local_spherical_player.xml`: `SphericalGLSurfaceView` →
  `com.arashivision.sdk.demo.ui.player.gl.PanoramaGLSurfaceView`. View id stays `sphericalView`
  to minimize churn (binding name unchanged).
- `LocalSphericalPlayerActivity`:
  - `exo.setVideoSurfaceView(binding.sphericalView)` → `exo.setVideoSurface(binding.sphericalView.videoSurface)`.
  - Remove `playerSink` / `Media3SphericalOrientationSink` usage; in `tryApplyOrientation`,
    call `binding.sphericalView.setOrientation(gazeQuaternion)` instead of `playerSink.apply(...)`.
    `currentGazeDirection` still updated from the same gaze for the arrow.
  - Drop `setDefaultStereoMode` / `setUseSensorRotation` (media3-specific).
  - `onResume`/`onPause` call the new view's lifecycle.
- Delete `Media3SphericalOrientationSink.kt` (offline reflection gone). Keep `OrientationApplier`
  interface only if still used; otherwise remove. `ReflectiveOrientationSink` (online camera)
  is untouched.
- `LocalVrManager`: still copies frames via PixelCopy from `sourceView`. Our view is a
  `GLSurfaceView` (a `SurfaceView`), so `findSurfaceView` finds it. **Risk:** PixelCopy from a
  GL surface must still yield frames — verify on device; fallback is `getBitmap` path already in
  base, or render-to-FBO if needed.

## 6. Orientation source

`GyroOrientationController` already exposes `getCurrentQuaternion()` / `getSmoothedQuaternion()`
and the gaze angles. The renderer wants the **gaze** orientation (same one driving the arrow).
We feed `setOrientation` from `tryApplyOrientation`, building a `UnitQuaternion` from the gaze
yaw/pitch (via `EquirectangularProjection.fromYawPitch(...).orientation`) — guaranteeing sphere
and arrow share one orientation. Sign/axis conventions reuse the verified probe-A mapping
(inverted yaw/pitch already encoded in `PlayerOrientationCoordinator`); the smoothed gaze from
the coordinator is the input.

**Critical invariant:** the resulting on-screen rotation must match what probe A produced via
`onScrollChange` (the verified-correct direction). Because `onScrollChange` took (yaw, pitch)
in degrees and our renderer takes a quaternion, the view-matrix convention
(`ViewMatrixMath.viewFromQuaternion`) must be calibrated so that the same gaze yaw/pitch yields
the same visible heading. This is verified on device against probe A; if the axis/sign differs,
it is corrected once in `ViewMatrixMath` (and covered by a test pinning the mapping), NOT by
ad-hoc negation scattered in the Activity.

## 7. Error handling

- Shader compile/link failures: log + throw on init (fail fast; this is dev-time).
- Missing GL_OES_EGL_image_external: log; the OES sampler is universally supported on minSdk 29,
  so treat as fatal-with-clear-message rather than silent.
- SurfaceTexture frame timing: `RENDERMODE_WHEN_DIRTY` + `onFrameAvailable` avoids busy-loop;
  also `requestRender()` on `setOrientation` so head motion updates even on a paused video.

## 8. Testing

- `:lib`: `SphereMeshTest`, `ViewMatrixMathTest` (pure JVM, in the Kover gate).
- `:app`: GL code is device-only. On-device acceptance: load a 360 video, sphere renders, gyro
  rotates the view correctly (same direction as probe A), arrow stays in sync, VR split-screen
  still mirrors, no reflection/log spam. User-gated.

## 9. Risks & mitigations

- **PixelCopy from GL surface in VR** — verify early; fallback paths noted in §5.
- **Behavior regression vs probe A** — reuse the exact verified gaze→angles mapping; on-device
  check gates the feature.
- **Texture transform / flip** — equirect UV orientation and the SurfaceTexture transform matrix
  can flip the image; resolve on device by applying `getTransformMatrix()` to UVs.
- **Scope** — mono only; no stereo-in-renderer, no barrel distortion (VR stays the existing
  PixelCopy mirror). YAGNI.

## 10. Success criteria

Offline player renders the panorama through `PanoramaGLSurfaceView`; gyro rotates it correctly;
arrow synced; VR works; `Media3SphericalOrientationSink` and its reflection/logging are gone;
`:lib` mesh/matrix tests pass in the Kover gate; verified on device by the user.
