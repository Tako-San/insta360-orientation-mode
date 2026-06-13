package com.arashivision.sdk.demo.ui.capture.camera

import com.arashivision.sdk.demo.ext.connectivityManager
import com.arashivision.sdk.demo.ext.instaCameraManager
import com.arashivision.sdk.demo.ext.setCaptureSettingValue
import com.arashivision.sdk.demo.util.NetworkManager
import com.arashivision.sdkcamera.camera.InstaCameraManager
import com.arashivision.sdkcamera.camera.callback.ICameraChangedCallback
import com.arashivision.sdkcamera.camera.callback.ICameraOperateCallback
import com.arashivision.sdkcamera.camera.callback.ICaptureStatusListener
import com.arashivision.sdkcamera.camera.callback.ICaptureSupportConfigCallback
import com.arashivision.sdkcamera.camera.callback.ILiveStatusListener
import com.arashivision.sdkcamera.camera.callback.IPreviewStatusListener
import com.arashivision.sdkcamera.camera.model.CaptureMode
import com.arashivision.sdkcamera.camera.model.CaptureSetting
import com.arashivision.sdkcamera.camera.model.SensorMode
import com.arashivision.sdkmedia.player.capture.InstaCapturePlayerView
import com.elvishew.xlog.Logger
import com.elvishew.xlog.XLog
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Objects
import kotlin.coroutines.resume

/**
 * Real Insta360 SDK implementation of [CameraSDKAdapter].
 *
 * All SDK calls are ported verbatim from CaptureViewModel; no logic is added or changed.
 * The adapter owns the SDK listener registrations and the stream-state flags that were
 * previously private fields on the ViewModel.
 */
class InstaCameraSDKAdapter : CameraSDKAdapter {

    private val logger: Logger by lazy { XLog.tag("InstaCameraSDKAdapter").build() }

    // --- internal state ported from CaptureViewModel ---
    private var openPreviewStreamListener: ((Boolean) -> Unit)? = null
    private var isFetchingOptions: Boolean = false
    private var isStreamOpened: Boolean = false

    private var callbacks: CameraCallbacks? = null

    // --- IPreviewStatusListener forwarded to CameraCallbacks ---
    private val previewStatusListener = object : IPreviewStatusListener {
        override fun onOpening() {
            logger.d("onOpening")
        }

        override fun onOpened() {
            logger.d("onOpened")
            isStreamOpened = true
            openPreviewStreamListener?.invoke(true)
            callbacks?.onPreviewOpened()
        }

        override fun onIdle() {
            logger.d("onIdle")
            isStreamOpened = false
        }

        override fun onError() {
            logger.d("onError")
            isStreamOpened = false
            openPreviewStreamListener?.invoke(false)
            callbacks?.onPreviewError()
        }
    }

    // --- ICaptureStatusListener forwarded to CameraCallbacks ---
    private val captureStatusListener = object : ICaptureStatusListener {
        override fun onCaptureStarting() {
            callbacks?.onCaptureStarting()
        }

        override fun onCaptureWorking() {
            callbacks?.onCaptureWorking()
        }

        override fun onCaptureStopping() {
            callbacks?.onCaptureStopping()
        }

        override fun onCaptureFinish(paths: Array<String>?) {
            callbacks?.onCaptureFinish()
        }

        override fun onCaptureError(i: Int) {
            callbacks?.onCaptureError(i)
        }

        override fun onCaptureTimeChanged(captureTime: Long) {
            callbacks?.onCaptureTime(captureTime)
        }

        override fun onCaptureCountChanged(captureCount: Int) {
            callbacks?.onCaptureCount(captureCount)
        }
    }

