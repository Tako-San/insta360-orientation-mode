package com.arashivision.sdk.demo.ui.capture

import com.arashivision.sdk.demo.base.BaseEvent
import com.arashivision.sdk.demo.ui.capture.camera.CameraSDKAdapter
import com.arashivision.sdk.demo.ui.capture.camera.LiveCallbacks
import com.arashivision.sdkcamera.camera.model.CaptureMode
import com.elvishew.xlog.XLog

/**
 * Handles record/photo/live capture control, extracted from CaptureViewModel.
 *
 * The caller owns CameraOfflineData and passes currentCaptureMode on each call so
 * this controller stays free of Android/SDK state and is JVM-testable.
 *
 * @param adapter      SDK operations behind a testable interface.
 * @param emitEvent    Sends domain events to the caller (ViewModel → UI bus).
 * @param getLiveRtmp  Injected instead of calling Pref.getLiveRtmp() directly,
 *                     so the controller stays JVM-testable (Pref requires Android).
 */
class CaptureControlController(
    private val adapter: CameraSDKAdapter,
    private val emitEvent: (BaseEvent) -> Unit,
    private val getLiveRtmp: () -> String,
) {

    private val logger by lazy { XLog.tag("CaptureControlController").build() }

    /** Mirrors CaptureViewModel.isLiving. */
    private var isLiving = false

    // -------------------------------------------------------------------------
    // isSingleClickAction — ported verbatim from CaptureViewModel but
    // parameterized by the caller-supplied mode (no cameraOfflineData held here).
    // -------------------------------------------------------------------------

    private fun isSingleClickAction(mode: CaptureMode): Boolean =
        mode.isPhotoMode && mode !in listOf(
            CaptureMode.INTERVAL_SHOOTING,
            CaptureMode.STARLAPSE_SHOOTING
        )

    // -------------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------------

    /** Mirrors CaptureViewModel.startCapture(). */
    fun startCapture(currentCaptureMode: CaptureMode) {
        // Single-click photo logic
        if (isSingleClickAction(currentCaptureMode)) {
            takePhotos(currentCaptureMode)
            return
        }

        // Record / live logic — ported verbatim
        when {
            currentCaptureMode.isLiveMode && !isLiving -> startLive(currentCaptureMode)
            currentCaptureMode.isLiveMode && isLiving -> stopLive()
            !currentCaptureMode.isLiveMode && !adapter.isCameraWorking() -> startRecord(currentCaptureMode)
            !currentCaptureMode.isLiveMode && adapter.isCameraWorking() -> stopRecord(currentCaptureMode)
            else -> {}
        }
    }

    // -------------------------------------------------------------------------
    // Private methods — all verbatim from CaptureViewModel
    // -------------------------------------------------------------------------

    private fun startRecord(captureMode: CaptureMode) {
        if (isSingleClickAction(captureMode)) return
        if (!adapter.isSdCardEnabled) {
            emitEvent(CaptureEvent.CameraCaptureEvent(CaptureEvent.CaptureStatus.SD_DISABLE))
            return
        }
        adapter.startRecord(captureMode)
    }

    private fun takePhotos(captureMode: CaptureMode) {
        if (!isSingleClickAction(captureMode)) return
        adapter.takePhoto(captureMode)
    }

    private fun stopRecord(captureMode: CaptureMode) {
        if (isSingleClickAction(captureMode)) return
        adapter.stopRecord(captureMode)
    }

    private fun startLive(currentCaptureMode: CaptureMode) {
        if (!currentCaptureMode.isLiveMode) return
        val rtmp = getLiveRtmp()
        if (rtmp.isEmpty()) {
            emitEvent(CaptureEvent.CameraLiveEvent(CaptureEvent.LiveStatus.RTMP_EMPTY))
            return
        }
        emitEvent(CaptureEvent.CameraLiveEvent(CaptureEvent.LiveStatus.START_LIVE))
        adapter.startLive(
            rtmp,
            object : LiveCallbacks {
                override fun onStarted() {
                    logger.d("onLivePushStarted")
                    isLiving = true
                    emitEvent(CaptureEvent.CameraLiveEvent(CaptureEvent.LiveStatus.PUSH_STARTED))
                }

                override fun onFinished() {
                    logger.d("onLivePushFinished")
                    isLiving = false
                    emitEvent(CaptureEvent.CameraLiveEvent(CaptureEvent.LiveStatus.PUSH_FINISHED))
                }

                override fun onError(code: Int, desc: String?) {
                    logger.d("onLivePushError code=$code desc=$desc")
                    isLiving = false
                    emitEvent(CaptureEvent.CameraLiveEvent(CaptureEvent.LiveStatus.PUSH_ERROR))
                }
            }
        )
    }

    private fun stopLive() {
        emitEvent(CaptureEvent.CameraLiveEvent(CaptureEvent.LiveStatus.STOP_LIVE))
        adapter.stopLive()
    }
}
