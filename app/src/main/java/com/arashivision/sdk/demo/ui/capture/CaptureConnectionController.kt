package com.arashivision.sdk.demo.ui.capture

import com.arashivision.sdk.demo.base.BaseEvent
import com.arashivision.sdk.demo.base.EventStatus
import com.arashivision.sdk.demo.capture.CameraOfflineData
import com.arashivision.sdk.demo.ui.capture.camera.CameraSDKAdapter
import com.elvishew.xlog.Logger
import com.elvishew.xlog.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Owns the camera init / mode-switch / stream-close lifecycle.
 * All SDK calls are delegated to [adapter]; no direct SDK or Android framework
 * references live here, which makes the class testable on the JVM.
 *
 * @param adapter            SDK operations abstracted behind an interface.
 * @param scope              Coroutine scope used to launch async work
 *                           (pass viewModelScope in production, TestScope in tests).
 * @param emitEvent          Sink for events; the owning ViewModel forwards these to its
 *                           SharedFlow / LiveData.
 * @param onOfflineDataReady Called once [CameraOfflineData] has been constructed during
 *                           [initCapture], so the owning ViewModel can hold a reference.
 * @param setOfflineCaptureSettingValueToCamera
 *                           Old-flow settings push; provided by the owning ViewModel
 *                           because it needs access to [CameraOfflineData] and [adapter]
 *                           to build the values map.
 * @param createOfflineData  Factory for [CameraOfflineData]; defaults to the real
 *                           constructor.  Inject a mock in unit tests to avoid SDK calls
 *                           in the CameraOfflineData constructor.
 */
class CaptureConnectionController(
    private val adapter: CameraSDKAdapter,
    private val scope: CoroutineScope,
    private val emitEvent: (BaseEvent) -> Unit,
    private val onOfflineDataReady: (CameraOfflineData) -> Unit,
    private val setOfflineCaptureSettingValueToCamera: suspend () -> Unit,
    private val createOfflineData: () -> CameraOfflineData = { CameraOfflineData() },
) {

    private val logger: Logger by lazy {
        XLog.tag(CaptureConnectionController::class.java.simpleName).build()
    }

    /** Run the full camera-init sequence. Mirrors VM.initCapture() verbatim. */
    fun initCapture() {
        logger.d("initCapture function invoke")
        emitEvent(CaptureEvent.InitCaptureEvent(EventStatus.START))
        scope.launch {
            // 1. Check panorama sensor
            emitEvent(CaptureEvent.InitCaptureEvent(EventStatus.PROGRESS, CaptureEvent.InitStep.CHECK_SENSOR))
            if (!adapter.ensurePanoramaSensor()) {
                emitEvent(CaptureEvent.InitCaptureEvent(EventStatus.FAILED, CaptureEvent.InitStep.CHECK_SENSOR))
                return@launch
            }

            // 2. Fetch camera options (first pass)
            emitEvent(CaptureEvent.InitCaptureEvent(EventStatus.PROGRESS, CaptureEvent.InitStep.FETCH_CAMERA_OPTIONS))
            if (!adapter.fetchCameraOptions()) {
                emitEvent(CaptureEvent.InitCaptureEvent(EventStatus.FAILED, CaptureEvent.InitStep.FETCH_CAMERA_OPTIONS))
                return@launch
            }

            // 3. Init support config (adapter handles network bind/unbind internally;
            //    returns false if cameraNet is null OR if the SDK call fails).
            emitEvent(CaptureEvent.InitCaptureEvent(EventStatus.PROGRESS, CaptureEvent.InitStep.INIT_SUPPORT_CONFIG))
            if (!adapter.initSupportConfig()) {
                emitEvent(CaptureEvent.InitCaptureEvent(EventStatus.FAILED, CaptureEvent.InitStep.INIT_SUPPORT_CONFIG))
                return@launch
            }

            // 4. Create offline data and hand it to the VM
            logger.d("initCapture create CameraOfflineData object")
            val offlineData = createOfflineData()
            onOfflineDataReady(offlineData)

            // 5. Open preview stream
            emitEvent(CaptureEvent.InitCaptureEvent(EventStatus.PROGRESS, CaptureEvent.InitStep.OPEN_PREVIEW_STREAM))
            if (!adapter.openPreviewStream()) {
                emitEvent(CaptureEvent.InitCaptureEvent(EventStatus.FAILED, CaptureEvent.InitStep.OPEN_PREVIEW_STREAM))
                return@launch
            }

            // 6. Old-flow: push offline settings to camera after preview stream is open
            if (!adapter.supportsNewCaptureControlFlow) {
                setOfflineCaptureSettingValueToCamera()
            }

            // 7. Re-fetch options (some params change after preview opens)
            adapter.fetchCameraOptions()

            // 8. Emit success
            emitEvent(
                CaptureEvent.InitCaptureEvent(
                    status = EventStatus.SUCCESS,
                    captureModeList = adapter.supportCaptureModes,
                    currentCaptureMode = offlineData.currentCaptureMode,
                )
            )
        }
    }

    /**
     * Switch the active capture mode by list position. Mirrors VM.switchCaptureMode() verbatim,
     * but takes [offlineData] as a parameter (the VM owns that instance).
     */
    fun switchCaptureMode(position: Int, offlineData: CameraOfflineData) {
        emitEvent(CaptureEvent.SwitchCaptureModeEvent(EventStatus.START))
        scope.launch {
            if (position > adapter.supportCaptureModes.size - 1) {
                emitEvent(CaptureEvent.SwitchCaptureModeEvent(EventStatus.FAILED))
                return@launch
            }
            val captureMode = adapter.supportCaptureModes[position]
            if (!offlineData.setCaptureMode(captureMode)) {
                emitEvent(CaptureEvent.SwitchCaptureModeEvent(EventStatus.FAILED))
                return@launch
            }
            if (!adapter.supportsNewCaptureControlFlow) {
                setOfflineCaptureSettingValueToCamera()
            }
            emitEvent(CaptureEvent.SwitchCaptureModeEvent(EventStatus.SUCCESS))
        }
    }

    /** Close the preview stream. Listener cleanup is handled by the adapter. */
    fun closePreviewStream() {
        adapter.closePreviewStream()
    }
}
