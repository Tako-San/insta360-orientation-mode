# Mono SphericalGLSurfaceView Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render the mono (non-VR) 360 view with media3's built-in `SphericalGLSurfaceView` instead of the hand-written GL renderer, eliminating the thread-affinity / recomposition / render-loop bug class that blocked video from opening.

**Architecture:** Hybrid. `PlayerScreen` branches on `state.vrEnabled`: when **false** (the common path) it hosts a new `SphericalPanoramaView` wrapping `androidx.media3.exoplayer.video.spherical.SphericalGLSurfaceView`, which owns the video surface, the gyro→view rotation, and its own render loop. When **true** it keeps hosting the existing `PanoramaGlView` (the only renderer that can do split-screen VR). The custom GL stack (`PanoramaGlView`, `PanoramaRenderer`, `ChoreographerDriver`, `StereoEyeLayout`, `GazePredictor`, shaders) is NOT deleted — it is the VR path. `OrientationEngine` keeps running read-only to feed the off-screen arrow (`ArrowOverlay` already reads `gazeRef`, never the renderer).

**Tech Stack:** Kotlin, Jetpack Compose, media3 1.10.1 (`media3-exoplayer`, already a dependency — `SphericalGLSurfaceView` lives in it; no new dependency), Hilt, JUnit4 + Robolectric + MockK for `:android` tests.

---

## Background facts (read before starting)

- `SphericalGLSurfaceView` (`androidx.media3.exoplayer.video.spherical.SphericalGLSurfaceView`, `@UnstableApi`, `final class extends GLSurfaceView`):
  - Creates its own OES `Surface` internally and emits it via `addVideoSurfaceListener(VideoSurfaceListener)` → `onVideoSurfaceCreated(Surface)` / `onVideoSurfaceDestroyed(Surface)`. It is **not** a SurfaceView you pass to `player.setVideoSurfaceView`.
  - Self-drives the view from `TYPE_GAME_ROTATION_VECTOR` (fallback `TYPE_ROTATION_VECTOR`) and adds always-on touch drag. It registers/unregisters its own sensor in `onResume()` / `onPause()` — the app must NOT register a SensorManager for it.
  - For correct projection the player must also receive `player.setVideoFrameMetadataListener(view.getVideoFrameMetadataListener())` and `player.setCameraMotionListener(view.getCameraMotionListener())`.
  - Exposes NO current-gaze getter. We do not need it: the arrow's gaze comes from `OrientationEngine.gazeRef`.
- Current wiring being changed:
  - `PlayerScreen.kt:51-69` creates `PanoramaGlView` in `AndroidView.factory` and calls `bindGazeRef` + `onVideoSurfaceReady` + `update { setVrEnabled; onPlaybackStateChanged }`.
  - `PlayerViewModel` already exposes `attachVideoSurface(Surface?)` (`PlayerViewModel.kt:117` → `exo.setVideoSurface`), `gazeRef`, `startSensor()`/`stopSensor()`, `play()`/`pause()`/`seek()`.
  - `ExoVideoPlayer` (`android/.../media/ExoVideoPlayer.kt`) hops every player access onto the player's `applicationLooper` via `onPlayerThread { }`. The player is built off-main on a `HandlerThread("ExoPlayer")` and prewarmed in `PanoramaApp`. KEEP all of this.
  - `ExoVideoPlayer` exposes `open/play/pause/seekTo/setVideoSurface/refreshPosition/release`; it does NOT yet expose the frame-metadata or camera-motion sinks.
- Test conventions: `:android` tests are JUnit4 + `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [34])`, MockK for mocks. `ExoVideoPlayerTest` mocks `Player` and stubs `player.applicationLooper` to the main looper so `onPlayerThread` runs inline (see `mockPlayerWithListener()`).
- Build/run is via context-mode `ctx_execute(language:"shell", ...)`, NOT raw Bash, on this machine. After editing, rebuild with `--rerun-tasks` because configuration cache can write `assembleDebug` UP-TO-DATE without repackaging the APK.

