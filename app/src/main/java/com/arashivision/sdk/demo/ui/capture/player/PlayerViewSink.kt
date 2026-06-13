package com.arashivision.sdk.demo.ui.capture.player

import com.arashivision.sdk.demo.ui.capture.camera.CaptureWindowCrop
import com.arashivision.sdk.demo.ui.capture.camera.PlayerOffsets
import com.arashivision.sdk.demo.ui.capture.camera.StreamResolution

/**
 * Narrow port over InstaCapturePlayerView, confining the SDK player DATA types
 * (CaptureParamsBuilderV2, OffsetData, WindowCropInfo) to the impl. The view's own
 * lifecycle/render calls (play/destroy) stay in the Activity — only the
 * SDK-data-carrying operations are wrapped here.
 */
interface PlayerViewSink {
    val isPlaying: Boolean
    val currentWindowCrop: CaptureWindowCrop?
    val currentStabOffset: String?
    val currentResolution: StreamResolution
    val currentFileType: Int

    /** Prepare the player with capture params (was VM.getCaptureParams() + view.prepare()). */
    fun prepare()
    fun applyOffset(playerOffset: PlayerOffsets, stabOffset: String)
    fun applyWindowCrop(crop: CaptureWindowCrop)
    fun applyResolution(res: StreamResolution)
}