    // --- ICameraChangedCallback for connection-state and stream-param changes ---
    // onCameraStatusChanged and onCameraPreviewStreamParamsChanged live on ICameraChangedCallback,
    // registered via registerCameraChangedCallback (BaseViewModel wires the base; the adapter
    // registers its own instance for the capture-specific forwarding).
    private val cameraChangedCallback = object : ICameraChangedCallback {
        override fun onCameraStatusChanged(enabled: Boolean, connectType: Int) {
            if (connectType == InstaCameraManager.CONNECT_TYPE_WIFI && !enabled) {
                callbacks?.onWifiDisconnected()
            }
        }

        override fun onCameraPreviewStreamParamsChanged(isPreviewStreamParamsChanged: Boolean) {
            if (instaCameraManager.cameraConnectedType != InstaCameraManager.CONNECT_TYPE_WIFI) return
            if (isFetchingOptions) return
            if (!isPreviewStreamParamsChanged) return
            callbacks?.onPreviewStreamParamsChanged()
        }
    }

    // --- lifecycle / connection ---

    override val isWifiConnected: Boolean
        get() = instaCameraManager.cameraConnectedType == InstaCameraManager.CONNECT_TYPE_WIFI

    override val supportsNewCaptureControlFlow: Boolean
        get() = instaCameraManager.supportConfig.supportNewCaptureControlFlow()

    override val supportCaptureModes: List<CaptureMode>
        get() = instaCameraManager.supportCaptureMode

    override fun setLockScreen(locked: Boolean) {
        instaCameraManager.setCameraLockScreen(locked)
    }

    override fun registerListeners(callbacks: CameraCallbacks) {
        this.callbacks = callbacks
        instaCameraManager.setPreviewStatusChangedListener(previewStatusListener)
        instaCameraManager.setCaptureStatusListener(captureStatusListener)
        instaCameraManager.registerCameraChangedCallback(cameraChangedCallback)
    }

    override fun unregisterListeners() {
        instaCameraManager.setPreviewStatusChangedListener(null)
        instaCameraManager.setCaptureStatusListener(null)
        instaCameraManager.unregisterCameraChangedCallback(cameraChangedCallback)
        callbacks = null
    }

    override suspend fun ensurePanoramaSensor(): Boolean {
        logger.d("checkCameraSensorMode function invoke")

        if (instaCameraManager.currentSensorMode == SensorMode.PANORAMA) {
            logger.d("checkCameraSensorMode already panorama")
            return true
        }
        return suspendCancellableCoroutine {
            instaCameraManager.switchPanoramaSensorMode(object : ICameraOperateCallback {
                override fun onSuccessful() = it.resume(true)
                override fun onFailed() = it.resume(false)
                override fun onCameraConnectError() = it.resume(false)
            })
        }
    }

    override suspend fun fetchCameraOptions(): Boolean {
        logger.d("fetchCameraOptions function invoke")
        isFetchingOptions = true
        val result = suspendCancellableCoroutine {
            instaCameraManager.fetchCameraOptions(object : ICameraOperateCallback {
                override fun onSuccessful() = it.resume(true)
                override fun onFailed() = it.resume(false)
                override fun onCameraConnectError() = it.resume(false)
            })
        }
        isFetchingOptions = false
        return result
    }

    override suspend fun initSupportConfig(): Boolean {
        // http communication requires camera network binding first
        NetworkManager.cameraNet?.let { connectivityManager.bindProcessToNetwork(it) } ?: return false
        return suspendCancellableCoroutine {
            logger.d("initCameraSupportConfig function invoke")
            instaCameraManager.initCameraSupportConfig(object : ICaptureSupportConfigCallback {
                override fun onComplete() {
                    logger.d("initCameraSupportConfig success")
                    // http communication done — release camera network binding
                    connectivityManager.bindProcessToNetwork(null)
                    it.resume(true)
                }

                override fun onFailed(s: String) {
                    logger.d("initCameraSupportConfig failed : $s")
                    // http communication done — release camera network binding
                    connectivityManager.bindProcessToNetwork(null)
                    it.resume(false)
                }
            })
        }
    }

    override suspend fun openPreviewStream(): Boolean {
        return suspendCancellableCoroutine {
            logger.d("openPreviewStream function invoke")
            openPreviewStreamListener = { success ->
                logger.d("openPreviewStream result : $success")
                instaCameraManager.setStreamEncode()
                it.resume(success)
                openPreviewStreamListener = null
            }
            instaCameraManager.startPreviewStream(InstaCameraManager.PREVIEW_TYPE_NORMAL)
        }
    }