## File Structure

- **Create** `android/src/main/kotlin/com/panorama/android/gl/SphericalPanoramaView.kt` — thin holder/wrapper around `SphericalGLSurfaceView`. One responsibility: expose `onVideoSurfaceReady: ((Surface) -> Unit)?`, the two listeners the player needs (`videoFrameMetadataListener`, `cameraMotionListener`), and lifecycle (`onResume`/`onPause`). No gaze binding (Spherical owns the view).
- **Modify** `android/src/main/kotlin/com/panorama/android/media/ExoVideoPlayer.kt` — add `setVideoFrameMetadataListener(...)` and `setCameraMotionListener(...)`, each hopping onto `onPlayerThread` like `setVideoSurface`.
- **Modify** `app/src/main/kotlin/com/panorama/app/player/PlayerViewModel.kt` — add proxies `attachFrameMetadataListener(...)` / `attachCameraMotionListener(...)` delegating to `exo`.
- **Modify** `app/src/main/kotlin/com/panorama/app/player/PlayerScreen.kt` — branch on `state.vrEnabled`: mono → `SphericalPanoramaView`; VR → existing `PanoramaGlView`. Keep `ArrowOverlay`, `PlayerControls`, sensor lifecycle.
- **Test** `android/src/test/kotlin/com/panorama/android/media/ExoVideoPlayerTest.kt` — extend for the two new setters.
- **NOT touched / NOT deleted:** `PanoramaGlView.kt`, `PanoramaRenderer.kt`, `ChoreographerDriver.kt`, `StereoEyeLayout.kt`, `GazePredictor.kt`, shaders, `OrientationEngine.kt`, `RemapConfig.kt`, `ArrowOverlay.kt`, all `:core`.

---

### Task 1: Add frame-metadata + camera-motion sinks to ExoVideoPlayer

`SphericalGLSurfaceView` needs these two listeners wired into the player or its equirect projection renders wrong. `ExoVideoPlayer` is the single place allowed to touch the thread-affine player, so the setters belong here and must hop onto `onPlayerThread`.

**Files:**
- Modify: `android/src/main/kotlin/com/panorama/android/media/ExoVideoPlayer.kt:67` (after `setVideoSurface`)
- Test: `android/src/test/kotlin/com/panorama/android/media/ExoVideoPlayerTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `ExoVideoPlayerTest.kt` (the `mockPlayerWithListener()` helper already stubs `applicationLooper` to the main looper, so `onPlayerThread` runs inline):

```kotlin
    @Test
    fun `setVideoFrameMetadataListener delegates on the player looper`() {
        val (player, _) = mockPlayerWithListener()
        val wrapper = ExoVideoPlayer(player)
        val listener = mockk<androidx.media3.exoplayer.video.VideoFrameMetadataListener>(relaxed = true)

        wrapper.setVideoFrameMetadataListener(listener)
        verify { player.setVideoFrameMetadataListener(listener) }
    }

    @Test
    fun `setCameraMotionListener delegates on the player looper`() {
        val (player, _) = mockPlayerWithListener()
        val wrapper = ExoVideoPlayer(player)
        val listener = mockk<androidx.media3.exoplayer.video.spherical.CameraMotionListener>(relaxed = true)

        wrapper.setCameraMotionListener(listener)
        verify { player.setCameraMotionListener(listener) }
    }
```

Add the imports near the top of the test file:

```kotlin
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.exoplayer.video.spherical.CameraMotionListener
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `ctx_execute shell: cd panorama-v2 && ./gradlew :android:testDebugUnitTest --tests 'com.panorama.android.media.ExoVideoPlayerTest' 2>&1 | tail -25`
Expected: FAIL — compilation error, `setVideoFrameMetadataListener` / `setCameraMotionListener` are unresolved on `ExoVideoPlayer`.

