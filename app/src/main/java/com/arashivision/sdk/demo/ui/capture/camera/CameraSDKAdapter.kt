package com.arashivision.sdk.demo.ui.capture.camera

import com.arashivision.sdkcamera.camera.model.CaptureMode
import com.arashivision.sdkcamera.camera.model.CaptureSetting

/** Domain wrapper for the player preview stream resolution (hides StreamResolution). */
data class StreamResolution(val width: Int, val height: Int, val fps: Int)

/** Domain wrapper for window-crop parameters (hides AssetInfo + WindowCropInfo fields). */
data class CaptureWindowCrop(
    val srcWidth: Int, val srcHeight: Int,
    val dstWidth: Int, val dstHeight: Int,
    val offsetX: Int, val offsetY: Int
)

/** Domain wrapper for player offsets; carries the full V1/V2/V3/V6 set so the player sink
 *  can rebuild an SDK OffsetData identical to InstaCapturePlayerView.getPlayerOffsetData(...). */
data class PlayerOffsets(
    val offsetV1: String,
    val offsetV2: String,
    val offsetV3: String,
    val offsetV6: String,
)

/** Result of a preview-param recomputation; what the player should update. */
data class PreviewUpdateInputs(
    val windowCrop: CaptureWindowCrop?,
    val playerOffset: PlayerOffsets?,
    val stabOffset: String,
    val resolution: StreamResolution?
)

/**
 * Port over the Insta360 SDK for the capture screen. Hides InstaCameraManager, supportConfig,
 * the SDK callback interfaces and the SDK rendering types behind suspend funcs + domain wrappers.
 * CaptureMode/CaptureSetting stay in signatures — they are domain enums already.
 */
interface CameraSDKAdapter {
    // --- lifecycle / connection ---
    val isWifiConnected: Boolean
    val supportsNewCaptureControlFlow: Boolean
    val supportCaptureModes: List<CaptureMode>

    fun setLockScreen(locked: Boolean)
    fun registerListeners(callbacks: CameraCallbacks)
    fun unregisterListeners()

    suspend fun ensurePanoramaSensor(): Boolean
    suspend fun fetchCameraOptions(): Boolean
    suspend fun initSupportConfig(): Boolean
    suspend fun openPreviewStream(): Boolean
    fun closePreviewStream()
    suspend fun reopenPreviewStream(): Boolean
    fun applyStreamEncode()

    // --- settings ---
    fun supportValueList(mode: CaptureMode, setting: CaptureSetting): List<Any>
    fun supportSettingsForMode(mode: CaptureMode): List<CaptureSetting>
    suspend fun commitSettings(mode: CaptureMode, values: Map<CaptureSetting, Any>): Boolean

    // --- capture control ---
    val isSdCardEnabled: Boolean
    fun isCameraWorking(mode: CaptureMode? = null): Boolean
    fun startRecord(mode: CaptureMode)
    fun stopRecord(mode: CaptureMode)
    fun takePhoto(mode: CaptureMode)
    fun startLive(rtmp: String, listener: LiveCallbacks)
    fun stopLive()

    // --- preview-param change (hides AssetInfo/supportConfig/InstaCapturePlayerView reads) ---
    val isPreviewOpened: Boolean
    /** True when the camera is currently encoding H.265 (mirrors instaCameraManager.isH265StreamEncode). */
    val isStreamH265: Boolean
    fun isEncodeMismatch(): Boolean
    fun isPreviewFileTypeChanged(mode: CaptureMode, currentFileType: Int): Boolean
    /**
     * Recompute what the player should update. [currentCrop]/[currentStabOffset]/[current] are the
     * player's current values; returns null when nothing relevant changed.
     */
    fun previewUpdateInputs(
        mode: CaptureMode,
        currentCrop: CaptureWindowCrop?,
        currentStabOffset: String?,
        current: StreamResolution?
    ): PreviewUpdateInputs?

    // --- timelapse readout (pure duration math lives in :lib TimelapseMath) ---
    fun timelapseIntervalNative(mode: CaptureMode): Int
    fun timelapseFps(mode: CaptureMode): Int
}

/** Capture/preview status callbacks, SDK-free. */
interface CameraCallbacks {
    fun onWifiDisconnected()
    fun onPreviewOpened()
    fun onPreviewError()
    fun onCaptureStarting()
    fun onCaptureWorking()
    fun onCaptureStopping()
    fun onCaptureFinish()
    fun onCaptureError(code: Int)
    fun onCaptureTime(ms: Long)
    fun onCaptureCount(count: Int)
    fun onPreviewStreamParamsChanged()
}

/** Live streaming callbacks, SDK-free. */
interface LiveCallbacks {
    fun onStarted()
    fun onFinished()
    fun onError(code: Int, desc: String?)
}
