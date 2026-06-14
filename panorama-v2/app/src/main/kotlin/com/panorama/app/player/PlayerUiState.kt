package com.panorama.app.player

import com.panorama.core.fov.ArrowState

/** The slow-changing UI snapshot the player screen renders. Deliberately excludes the
 *  high-frequency gaze quaternion: gaze flows GL-side through the engine's gazeRef, never through
 *  this StateFlow, so the UI only re-composes on real state transitions (play/pause, seek, VR,
 *  calibration, the throttled arrow recompute).
 *
 *  @param calibrationNonce bumped on every recalibrate() so the screen can react to a re-zero even
 *         though the gaze itself is read off-band. */
data class PlayerUiState(
    val playbackPosMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val vrEnabled: Boolean = false,
    val arrow: ArrowState = ArrowState(visible = false, angleRad = null),
    val calibrationNonce: Int = 0,
)