- [ ] **Step 3: Add the two setters**

In `ExoVideoPlayer.kt`, add the imports next to the existing media3 imports:

```kotlin
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.exoplayer.video.spherical.CameraMotionListener
```

**IMPORTANT (verified against media3 1.10.1 jars):** `setVideoFrameMetadataListener` / `setCameraMotionListener` are declared on `androidx.media3.exoplayer.ExoPlayer`, NOT on the `androidx.media3.common.Player` interface this wrapper holds. So the body must cast `player as ExoPlayer`. The cast is safe: the only production player is the `ExoPlayer` built by `create`, and the spherical view fundamentally requires the ExoPlayer API. Add `import androidx.media3.exoplayer.ExoPlayer` too.

Add the methods immediately after `setVideoSurface` (after line 67). Non-null listener (the ExoPlayer signatures are non-null); teardown is handled by detaching the surface, no null path needed:

```kotlin
    /** Routes decoded-frame metadata to [listener] for SphericalGLSurfaceView's equirect projection.
     *  Hops onto the player looper. These two sinks are declared on [ExoPlayer], not the [Player]
     *  interface this wrapper holds; the cast is safe because the only production player is the
     *  ExoPlayer built by [create], and the spherical view fundamentally requires the ExoPlayer API. */
    fun setVideoFrameMetadataListener(listener: VideoFrameMetadataListener) =
        onPlayerThread { (player as ExoPlayer).setVideoFrameMetadataListener(listener) }

    /** Routes camera-motion (projection) data to [listener], required by SphericalGLSurfaceView.
     *  Hops onto the player looper. Same ExoPlayer-cast rationale as above. */
    fun setCameraMotionListener(listener: CameraMotionListener) =
        onPlayerThread { (player as ExoPlayer).setCameraMotionListener(listener) }
```

The two tests must build a local `mockk<ExoPlayer>` (NOT the shared `mockPlayerWithListener()`, which mocks `Player` and would fail the cast). Each new test:
```kotlin
        val player = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { player.applicationLooper } returns android.os.Looper.getMainLooper()
        val wrapper = ExoVideoPlayer(player)
```
then construct the listener mock, call the setter, and `verify`. On surface teardown (Task 4) we do NOT pass null here — detaching the video surface (`setVideoSurface(null)`) is sufficient; the listeners are replaced on the next attach.

- [ ] **Step 4: Run tests to verify they pass**

Run: `ctx_execute shell: cd panorama-v2 && ./gradlew :android:testDebugUnitTest --tests 'com.panorama.android.media.ExoVideoPlayerTest' 2>&1 | tail -15`
Expected: PASS (all `ExoVideoPlayerTest` cases green, including the two new ones).

- [ ] **Step 5: Commit**

```bash
git add android/src/main/kotlin/com/panorama/android/media/ExoVideoPlayer.kt android/src/test/kotlin/com/panorama/android/media/ExoVideoPlayerTest.kt
git commit -m "feat(android): expose frame-metadata and camera-motion sinks on ExoVideoPlayer for spherical view"
```

---

### Task 2: Create the SphericalPanoramaView holder

A thin wrapper that owns a `SphericalGLSurfaceView`, surfaces its created `Surface` to the caller, and exposes the two listeners the player needs plus lifecycle hooks. Mirrors the role `PanoramaGlView` plays for the player wiring, minus gaze (Spherical owns the view).

**Files:**
- Create: `android/src/main/kotlin/com/panorama/android/gl/SphericalPanoramaView.kt`

There is no unit test for this class (it instantiates a real `SphericalGLSurfaceView` which needs an EGL context / device GL — same reason `ExoVideoPlayer.create` and `PanoramaGlView` are not unit-tested). It is verified on-device in Task 5.

- [ ] **Step 1: Write the holder**

Create `android/src/main/kotlin/com/panorama/android/gl/SphericalPanoramaView.kt`:

