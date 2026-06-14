package com.panorama.app.player

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.panorama.android.gl.PanoramaGlView

/** The 360 player screen: a full-bleed [PanoramaGlView] with the off-screen [ArrowOverlay] and the
 *  [PlayerControls] stacked on top.
 *
 *  This composable is the Phase-3 integration hub. It owns the wiring between the three :android
 *  collaborators (held by [PlayerViewModel]):
 *   - gaze: the GL view is pointed at the engine's own gazeRef via [PanoramaGlView.bindGazeRef], so
 *     the GL thread reads exactly the gaze the sensor engine writes (no copy, one reference).
 *   - video: when the renderer's output [android.view.Surface] is ready it is handed to the player
 *     through [PlayerViewModel.attachVideoSurface].
 *   - sensor lifecycle: started/stopped from a [DisposableEffect] keyed to the screen's lifecycle.
 *   - VR / playback: state transitions drive [PanoramaGlView.setVrEnabled] /
 *     [PanoramaGlView.onPlaybackStateChanged].
 *
 *  [videoUri]/[sidecarUri] arrive as nav arguments; a [LaunchedEffect] opens them once. */
@Composable
fun PlayerScreen(
    videoUri: Uri?,
    sidecarUri: Uri? = null,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Open the chosen media exactly once per (uri) change.
    LaunchedEffect(videoUri, sidecarUri) {
        if (videoUri != null) viewModel.selectMedia(videoUri, sidecarUri)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // The GL view is created once and remembered; subsequent recompositions only re-run update.
        val glView = remember {
            { ctx: android.content.Context ->
                PanoramaGlView(ctx).apply {
                    // Read the gaze the engine writes; the renderer picks it up on the next frame.
                    bindGazeRef(viewModel.gazeRef)
                    // Renderer -> player: hand the OES-backed Surface to ExoPlayer when it exists.
                    onVideoSurfaceReady = { surface -> viewModel.attachVideoSurface(surface) }
                }
            }
        }

        AndroidView(
            factory = glView,
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                view.setVrEnabled(state.vrEnabled)
                view.onPlaybackStateChanged(state.isPlaying)
            },
        )

        // Sensor lifecycle: start on RESUME, stop on PAUSE; also stop on dispose. The GLSurfaceView's
        // own onPause/onResume are driven by AndroidView's lifecycle hooks.
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> viewModel.startSensor()
                    Lifecycle.Event.ON_PAUSE -> viewModel.stopSensor()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                viewModel.stopSensor()
            }
        }

        ArrowOverlay(
            arrow = state.arrow,
            vrEnabled = state.vrEnabled,
            gazeRef = viewModel.gazeRef,
            modifier = Modifier.fillMaxSize(),
        )

        PlayerControls(
            isPlaying = state.isPlaying,
            positionMs = state.playbackPosMs,
            durationMs = state.durationMs,
            vrEnabled = state.vrEnabled,
            onPlayPause = { if (state.isPlaying) viewModel.pause() else viewModel.play() },
            onSeek = viewModel::seek,
            onRecalibrate = viewModel::recalibrate,
            onToggleVr = viewModel::toggleVr,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
