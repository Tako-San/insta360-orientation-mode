package com.arashivision.sdk.demo.ui.capture

import androidx.lifecycle.viewModelScope
import com.arashivision.orientation.capture.TimelapseMath
import com.arashivision.sdk.demo.base.BaseViewModel
import com.arashivision.sdk.demo.capture.CameraOfflineData
import com.arashivision.sdk.demo.pref.Pref
import com.arashivision.sdk.demo.ui.capture.camera.CameraCallbacks
import com.arashivision.sdk.demo.ui.capture.camera.CaptureWindowCrop
import com.arashivision.sdk.demo.ui.capture.camera.InstaCameraSDKAdapter
import com.arashivision.sdk.demo.ui.capture.camera.StreamResolution
import com.arashivision.sdkcamera.camera.model.CaptureMode
import com.arashivision.sdkcamera.camera.model.CaptureSetting
import com.arashivision.sdkmedia.player.capture.CaptureParamsBuilderV2
import com.arashivision.sdkmedia.player.capture.InstaCapturePlayerView
import com.arashivision.sdkmedia.player.config.InstaStabType
import com.elvishew.xlog.Logger
import com.elvishew.xlog.XLog

/**
 * Thin coordinator over [InstaCameraSDKAdapter] + 3 controllers.
 *
 * Public API is identical to the old god-object so [CaptureActivity] compiles unchanged.
 * All business logic lives in the controllers; this class wires them together and
 * manages the ViewModel lifecycle (registerListeners / onCleared).
 */
class CaptureViewModel : BaseViewModel() {

    private val logger: Logger = XLog.tag(CaptureViewModel::class.java.simpleName).build()

    // --- adapter ---
    private val adapter = InstaCameraSDKAdapter()

    // --- offline data (set once by connectionController.initCapture) ---
    lateinit var cameraOfflineData: CameraOfflineData
        private set

    // --- settings bridge: builds a Map from the offline data and commits via adapter ---
    private val setOfflineCaptureSettingValueToCamera: suspend () -> Unit = {
        val mode = cameraOfflineData.currentCaptureMode
        val values = adapter.supportSettingsForMode(mode).associateWith { setting ->
            cameraOfflineData.getCaptureSetting(mode, setting)
        }
        adapter.commitSettings(mode, values)
    }

    // --- controllers ---
    private val connectionController = CaptureConnectionController(
        adapter = adapter,
        scope = viewModelScope,
        emitEvent = ::emitEvent,
        onOfflineDataReady = { cameraOfflineData = it },
        setOfflineCaptureSettingValueToCamera = setOfflineCaptureSettingValueToCamera,
    )

    private val controlController = CaptureControlController(
        adapter = adapter,
        emitEvent = ::emitEvent,
        getLiveRtmp = { Pref.getLiveRtmp() },
    )

    private val previewParamsController = PreviewParamsController(
        adapter = adapter,
        scope = viewModelScope,
        emitEvent = ::emitEvent,
        setOfflineCaptureSettingValueToCamera = setOfflineCaptureSettingValueToCamera,
    )

    // --- SDK callback dispatcher ---
    private val cameraCallbacks = object : CameraCallbacks {

        override fun onWifiDisconnected() {
            emitEvent(CaptureEvent.CameraWiFiDisconnectEvent)
        }

        // Preview open/error are handled as suspend results inside connectionController;
        // no extra event needed here.
        override fun onPreviewOpened() = Unit
        override fun onPreviewError() = Unit

        override fun onCaptureStarting() {
            emitEvent(CaptureEvent.CameraCaptureEvent(CaptureEvent.CaptureStatus.STARTING))
        }

        override fun onCaptureWorking() {
            emitEvent(CaptureEvent.CameraCaptureEvent(CaptureEvent.CaptureStatus.WORKING))
        }

        override fun onCaptureStopping() {
            emitEvent(CaptureEvent.CameraCaptureEvent(CaptureEvent.CaptureStatus.STOPPING))
        }

        override fun onCaptureFinish() {
            emitEvent(CaptureEvent.CameraCaptureEvent(CaptureEvent.CaptureStatus.FINISH))
            previewParamsController.onCaptureFinish(cameraOfflineData.currentCaptureMode)
        }

        override fun onCaptureError(code: Int) {
            emitEvent(CaptureEvent.CameraCaptureEvent(errorCode = code))
        }

        override fun onCaptureTime(ms: Long) {
            // Verbatim port of old VM.onCaptureTimeChanged: timelapse shows finished-video duration.
            if (adapter.isCameraWorking(CaptureMode.TIMELAPSE)) {
                val interval = adapter.timelapseIntervalNative(CaptureMode.TIMELAPSE)
                val fps = adapter.timelapseFps(CaptureMode.TIMELAPSE)
                val videoTime = TimelapseMath.videoDurationMs(ms, interval, fps)
                emitEvent(CaptureEvent.CameraCaptureEvent(CaptureEvent.CaptureStatus.RECORD_TIME, ms, videoTime))
            } else {
                emitEvent(CaptureEvent.CameraCaptureEvent(CaptureEvent.CaptureStatus.RECORD_TIME, recordTime = ms))
            }
        }

        override fun onCaptureCount(count: Int) {
            emitEvent(CaptureEvent.CameraCaptureEvent(CaptureEvent.CaptureStatus.CAPTURE_COUNT, captureCount = count))
        }

        override fun onPreviewStreamParamsChanged() {
            previewParamsController.onPreviewStreamParamsChanged()
        }
    }