```kotlin
package com.panorama.android.gl

import android.content.Context
import android.util.AttributeSet
import android.view.Surface
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.exoplayer.video.spherical.CameraMotionListener
import androidx.media3.exoplayer.video.spherical.SphericalGLSurfaceView

/** Mono 360 viewer backed by media3's [SphericalGLSurfaceView]. The view owns its own video
 *  [Surface], the gyro->view rotation (TYPE_GAME_ROTATION_VECTOR + touch drag), its sensor
 *  registration (in [onResume]/[onPause]), and its render loop — so none of the custom GL stack
 *  ([PanoramaGlView]/[PanoramaRenderer]/[ChoreographerDriver]) is involved in mono mode.
 *
 *  The caller wires three things into the player:
 *   - the emitted [Surface] via [onVideoSurfaceReady] (and null on destroy, handled internally),
 *   - [videoFrameMetadataListener] and [cameraMotionListener], required for correct equirect
 *     projection (hand them to ExoVideoPlayer.setVideoFrameMetadataListener / setCameraMotionListener).
 *
 *  This view does NOT expose the current gaze; the off-screen arrow takes its gaze from
 *  OrientationEngine.gazeRef independently, so no gaze binding lives here. */
@UnstableApi
class SphericalPanoramaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SphericalGLSurfaceView(context, attrs) {

    /** Invoked when the internal OES-backed [Surface] becomes available; hand it to the player.
     *  Cleared (player surface detached) automatically when the surface is destroyed. */
    var onVideoSurfaceReady: ((Surface) -> Unit)? = null

    /** Invoked with null when the surface is destroyed, so the caller can detach it from the player. */
    var onVideoSurfaceDestroyed: (() -> Unit)? = null

    init {
        addVideoSurfaceListener(object : VideoSurfaceListener {
            override fun onVideoSurfaceCreated(surface: Surface) {
                onVideoSurfaceReady?.invoke(surface)
            }

            override fun onVideoSurfaceDestroyed(surface: Surface) {
                onVideoSurfaceDestroyed?.invoke()
            }
        })
    }
}
```

Note: `SphericalGLSurfaceView` already exposes `getVideoFrameMetadataListener()` and `getCameraMotionListener()` (Kotlin: `videoFrameMetadataListener` / `cameraMotionListener`) and `addVideoSurfaceListener` / `VideoSurfaceListener`; we inherit them. No need to redeclare. `onResume()` / `onPause()` are also inherited from `SphericalGLSurfaceView`.

- [ ] **Step 2: Verify it compiles**

Run: `ctx_execute shell: cd panorama-v2 && ./gradlew :android:compileDebugKotlin 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL (no unresolved references; `VideoSurfaceListener`, `addVideoSurfaceListener`, `getVideoFrameMetadataListener`, `getCameraMotionListener` all resolve from the inherited `SphericalGLSurfaceView`).

- [ ] **Step 3: Commit**

```bash
git add android/src/main/kotlin/com/panorama/android/gl/SphericalPanoramaView.kt
git commit -m "feat(android): add SphericalPanoramaView holder around media3 SphericalGLSurfaceView"
```

---

### Task 3: Add ViewModel proxies for the two listeners

`PlayerScreen` must not touch `ExoVideoPlayer` directly (the VM is the single seam to `:android`). Add proxies mirroring `attachVideoSurface`.

**Files:**
- Modify: `app/src/main/kotlin/com/panorama/app/player/PlayerViewModel.kt:117` (after `attachVideoSurface`)
- Test: `app/src/test/kotlin/com/panorama/app/player/PlayerViewModelTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `PlayerViewModelTest.kt`. The test class already has `private val exo = mockk<ExoVideoPlayer>(relaxed = true)` (line 54) and a VM factory `private fun viewModel(scope: CoroutineScope, ...)` (line 79) used inside `runTest { ... }` as `val vm = viewModel(backgroundScope)`. Follow that exact pattern:

```kotlin
    @Test
    fun `attach listeners delegate to exo`() = runTest {
        val vm = viewModel(backgroundScope)
        val frame = mockk<androidx.media3.exoplayer.video.VideoFrameMetadataListener>(relaxed = true)
        val motion = mockk<androidx.media3.exoplayer.video.spherical.CameraMotionListener>(relaxed = true)

        vm.attachFrameMetadataListener(frame)
        vm.attachCameraMotionListener(motion)

        verify { exo.setVideoFrameMetadataListener(frame) }
        verify { exo.setCameraMotionListener(motion) }
    }
```

(If `runTest`/`backgroundScope` need an import, the file already imports them — match the surrounding tests like `play and pause delegate`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `ctx_execute shell: cd panorama-v2 && ./gradlew :app:testDebugUnitTest --tests 'com.panorama.app.player.PlayerViewModelTest' 2>&1 | tail -20`
Expected: FAIL — `attachFrameMetadataListener` / `attachCameraMotionListener` unresolved on `PlayerViewModel`.

- [ ] **Step 3: Add the proxies**

In `PlayerViewModel.kt`, add the imports near the other media3 imports:

```kotlin
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.exoplayer.video.spherical.CameraMotionListener
```

Add after `attachVideoSurface` (line 117):

```kotlin
    /** Wire the spherical view's frame-metadata sink into the player (mono mode). */
    fun attachFrameMetadataListener(listener: VideoFrameMetadataListener) =
        exo.setVideoFrameMetadataListener(listener)

    /** Wire the spherical view's camera-motion sink into the player (mono mode). */
    fun attachCameraMotionListener(listener: CameraMotionListener) =
        exo.setCameraMotionListener(listener)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `ctx_execute shell: cd panorama-v2 && ./gradlew :app:testDebugUnitTest --tests 'com.panorama.app.player.PlayerViewModelTest' 2>&1 | tail -15`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/panorama/app/player/PlayerViewModel.kt app/src/test/kotlin/com/panorama/app/player/PlayerViewModelTest.kt
git commit -m "feat(app): PlayerViewModel proxies for spherical view frame-metadata and camera-motion sinks"
```

---

### Task 4: Branch PlayerScreen — Spherical for mono, PanoramaGlView for VR

This is the integration. `PlayerScreen` chooses the renderer by `state.vrEnabled`. Mono path hosts `SphericalPanoramaView` and wires surface + the two listeners; VR path keeps the existing `PanoramaGlView` wiring untouched. The sensor lifecycle (`startSensor`/`stopSensor`) and `ArrowOverlay` stay in both paths because the arrow needs `OrientationEngine` running.

**Files:**
- Modify: `app/src/main/kotlin/com/panorama/app/player/PlayerScreen.kt:49-69`

No unit test (Compose UI is verified on-device per project convention; the criterion is compilation + a connected, coherent graph). Verified on-device in Task 5.

- [ ] **Step 1: Replace the single AndroidView with a VR-flag branch**

In `PlayerScreen.kt`, replace the `Box` body's GL-view block (the `val glView = remember { ... }` and its `AndroidView(...)`, lines 50-69) with the branch below. Keep the surrounding `Box`, the `DisposableEffect` sensor block, `ArrowOverlay`, and `PlayerControls` exactly as they are.

```kotlin
        if (state.vrEnabled) {
            // VR: the only renderer that can do split-screen stereo — the custom GL stack.
            val glView = remember {
                { ctx: android.content.Context ->
                    PanoramaGlView(ctx).apply {
                        bindGazeRef(viewModel.gazeRef)
                        onVideoSurfaceReady = { surface -> viewModel.attachVideoSurface(surface) }
                    }
                }
            }
            AndroidView(
                factory = glView,
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    view.setVrEnabled(true)
                    view.onPlaybackStateChanged(state.isPlaying)
                },
            )
        } else {
            // Mono: media3's SphericalGLSurfaceView owns the surface, gyro->view, and render loop.
            // It also drives the player's projection via the frame-metadata + camera-motion sinks.
            val sphericalView = remember {
                { ctx: android.content.Context ->
                    SphericalPanoramaView(ctx).apply {
                        onVideoSurfaceReady = { surface ->
                            viewModel.attachVideoSurface(surface)
                            viewModel.attachFrameMetadataListener(videoFrameMetadataListener)
                            viewModel.attachCameraMotionListener(cameraMotionListener)
                        }
                        // On teardown, detaching the surface is enough to stop output; the
                        // metadata/motion listeners (non-null in media3) are replaced on next attach.
                        onVideoSurfaceDestroyed = { viewModel.attachVideoSurface(null) }
                    }
                }
            }
            AndroidView(
                factory = sphericalView,
                modifier = Modifier.fillMaxSize(),
                // SphericalGLSurfaceView drives its own render loop and sensor; it needs onResume/
                // onPause forwarded. Compose AndroidView calls onRelease on dispose; lifecycle is
                // forwarded in the DisposableEffect below.
                update = { /* no-op: the spherical view self-drives playback rendering */ },
            )
        }
```

- [ ] **Step 2: Forward onResume/onPause to the spherical view via the existing lifecycle observer**

The `SphericalGLSurfaceView` registers/unregisters its sensor and pauses/resumes its GL thread in `onResume()`/`onPause()`. AndroidView does not call these automatically. Hold a reference and forward lifecycle. Change the `DisposableEffect(lifecycleOwner)` block (lines 73-86) so it also drives the spherical view. Replace it with:

```kotlin
        // Keep a handle to the live spherical view (mono mode) so we can forward onResume/onPause —
        // SphericalGLSurfaceView registers its own sensor and GL thread in those callbacks.
        var sphericalRef by remember { mutableStateOf<SphericalPanoramaView?>(null) }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        viewModel.startSensor()
                        sphericalRef?.onResume()
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        sphericalRef?.onPause()
                        viewModel.stopSensor()
                    }
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                viewModel.stopSensor()
            }
        }
```

And in the mono `AndroidView.factory` from Step 1, set the ref so the observer can reach it — change the `SphericalPanoramaView(ctx).apply { ... }` so the factory also assigns `sphericalRef`:

```kotlin
            val sphericalView = remember {
                { ctx: android.content.Context ->
                    SphericalPanoramaView(ctx).apply {
                        sphericalRef = this
                        onVideoSurfaceReady = { surface ->
                            viewModel.attachVideoSurface(surface)
                            viewModel.attachFrameMetadataListener(videoFrameMetadataListener)
                            viewModel.attachCameraMotionListener(cameraMotionListener)
                        }
                        onVideoSurfaceDestroyed = { viewModel.attachVideoSurface(null) }
                    }
                }
            }
```

Add the missing imports at the top of `PlayerScreen.kt`:

```kotlin
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.panorama.android.gl.SphericalPanoramaView
```

(`androidx.compose.runtime.getValue` is already imported; `PanoramaGlView` is already imported.)

- [ ] **Step 3: Verify compilation**

Run: `ctx_execute shell: cd panorama-v2 && ./gradlew :app:compileDebugKotlin 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL (no unresolved references; `SphericalPanoramaView`, `videoFrameMetadataListener`, `cameraMotionListener`, `onResume`/`onPause` all resolve).

- [ ] **Step 4: Run the full unit-test suite (regression gate)**

Run: `ctx_execute shell: cd panorama-v2 && ./gradlew :core:test :android:testDebugUnitTest :app:testDebugUnitTest 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL — no test regressions from the wiring change.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/panorama/app/player/PlayerScreen.kt
git commit -m "feat(app): host SphericalGLSurfaceView for mono playback, keep custom renderer for VR"
```

---

### Task 5: On-device verification (the only real test for rendering)

Compose UI rendering, GL, sensors, and the spherical projection cannot be unit-tested; they are verified on the connected device (4c3c060a).

**Files:** none (verification).

- [ ] **Step 1: Build + install with a forced repackage**

Run:
```
ctx_execute shell: cd panorama-v2 && ./gradlew :app:assembleDebug --rerun-tasks 2>&1 | tail -5 \
  && APK=app/build/outputs/apk/debug/app-debug.apk \
  && export PATH="$PATH:/home/farid/android-sdk/platform-tools" \
  && adb install -r "$APK" 2>&1 | tail -1 \
  && adb shell dumpsys package com.panorama.app | grep lastUpdateTime | tr -d '\r'
```
Expected: BUILD SUCCESSFUL, `Success`, fresh `lastUpdateTime`.

- [ ] **Step 2: Mono open + spin (the headline fix)**

On the device: open a 360 video via the picker, do NOT touch the screen.
- Expected: the player appears immediately and video plays. Rotating the phone pans the panorama (gyro). This is the bug we were chasing — confirm it now opens and spins in mono.
- Capture confirmation:
  `ctx_execute shell: export PATH="$PATH:/home/farid/android-sdk/platform-tools"; adb logcat -d -s MainActivity:I 2>/dev/null | grep -E 'PlayerScreen composing' | tail -3`
  Expected: `PlayerScreen composing` appears (a frame after navigation), with the video visibly rendering.

- [ ] **Step 3: Arrow still works**

With a sidecar loaded (or accept the no-sidecar case where the arrow is hidden): rotate the phone and confirm the red chevron tracks the off-screen target. The arrow's gaze comes from `OrientationEngine` (still running), so head motion should move it in sync with the spherical view.

- [ ] **Step 4: VR path still renders**

Tap the VR toggle (`PlayerControls` "VR Off"→"VR On"). Expected: the screen switches to the split-screen custom renderer (two eye viewports). Toggle back to mono and confirm the spherical view returns and video still plays. Watch for a black frame on switch — if the surface is not detached/reattached cleanly, note it (the mono path detaches on `onVideoSurfaceDestroyed`; the VR path reattaches via `onVideoSurfaceReady`).

- [ ] **Step 5: Record the result**

If all four pass, the migration is done for mono. If the VR↔mono switch shows a black frame or double output, that is a surface-ownership issue (both renderers must not hold the player surface at once) — note it for a follow-up; the headline mono fix can still ship.

---

## Notes & known limitations (carry into review)

- `SphericalGLSurfaceView` is `@UnstableApi` — pinned to media3 1.10.1; revisit on media3 upgrades.
- Mono mode loses the custom pipeline's `calibrate()`/re-zero, adaptive SLERP smoothing, motion-to-photon prediction, and `AxisConvention` signs for the *view* (the Spherical view self-drives). The **arrow** still uses all of that via `OrientationEngine`. Consequence: in mono, the `Recalibrate` button re-zeros the arrow gaze but NOT the spherical view — a UX mismatch. Acceptable for now; consider hiding `Recalibrate` in mono mode in a follow-up (one-line guard in `PlayerControls` on `!vrEnabled`).
- Mono inherits always-on touch drag from `SphericalGLSurfaceView` (cannot be disabled via public API); the arrow cannot observe the touch component, so after a finger-drag the arrow may be slightly off until the next head movement.
- To tighten arrow/view sync, a follow-up may switch `SensorReader` from `TYPE_ROTATION_VECTOR` to `TYPE_GAME_ROTATION_VECTOR` to match Spherical's sensor — out of scope for this plan.
- Diagnostic `Log.i` calls left in `LibraryScreen`/`MainActivity`/`PanoramaRenderer`/`OrientationEngine` from the earlier debugging are still present; clean them up once mono playback is confirmed (separate cleanup commit).