    override fun closePreviewStream() {
        instaCameraManager.closePreviewStream()
    }

    override suspend fun reopenPreviewStream(): Boolean {
        instaCameraManager.closePreviewStream()
        return openPreviewStream()
    }

    override fun applyStreamEncode() {
        instaCameraManager.setStreamEncode()
    }

    // --- settings ---

    override fun supportValueList(mode: CaptureMode, setting: CaptureSetting): List<Any> {
        return when (setting) {
            CaptureSetting.EXPOSURE -> instaCameraManager.getSupportExposureList(mode)
                .sortedBy { it.nativeValue }

            CaptureSetting.EV -> instaCameraManager.getSupportEVList(mode)
                .sortedBy { it.nativeValue }

            CaptureSetting.EV_INTERVAL -> instaCameraManager.getSupportEVIntervalList(mode)
                .sortedBy { it.nativeValue }

            CaptureSetting.SHUTTER -> instaCameraManager.getSupportShutterList(mode)
                .sortedBy { it.nativeValue }

            CaptureSetting.SHUTTER_MODE -> instaCameraManager.getSupportShutterModeList(mode)
                .sortedBy { it.nativeValue }

            CaptureSetting.ISO -> instaCameraManager.getSupportISOList(mode)
                .sortedBy { it.nativeValue }

            CaptureSetting.ISO_TOP_LIMIT -> instaCameraManager.getSupportISOTopLimitList(mode)
                .sortedBy { it.nativeValue }

            CaptureSetting.RECORD_RESOLUTION -> instaCameraManager.getSupportRecordResolutionList(
                mode
            ).sortedBy { it.nativeValue }

            CaptureSetting.PHOTO_RESOLUTION -> instaCameraManager.getSupportPhotoResolutionList(
                mode
            ).sortedBy { it.nativeValue }

            CaptureSetting.WB -> instaCameraManager.getSupportWBList(mode)
                .sortedBy { it.nativeValue }

            CaptureSetting.AEB -> instaCameraManager.getSupportAEBList(mode)
                .sortedBy { it.nativeValue }

            CaptureSetting.INTERVAL -> instaCameraManager.getSupportIntervalList(mode)
                .sortedBy { it.nativeValue }

            CaptureSetting.GAMMA_MODE -> instaCameraManager.getSupportGammaModeList(mode)
                .sortedBy { it.nativeValue }

            CaptureSetting.RAW_TYPE -> instaCameraManager.getSupportRawTypeList(mode)
                .sortedBy { it.nativeValue }

            CaptureSetting.RECORD_DURATION -> instaCameraManager.getSupportRecordDurationList(
                mode
            ).sortedBy { it.nativeValue }

            CaptureSetting.DARK_EIS_ENABLE -> instaCameraManager.getSupportDarkEisList(mode)
                .sortedBy { it.nativeValue }

            CaptureSetting.PANO_EXPOSURE_MODE -> instaCameraManager.getSupportPanoExposureList(
                mode
            ).sortedBy { it.nativeValue }

            CaptureSetting.BURST_CAPTURE -> instaCameraManager.getSupportBurstCaptureList(
                mode
            ).sortedBy { it.time }

            CaptureSetting.INTERNAL_SPLICING -> instaCameraManager.getSupportInternalSplicingList(
                mode
            ).sortedBy { it.nativeValue }

            CaptureSetting.HDR_STATUS -> instaCameraManager.getSupportHdrStatusList(mode)
                .sortedBy { it.nativeValue }

            CaptureSetting.PHOTO_HDR_TYPE -> instaCameraManager.getSupportPhotoHdrTypeList(
                mode
            ).sortedBy { it.nativeValue }

            CaptureSetting.LIVE_BITRATE -> instaCameraManager.getSupportLiveBitrateList(mode)
                .sortedBy { it.nativeValue }

            CaptureSetting.I_LOG -> instaCameraManager.getSupportILogStatusList(mode)
                .sortedBy { it.nativeValue }
        }
    }

    override fun supportSettingsForMode(mode: CaptureMode): List<CaptureSetting> {
        return instaCameraManager.getSupportCaptureSettingList(mode)
    }

