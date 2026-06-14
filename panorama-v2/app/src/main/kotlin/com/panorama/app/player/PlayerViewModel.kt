package com.panorama.app.player

import android.net.Uri
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panorama.android.detection.SidecarLoader
import com.panorama.android.media.ExoVideoPlayer
import com.panorama.android.sensor.OrientationEngine
import com.panorama.core.calibration.AxisConvention
import com.panorama.core.detection.DetectionSource
import com.panorama.core.fov.ArrowResolver
import com.panorama.core.math.GazeState
import com.panorama.core.projection.ProjectionModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.math.PI

/** Thin pump between the player UI and the :android collaborators. It holds no rendering logic and
 *  no reducer: each user action is a plain method that delegates to [ExoVideoPlayer] /
 *  [OrientationEngine] and pokes the [StateFlow]. The high-frequency gaze is intentionally kept
 *  OUT of [state] — it travels GL-side through the engine's gazeRef — so the UI only re-composes on
 *  slow transitions plus the throttled (~30 Hz) arrow recompute.
 *
 *  Testability: the throttle loop's [throttleDispatcher] and [tickerIntervalMs] are injectable so
 *  coroutines-test can drive virtual time; the endless mirror/throttle coroutines run in the
 *  injectable [backgroundScope] (default [viewModelScope]) so a test can hand in a scope it
 *  auto-cancels (e.g. runTest's backgroundScope) and avoid hangs. The secondary @Inject constructor
 *  wires the production defaults ([viewModelScope], [Dispatchers.Default], ~33 ms). */
@HiltViewModel
class PlayerViewModel(
    private val exo: ExoVideoPlayer,
    private val orientationEngine: OrientationEngine,
    private val sidecarLoader: SidecarLoader,
    @Suppress("unused") private val projection: ProjectionModel,
    private val axisConvention: AxisConvention,
    private val throttleDispatcher: CoroutineDispatcher,
    private val tickerIntervalMs: Long,
    backgroundScope: CoroutineScope? = null,
) : ViewModel() {

    /** Hilt entry point: only the graph-provided ports are injected; the throttle knobs default to
     *  production values and the background coroutines run in [viewModelScope]. */
    @Inject
    constructor(
        exo: ExoVideoPlayer,
        orientationEngine: OrientationEngine,
        sidecarLoader: SidecarLoader,
        projection: ProjectionModel,
        axisConvention: AxisConvention,
    ) : this(
        exo = exo,
        orientationEngine = orientationEngine,
        sidecarLoader = sidecarLoader,
        projection = projection,
        axisConvention = axisConvention,
        throttleDispatcher = Dispatchers.Default,
        tickerIntervalMs = DEFAULT_TICKER_INTERVAL_MS,
    )

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** Per-playback, created on [selectMedia] from the chosen sidecar; null when the video has no
     *  sidecar, which disables arrows. */
    @Volatile
    private var detectionSource: DetectionSource? = null

    init {
        // Endless flows/loop live in the injectable scope (default viewModelScope); a test passes a
        // scope it auto-cancels so runTest never joins on these never-completing coroutines.
        val scope = backgroundScope ?: viewModelScope
        // Mirror the player's coroutine flows into the slow UI state.
        scope.launch {
            exo.isPlaying.collect { playing -> _state.update { it.copy(isPlaying = playing) } }
        }
        scope.launch {
            exo.positionMs.collect { pos -> _state.update { it.copy(playbackPosMs = pos) } }
        }
        // Throttled arrow recompute + transport poll (~30 Hz) on the injectable dispatcher. The poll
        // is what drives the seek bar: refreshPosition() samples position+duration on the player's
        // own looper (media3 is single-thread-affine) into exo.positionMs / exo.durationMs, which we
        // then mirror into the slow UI state.
        scope.launch(throttleDispatcher) {
            while (isActive) {
                exo.refreshPosition()
                val dur = exo.durationMs.value
                if (dur > 0L) _state.update { if (it.durationMs == dur) it else it.copy(durationMs = dur) }
                recomputeArrow()
                delay(tickerIntervalMs)
            }
        }
    }

    /** The sensor engine's own gaze snapshot, exposed so the UI can hand it straight to
     *  [com.panorama.android.gl.PanoramaGlView.bindGazeRef] without the UI touching :android sensors
     *  directly. The GL thread then reads exactly what the engine writes — one shared reference. */
    val gazeRef: AtomicReference<GazeState> get() = orientationEngine.gazeRef

    /** Sensor lifecycle proxies driven from the screen's DisposableEffect (onResume/onPause). */
    fun startSensor() = orientationEngine.start()

    fun stopSensor() = orientationEngine.stop()

    /** Wire the GL view's output Surface into the player once the renderer has created it. */
    fun attachVideoSurface(surface: Surface?) = exo.setVideoSurface(surface)

    fun play() = exo.play()

    fun pause() = exo.pause()

    fun seek(ms: Long) = exo.seekTo(ms)

    /** VM only holds the flag; the Activity reads it to drive PanoramaGlView.setVrEnabled. */
    fun toggleVr() = _state.update { it.copy(vrEnabled = !it.vrEnabled) }

    /** Re-zero the gaze and bump the nonce so the UI can react even though gaze is read off-band. */
    fun recalibrate() {
        orientationEngine.calibrate()
        _state.update { it.copy(calibrationNonce = it.calibrationNonce + 1) }
    }

    /** Open [videoUri] in the player and start playback; if a [sidecarUri] is given, load its
     *  detections for arrows. This is a 360 player, so opening a clip auto-plays it: starting
     *  playback also spins up the GL render loop (via isPlaying -> onPlaybackStateChanged), which a
     *  prepare-only open would leave halted on the first frame. */
    fun selectMedia(videoUri: Uri, sidecarUri: Uri? = null) {
        exo.open(videoUri)
        exo.play()
        detectionSource = sidecarUri?.let { sidecarLoader.load(it) }
    }

    private fun recomputeArrow() {
        val source = detectionSource
        val arrow = if (source == null) {
            HIDDEN_ARROW
        } else {
            val gaze = orientationEngine.currentGaze()
            val detections = source.detectionsAt(exo.positionMs.value)
            ArrowResolver.resolve(detections, gaze, H_FOV_RAD, V_FOV_RAD, axisConvention)
        }
        _state.update { if (it.arrow == arrow) it else it.copy(arrow = arrow) }
    }

    private companion object {
        const val DEFAULT_TICKER_INTERVAL_MS = 33L          // ~30 Hz
        val H_FOV_RAD = (90.0 * PI / 180.0).toFloat()
        val V_FOV_RAD = (60.0 * PI / 180.0).toFloat()
        val HIDDEN_ARROW = com.panorama.core.fov.ArrowState(visible = false, angleRad = null)
    }
}
