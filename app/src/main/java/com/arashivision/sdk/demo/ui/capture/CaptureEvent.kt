package com.arashivision.sdk.demo.ui.capture

import com.arashivision.sdk.demo.base.BaseEvent
import com.arashivision.sdk.demo.base.EventStatus
import com.arashivision.sdk.demo.ui.capture.camera.CaptureWindowCrop
import com.arashivision.sdk.demo.ui.capture.camera.PlayerOffsets
import com.arashivision.sdk.demo.ui.capture.camera.StreamResolution
import com.arashivision.sdkcamera.camera.model.CaptureMode

interface CaptureEvent : BaseEvent {

    object CameraWiFiDisconnectEvent : CaptureEvent

    enum class InitStep {
        CHECK_SENSOR,
        INIT_SUPPORT_CONFIG,
        FETCH_CAMERA_OPTIONS,
        OPEN_PREVIEW_STREAM
    }

    data class InitCaptureEvent(
        var status: EventStatus,
        var step: InitStep? = null,
        var captureModeList: List<CaptureMode>? = null,
        var currentCaptureMode: CaptureMode? = null
    ) : CaptureEvent

    data class SwitchCaptureModeEvent(var status: EventStatus) : CaptureEvent

    object CameraPreviewStreamParamsChangedEvent : CaptureEvent

    object RestartPlayerViewEvent : CaptureEvent

    class UpdatePlayerViewParamsEvent(
        var windowCrop: CaptureWindowCrop? = null,
        var playerOffset: PlayerOffsets? = null,
        var stabOffset: String? = null,
        var resolution: StreamResolution? = null,
    ) : CaptureEvent


    enum class CaptureStatus {
        SD_DISABLE,
        STARTING,
        WORKING,
        STOPPING,
        FINISH,
        RECORD_TIME,
        CAPTURE_COUNT,
        ERROR
    }

    // 相机拍摄事件
    class CameraCaptureEvent(
        var status: CaptureStatus = CaptureStatus.ERROR,
        var recordTime: Long = -1,
        var videoTime: Long = -1,
        var captureCount: Int = -1,
        var errorCode: Int = -1
    ) : CaptureEvent

    enum class LiveStatus {
        RTMP_EMPTY,
        START_LIVE,
        STOP_LIVE,
        PUSH_STARTED,
        PUSH_FINISHED,
        PUSH_ERROR,
    }

    class CameraLiveEvent(var status: LiveStatus) : CaptureEvent
}