    override suspend fun commitSettings(mode: CaptureMode, values: Map<CaptureSetting, Any>): Boolean {
        logger.d("commitSettings function invoke")
        return suspendCancellableCoroutine {
            logger.d("commitSettings current capture mode is $mode")
            with(instaCameraManager) {
                // batch-set all values
                beginSettingOptions()
                values.forEach { (setting, value) ->
                    logger.d("$setting = $value")
                    setCaptureSettingValue(mode, setting, value)
                }
                if (instaCameraManager.supportConfig.supportNewCaptureControlFlow()) {
                    commitSettingOptions { code -> it.resume(code == 0) }
                } else {
                    commitSettingOptions(null)
                    it.resume(true)
                }
            }
        }
    }

    // --- capture control ---

    override val isSdCardEnabled: Boolean
        get() = instaCameraManager.isSdCardEnabled

    override fun isCameraWorking(mode: CaptureMode?): Boolean {
        return if (mode == null) instaCameraManager.isCameraWorking
        else instaCameraManager.isCameraWorking(mode)
    }

    override fun startRecord(mode: CaptureMode) {
        when (mode) {
            CaptureMode.RECORD_NORMAL -> instaCameraManager.startNormalRecord()
            CaptureMode.BULLETTIME -> instaCameraManager.startBulletTime()
            CaptureMode.TIMELAPSE -> instaCameraManager.startTimeLapse()
            CaptureMode.HDR_RECORD -> instaCameraManager.startHDRRecord()
            CaptureMode.TIME_SHIFT -> instaCameraManager.startTimeShift()
            CaptureMode.LOOPER_RECORDING -> instaCameraManager.startLooperRecord()
            CaptureMode.SUPER_RECORD -> instaCameraManager.startSuperRecord()
            CaptureMode.SLOW_MOTION -> instaCameraManager.startSlowMotionRecord()
            CaptureMode.SELFIE_RECORD -> instaCameraManager.startSelfieRecord()
            CaptureMode.PURE_RECORD -> instaCameraManager.startPureRecord()
            CaptureMode.INTERVAL_SHOOTING -> instaCameraManager.startIntervalShooting()
            CaptureMode.STARLAPSE_SHOOTING -> instaCameraManager.startStarLapseShooting()
            else -> {}
        }
    }

    override fun stopRecord(mode: CaptureMode) {
        when (mode) {
            CaptureMode.RECORD_NORMAL -> instaCameraManager.stopNormalRecord()
            CaptureMode.BULLETTIME -> instaCameraManager.stopBulletTime()
            CaptureMode.TIMELAPSE -> instaCameraManager.stopTimeLapse()
            CaptureMode.HDR_RECORD -> instaCameraManager.stopHDRRecord()
            CaptureMode.TIME_SHIFT -> instaCameraManager.stopTimeShift()
            CaptureMode.LOOPER_RECORDING -> instaCameraManager.stopLooperRecord()
            CaptureMode.SUPER_RECORD -> instaCameraManager.stopSuperRecord()
            CaptureMode.SLOW_MOTION -> instaCameraManager.stopSlowMotionRecord()
            CaptureMode.SELFIE_RECORD -> instaCameraManager.stopSelfieRecord()
            CaptureMode.PURE_RECORD -> instaCameraManager.stopPureRecord()
            CaptureMode.INTERVAL_SHOOTING -> instaCameraManager.stopIntervalShooting()
            CaptureMode.STARLAPSE_SHOOTING -> instaCameraManager.stopStarLapseShooting()
            else -> {}
        }
    }

    override fun takePhoto(mode: CaptureMode) {
        when (mode) {
            CaptureMode.CAPTURE_NORMAL -> instaCameraManager.startNormalCapture()
            CaptureMode.HDR_CAPTURE -> instaCameraManager.startHDRCapture()
            CaptureMode.NIGHT_SCENE -> instaCameraManager.startNightScene()
            CaptureMode.BURST -> instaCameraManager.startBurstCapture()
            else -> {}
        }
    }