    init {
        // Match old init order: register listeners → lock screen → init capture.
        adapter.registerListeners(cameraCallbacks)
        adapter.setLockScreen(true)
        connectionController.initCapture()
    }

    // -------------------------------------------------------------------------
    // Public API — consumed by CaptureActivity
    // -------------------------------------------------------------------------

    /**
     * True when the current mode is a single-click photo action
     * (not interval/star-lapse, which toggle like video).
     */
    val isSingleClickAction: Boolean
        get() = cameraOfflineData.currentCaptureMode.let {
            it.isPhotoMode && it !in listOf(
                CaptureMode.INTERVAL_SHOOTING,
                CaptureMode.STARLAPSE_SHOOTING
            )
        }

    fun getCaptureSettingSupportValueList(captureSetting: CaptureSetting): List<Any> {
        return adapter.supportValueList(cameraOfflineData.currentCaptureMode, captureSetting)
    }

    /**
     * Builds the SDK params for [InstaCapturePlayerView.prepare].
     * Task 7 will relocate this to PlayerViewSink; kept here verbatim so the Activity compiles.
     */
    fun getCaptureParams(): CaptureParamsBuilderV2 {
        return CaptureParamsBuilderV2().apply {
            this.stabCacheFrameNum = Pref.getStabCacheFrameNum()
            this.setStabType(InstaStabType.STAB_TYPE_OFF)
        }
    }

    fun switchCaptureMode(position: Int) {
        connectionController.switchCaptureMode(position, cameraOfflineData)
    }

    fun startCapture() {
        controlController.startCapture(cameraOfflineData.currentCaptureMode)
    }

    fun closePreviewStream() {
        connectionController.closePreviewStream()
    }

    /**
     * Reads current player state and delegates to [PreviewParamsController].
     * The SDK [InstaCapturePlayerView] is read here at the VM boundary;
     * Task 7's PlayerViewSink will absorb this read.
     */
    fun cameraPreviewStreamParamsChanged(playerView: InstaCapturePlayerView) {
        val mode = cameraOfflineData.currentCaptureMode
        val currentCrop = playerView.windowCropInfo?.let { w ->
            CaptureWindowCrop(w.srcWidth, w.srcHeight, w.desWidth, w.desHeight, w.offsetX, w.offsetY)
        }
        val currentResolution = StreamResolution(
            playerView.previewWidth,
            playerView.previewHeight,
            playerView.previewFps,
        )
        previewParamsController.cameraPreviewStreamParamsChanged(
            mode = mode,
            isPlaying = playerView.isPlaying,
            currentCrop = currentCrop,
            currentStabOffset = playerView.stabOffset,
            currentResolution = currentResolution,
            currentFileType = playerView.fileType,
        )
    }

    // -------------------------------------------------------------------------
    // Transition: suppress BaseViewModel.onCameraStatusChanged
    // -------------------------------------------------------------------------

    /**
     * The old CaptureViewModel overrode this to emit CameraWiFiDisconnectEvent for WIFI
     * disconnect only, without calling super (so BaseEvent.CameraStatusChangedEvent was
     * never emitted on the capture screen).
     *
     * The adapter's ICameraChangedCallback already handles the wifi-disconnect signal and
     * forwards it to [cameraCallbacks.onWifiDisconnected] → emits CameraWiFiDisconnectEvent.
     * We override here to be a no-op so BaseViewModel does not additionally emit
     * CameraStatusChangedEvent (which the old VM never emitted on this screen).
     */
    override fun onCameraStatusChanged(enabled: Boolean, connectType: Int) {
        // Intentionally empty: the adapter callback handles wifi-disconnect.
        // Do NOT call super — BaseViewModel would emit CameraStatusChangedEvent,
        // which the original CaptureViewModel never did.
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCleared() {
        adapter.unregisterListeners()
        adapter.setLockScreen(false)
        super.onCleared()
    }
}
