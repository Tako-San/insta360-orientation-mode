package com.arashivision.sdk.demo.ui.capture

import com.arashivision.sdk.demo.base.BaseEvent
import com.arashivision.sdk.demo.ui.capture.camera.CameraSDKAdapter
import com.arashivision.sdk.demo.ui.capture.camera.CaptureWindowCrop
import com.arashivision.sdk.demo.ui.capture.camera.StreamResolution
import com.arashivision.sdkcamera.camera.model.CaptureMode
import com.elvishew.xlog.Logger
import com.elvishew.xlog.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Handles camera preview-stream parameter changes, extracted from CaptureViewModel.
 *
 * Reads player state from caller-supplied parameters (Activity/sink supplies current values)
 * so this class stays free of Android Views and is JVM-testable.
 *
 * @param adapter                              SDK operations behind a testable interface.
 * @param scope                                Coroutine scope for suspend calls (viewModelScope in production).
 * @param emitEvent                            Sends domain events to the caller (ViewModel → UI bus).
 * @param setOfflineCaptureSettingValueToCamera Injected suspend lambda; called after photo capture
 *                                             finishes when the preview is still open and no stream
 *                                             restart is needed.
 */
class PreviewParamsController(
    private val adapter: CameraSDKAdapter,
    private val scope: CoroutineScope,
    private val emitEvent: (BaseEvent) -> Unit,
    private val setOfflineCaptureSettingValueToCamera: suspend () -> Unit,
) {

    private val logger: Logger by lazy { XLog.tag("PreviewParamsController").build() }

    /**
     * Called when the SDK fires onPreviewStreamParamsChanged (after WiFi/isFetchingOptions guards).
     * Verbatim port of VM.onCameraPreviewStreamParamsChanged effect — just emits the event.
     */
    fun onPreviewStreamParamsChanged() {
        emitEvent(CaptureEvent.CameraPreviewStreamParamsChangedEvent)
    }

    /**
     * Ports VM.cameraPreviewStreamParamsChanged(playerView) verbatim.
     * The caller (Activity/ViewModel) supplies current player state as parameters instead of
     * passing the SDK View directly.
     *
     * @param mode              Current capture mode.
     * @param isPlaying         Whether the player is currently playing (playerView.isPlaying).
     * @param currentCrop       Current window-crop on the player, or null if not set.
     * @param currentStabOffset Current stab-offset string on the player, or null.
     * @param currentResolution Current preview resolution on the player, or null.
     * @param currentFileType   Current preview file type from the player (playerView.fileType).
     */
    fun cameraPreviewStreamParamsChanged(
        mode: CaptureMode,
        isPlaying: Boolean,
        currentCrop: CaptureWindowCrop?,
        currentStabOffset: String?,
        currentResolution: StreamResolution?,
        currentFileType: Int,
    ) {
        // Preview encoding format changed → restart decoder and player
        if (adapter.isEncodeMismatch()) {
            adapter.applyStreamEncode()
            emitEvent(CaptureEvent.RestartPlayerViewEvent)
            return
        }

        if (!isPlaying) return

        // Preview anti-shake switch changed → restart player
        if (adapter.isPreviewFileTypeChanged(mode, currentFileType)) {
            scope.launch {
                adapter.fetchCameraOptions()
                emitEvent(CaptureEvent.RestartPlayerViewEvent)
            }
            return
        }

        val inputs = adapter.previewUpdateInputs(mode, currentCrop, currentStabOffset, currentResolution)
            ?: return

        logger.d("cameraPreviewStreamParamsChanged  windowCrop=${inputs.windowCrop}  playerOffset=${inputs.playerOffset}  stabOffset=${inputs.stabOffset}  resolution=${inputs.resolution}")
        emitEvent(CaptureEvent.UpdatePlayerViewParamsEvent(
            windowCrop = inputs.windowCrop,
            playerOffset = inputs.playerOffset,
            stabOffset = inputs.stabOffset,
            resolution = inputs.resolution,
        ))
    }

    /**
     * Ports VM.onCaptureFinishEnd() verbatim.
     * The VM coordinator (Task 6) emits CameraCaptureEvent(FINISH) first, then calls this.
     *
     * @param mode Current capture mode (replaces cameraOfflineData.currentCaptureMode access).
     */
    fun onCaptureFinish(mode: CaptureMode) {
        scope.launch {
            if (adapter.supportsNewCaptureControlFlow) return@launch

            if (mode.isVideoMode || mode in arrayOf(CaptureMode.INTERVAL_SHOOTING, CaptureMode.STARLAPSE_SHOOTING)) {
                adapter.reopenPreviewStream()
                emitEvent(CaptureEvent.RestartPlayerViewEvent)
            } else {
                if (mode.isPhotoMode) {
                    // Camera has an internal H264/H265 switching bug during photo capture;
                    // detect the change and restart preview if it occurred.
                    val oldIsH265 = adapter.isStreamH265
                    adapter.fetchCameraOptions()
                    if (adapter.isStreamH265 != oldIsH265) {
                        adapter.reopenPreviewStream()
                        emitEvent(CaptureEvent.RestartPlayerViewEvent)
                    }
                }
                if (adapter.isPreviewOpened) {
                    setOfflineCaptureSettingValueToCamera()
                }
            }
        }
    }
}