    override fun startLive(rtmp: String, listener: LiveCallbacks) {
        instaCameraManager.startLive(
            rtmp,
            -1,
            object : ILiveStatusListener {
                override fun onLivePushStarted() {
                    logger.d("onLivePushStarted")
                    listener.onStarted()
                }

                override fun onLivePushFinished() {
                    logger.d("onLivePushFinished")
                    listener.onFinished()
                }

                override fun onLivePushError(error: Int, desc: String?) {
                    logger.d("onLivePushError  error=$error   desc=$desc")
                    listener.onError(error, desc)
                }

                override fun onLiveFpsUpdate(fps: Int) {
                    logger.d("onLiveFpsUpdate")
                }
            }
        )
    }

    override fun stopLive() {
        instaCameraManager.stopLive()
    }

    // --- preview-param change ---

    override val isPreviewOpened: Boolean
        get() = instaCameraManager.previewStatus == InstaCameraManager.PREVIEW_STATUS_OPENED

    override fun isEncodeMismatch(): Boolean {
        return isStreamOpened && (instaCameraManager.isH265StreamEncode != (instaCameraManager.videoEncodeType == InstaCameraManager.ENCODE_265))
    }

    override fun isPreviewFileTypeChanged(mode: CaptureMode, currentFileType: Int): Boolean {
        val isFlowStateOn = instaCameraManager.isFlowstateOn(mode)
        return instaCameraManager.supportConfig.getPreviewFileType(mode, isFlowStateOn) != currentFileType
    }

    override fun previewUpdateInputs(
        mode: CaptureMode,
        currentCrop: CaptureWindowCrop?,
        currentStabOffset: String?,
        current: StreamResolution?
    ): PreviewUpdateInputs? {
        val isFlowStateOn = instaCameraManager.isFlowstateOn(mode)

        val assetInfo = instaCameraManager.supportConfig.getConvertAssetInfo(mode, isFlowStateOn)

        val assetInfoStab = instaCameraManager.supportConfig.getStabConvertAssetInfo(mode, isFlowStateOn)

        val stabOffset = InstaCapturePlayerView.getPlayerOffsetData(assetInfoStab).offsetV1

        // Check if window crop needs to be updated
        val shouldUpdateWindowCrop = run {
            if (currentCrop == null) return@run true
            assetInfo.cropWindowSrcWidth != currentCrop.srcWidth
                    || assetInfo.cropWindowSrcHeight != currentCrop.srcHeight
                    || assetInfo.cropWindowDstWidth != currentCrop.dstWidth
                    || assetInfo.cropWindowDstHeight != currentCrop.dstHeight
                    || assetInfo.cropOffsetX != currentCrop.offsetX
                    || assetInfo.cropOffsetY != currentCrop.offsetY
                    || !Objects.equals(stabOffset, currentStabOffset)
        }

        val windowCrop = if (shouldUpdateWindowCrop) {
            CaptureWindowCrop(
                srcWidth = assetInfo.cropWindowSrcWidth,
                srcHeight = assetInfo.cropWindowSrcHeight,
                dstWidth = assetInfo.cropWindowDstWidth,
                dstHeight = assetInfo.cropWindowDstHeight,
                offsetX = assetInfo.cropOffsetX,
                offsetY = assetInfo.cropOffsetY
            )
        } else null

        val playerOffset = if (shouldUpdateWindowCrop) {
            PlayerOffsets(InstaCapturePlayerView.getPlayerOffsetData(assetInfo).offsetV1)
        } else null

        // Check if preview resolution changed
        val resolution = instaCameraManager.curFirstStreamResolution?.let {
            val r = StreamResolution(it.width, it.height, it.fps)
            if (r != current) r else null
        }

        // Return null when nothing changed
        if (windowCrop == null && playerOffset == null && resolution == null) return null

        return PreviewUpdateInputs(windowCrop, playerOffset, stabOffset, resolution)
    }

    // --- timelapse readout ---

    override fun timelapseIntervalNative(mode: CaptureMode): Int {
        return instaCameraManager.getInterval(mode).nativeValue
    }

    override fun timelapseFps(mode: CaptureMode): Int {
        return instaCameraManager.getRecordResolution(mode).fps
    }
}
