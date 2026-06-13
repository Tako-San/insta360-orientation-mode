package com.arashivision.sdk.demo.ui.capture

import androidx.lifecycle.viewModelScope
import com.arashivision.orientation.capture.TimelapseMath
import com.arashivision.camera.options.CaptureResolution
import com.arashivision.graphicpath.render.source.AssetInfo
import com.arashivision.insta360.basemedia.asset.WindowCropInfo
import com.arashivision.sdk.demo.base.BaseViewModel
import com.arashivision.sdk.demo.base.EventStatus
import com.arashivision.sdk.demo.capture.CameraOfflineData
import com.arashivision.sdk.demo.ext.connectivityManager
import com.arashivision.sdk.demo.ext.instaCameraManager
import com.arashivision.sdk.demo.ext.setCaptureSettingValue
import com.arashivision.sdk.demo.pref.Pref
import com.arashivision.sdk.demo.ui.capture.CaptureEvent.CameraWiFiDisconnectEvent
import com.arashivision.sdk.demo.util.NetworkManager
import com.arashivision.sdkcamera.camera.InstaCameraManager
import com.arashivision.sdkcamera.camera.callback.ICameraOperateCallback
import com.arashivision.sdkcamera.camera.callback.ICaptureStatusListener
import com.arashivision.sdkcamera.camera.callback.ICaptureSupportConfigCallback
import com.arashivision.sdkcamera.camera.callback.ILiveStatusListener
import com.arashivision.sdkcamera.camera.callback.IPreviewStatusListener
import com.arashivision.sdkcamera.camera.model.CaptureMode
import com.arashivision.sdkcamera.camera.model.CaptureSetting
import com.arashivision.sdkcamera.camera.model.RecordResolution
import com.arashivision.sdkcamera.camera.model.SensorMode
import com.arashivision.sdkmedia.player.capture.CaptureParamsBuilderV2
import com.arashivision.sdkmedia.player.capture.InstaCapturePlayerView
import com.arashivision.sdkmedia.player.config.InstaStabType
import com.elvishew.xlog.Logger
import com.elvishew.xlog.XLog
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Objects
import kotlin.coroutines.resume

class CaptureViewModel : BaseViewModel(), IPreviewStatusListener, ICaptureStatusListener {

    private val logger: Logger = XLog.tag(CaptureViewModel::class.java.simpleName).build()

    private var openPreviewStreamListener: ((Boolean) -> Unit)? = null
    private var isFetchingOptions: Boolean = false
    private var isStreamOpened: Boolean = false

    lateinit var cameraOfflineData: CameraOfflineData
        private set

    val isSingleClickAction: Boolean
        get() = cameraOfflineData.currentCaptureMode.let {
            it.isPhotoMode && it !in listOf(
                CaptureMode.INTERVAL_SHOOTING,
                CaptureMode.STARLAPSE_SHOOTING
            )
        }

    private var isLiving = false

    init {
        instaCameraManager.setPreviewStatusChangedListener(this)
        instaCameraManager.setCaptureStatusListener(this)
        // 进入拍摄页锁屏，防止用户操作相机，引起参数不同步的问题
        instaCameraManager.setCameraLockScreen(true)

        initCapture()
    }

    fun getCaptureSettingSupportValueList(captureSetting: CaptureSetting): List<Any> {
        val captureMode = cameraOfflineData.currentCaptureMode
        return when (captureSetting) {
            CaptureSetting.EXPOSURE -> instaCameraManager.getSupportExposureList(captureMode)
                .sortedBy { it.nativeValue }

            CaptureSetting.EV -> instaCameraManager.getSupportEVList(captureMode)
                .sortedBy { it.nativeValue }

            CaptureSetting.EV_INTERVAL -> instaCameraManager.getSupportEVIntervalList(captureMode)
                .sortedBy { it.nativeValue }

            CaptureSetting.SHUTTER -> instaCameraManager.getSupportShutterList(captureMode)
                .sortedBy { it.nativeValue }

            CaptureSetting.SHUTTER_MODE -> instaCameraManager.getSupportShutterModeList(captureMode)
                .sortedBy { it.nativeValue }

            CaptureSetting.ISO -> instaCameraManager.getSupportISOList(captureMode)
                .sortedBy { it.nativeValue }

            CaptureSetting.ISO_TOP_LIMIT -> instaCameraManager.getSupportISOTopLimitList(captureMode)
                .sortedBy { it.nativeValue }

            CaptureSetting.RECORD_RESOLUTION -> instaCameraManager.getSupportRecordResolutionList(
                captureMode
            ).sortedBy { it.nativeValue }

            CaptureSetting.PHOTO_RESOLUTION -> instaCameraManager.getSupportPhotoResolutionList(
                captureMode
            ).sortedBy { it.nativeValue }

            CaptureSetting.WB -> instaCameraManager.getSupportWBList(captureMode)
                .sortedBy { it.nativeValue }

            CaptureSetting.AEB -> instaCameraManager.getSupportAEBList(captureMode)
                .sortedBy { it.nativeValue }

            CaptureSetting.INTERVAL -> instaCameraManager.getSupportIntervalList(captureMode)
                .sortedBy { it.nativeValue }

            CaptureSetting.GAMMA_MODE -> instaCameraManager.getSupportGammaModeList(captureMode)
                .sortedBy { it.nativeValue }

            CaptureSetting.RAW_TYPE -> instaCameraManager.getSupportRawTypeList(captureMode)
                .sortedBy { it.nativeValue }

            CaptureSetting.RECORD_DURATION -> instaCameraManager.getSupportRecordDurationList(
                captureMode
            ).sortedBy { it.nativeValue }

            CaptureSetting.DARK_EIS_ENABLE -> instaCameraManager.getSupportDarkEisList(captureMode)
                .sortedBy { it.nativeValue }

            CaptureSetting.PANO_EXPOSURE_MODE -> instaCameraManager.getSupportPanoExposureList(
                captureMode
            ).sortedBy { it.nativeValue }

            CaptureSetting.BURST_CAPTURE -> instaCameraManager.getSupportBurstCaptureList(
                captureMode
            ).sortedBy { it.time }

            CaptureSetting.INTERNAL_SPLICING -> instaCameraManager.getSupportInternalSplicingList(
                captureMode
            ).sortedBy { it.nativeValue }

            CaptureSetting.HDR_STATUS -> instaCameraManager.getSupportHdrStatusList(captureMode)
                .sortedBy { it.nativeValue }

            CaptureSetting.PHOTO_HDR_TYPE -> instaCameraManager.getSupportPhotoHdrTypeList(
                captureMode
            ).sortedBy { it.nativeValue }

            CaptureSetting.LIVE_BITRATE -> instaCameraManager.getSupportLiveBitrateList(captureMode)
                .sortedBy { it.nativeValue }

            CaptureSetting.I_LOG -> instaCameraManager.getSupportILogStatusList(captureMode)
                .sortedBy { it.nativeValue }
        }
    }

    fun getCaptureParams(): CaptureParamsBuilderV2 {
        return CaptureParamsBuilderV2().apply {
            this.stabCacheFrameNum = Pref.getStabCacheFrameNum()
            this.setStabType(InstaStabType.STAB_TYPE_OFF)
        }
    }

    private fun initCapture() {
        logger.d("initCapture function invoke")
        emitEvent(CaptureEvent.InitCaptureEvent(EventStatus.START))
        viewModelScope.launch {
            // 检查镜头是否为全景，如果不是则切换至全景
            emitEvent(CaptureEvent.InitCaptureEvent(EventStatus.PROGRESS, CaptureEvent.InitStep.CHECK_SENSOR))
            val checkCameraSensorResult = checkCameraSensorMode()
            if (!checkCameraSensorResult) {
                emitEvent(CaptureEvent.InitCaptureEvent(EventStatus.FAILED, CaptureEvent.InitStep.CHECK_SENSOR))
                return@launch
            }

            // 获取相机参数
            emitEvent(CaptureEvent.InitCaptureEvent(EventStatus.PROGRESS, CaptureEvent.InitStep.FETCH_CAMERA_OPTIONS))
            val fetchCameraOptionsResult = fetchCameraOptions()
            if (!fetchCameraOptionsResult) {
                emitEvent(CaptureEvent.InitCaptureEvent(EventStatus.FAILED, CaptureEvent.InitStep.FETCH_CAMERA_OPTIONS))
                return@launch
            }

            // http通信需先绑定相机网络
            NetworkManager.cameraNet?.let {
                connectivityManager.bindProcessToNetwork(it)
            } ?: run {
                emitEvent(CaptureEvent.InitCaptureEvent(EventStatus.FAILED, CaptureEvent.InitStep.INIT_SUPPORT_CONFIG))
                return@launch
            }
            // 初始化json配置
            emitEvent(CaptureEvent.InitCaptureEvent(EventStatus.PROGRESS, CaptureEvent.InitStep.INIT_SUPPORT_CONFIG))
            val initCameraSupportConfigResult = initCameraSupportConfig()
            // http通信需先绑定相机网络
            connectivityManager.bindProcessToNetwork(null)
            if (!initCameraSupportConfigResult) {
                emitEvent(CaptureEvent.InitCaptureEvent(EventStatus.FAILED, CaptureEvent.InitStep.INIT_SUPPORT_CONFIG))
                return@launch
            }

            logger.d("initCapture create CameraOfflineData object")
            cameraOfflineData = CameraOfflineData()


            // 开启预览流
            emitEvent(CaptureEvent.InitCaptureEvent(EventStatus.PROGRESS, CaptureEvent.InitStep.OPEN_PREVIEW_STREAM))
            val openPreviewStreamResult = openPreviewStream()
            if (!openPreviewStreamResult) {
                emitEvent(CaptureEvent.InitCaptureEvent(EventStatus.FAILED, CaptureEvent.InitStep.OPEN_PREVIEW_STREAM))
                return@launch
            }

            // 旧拍摄流程需要在预览流开启之后，将app校验合法性之后的Offline拍摄参数设置给相机
            if (!instaCameraManager.supportConfig.supportNewCaptureControlFlow()) {
                logger.d("[Old Capture Control Flow] set offline capture setting value to camera")
                setOfflineCaptureSettingValueToCamera()
            }

            // 开启预览流之后可能导致某些参数发生变化，需要重新获取相机参数
            fetchCameraOptions()

            emitEvent(CaptureEvent.InitCaptureEvent(EventStatus.SUCCESS, captureModeList = instaCameraManager.supportCaptureMode, currentCaptureMode = cameraOfflineData.currentCaptureMode))
        }
    }

    fun switchCaptureMode(position: Int) {
        emitEvent(CaptureEvent.SwitchCaptureModeEvent(EventStatus.START))
        viewModelScope.launch {
            if (position > instaCameraManager.supportCaptureMode.size - 1) {
                emitEvent(CaptureEvent.SwitchCaptureModeEvent(EventStatus.FAILED))
                return@launch
            }
            val captureMode = instaCameraManager.supportCaptureMode[position]
            // 下发切换拍摄模式命令
            val result = cameraOfflineData.setCaptureMode(captureMode)
            if (!result) {
                emitEvent(CaptureEvent.SwitchCaptureModeEvent(EventStatus.FAILED))
                return@launch
            }
            // 将新的拍摄模式的offline参数设置给相机
            if (!instaCameraManager.supportConfig.supportNewCaptureControlFlow()) {
                setOfflineCaptureSettingValueToCamera()
            }
            emitEvent(CaptureEvent.SwitchCaptureModeEvent(EventStatus.SUCCESS))
        }
    }

    private fun startLive() {
        val currentCaptureMode = cameraOfflineData.currentCaptureMode
        if (!currentCaptureMode.isLiveMode) {
            return
        }
        val liveRtmp = Pref.getLiveRtmp()
        if (liveRtmp.isEmpty()) {
            emitEvent(CaptureEvent.CameraLiveEvent(CaptureEvent.LiveStatus.RTMP_EMPTY))
            return
        }
        emitEvent(CaptureEvent.CameraLiveEvent(CaptureEvent.LiveStatus.START_LIVE))

        instaCameraManager.startLive(
            liveRtmp,
            -1,
            object : ILiveStatusListener {
                override fun onLivePushStarted() {
                    logger.d("onLivePushStarted")
                    isLiving = true
                    emitEvent(CaptureEvent.CameraLiveEvent(CaptureEvent.LiveStatus.PUSH_STARTED))
                }

                override fun onLivePushFinished() {
                    logger.d("onLivePushFinished")
                    isLiving = false
                    emitEvent(CaptureEvent.CameraLiveEvent(CaptureEvent.LiveStatus.PUSH_FINISHED))
                }

                override fun onLivePushError(error: Int, desc: String?) {
                    logger.d("onLivePushError  error=$error   desc=$desc")
                    isLiving = false
                    emitEvent(CaptureEvent.CameraLiveEvent(CaptureEvent.LiveStatus.PUSH_ERROR))
                }

                override fun onLiveFpsUpdate(fps: Int) {
                    logger.d("onLiveFpsUpdate")
                }
            }
        )
    }

    private fun stopLive() {
        emitEvent(CaptureEvent.CameraLiveEvent(CaptureEvent.LiveStatus.STOP_LIVE))
        instaCameraManager.stopLive()
    }

    fun startCapture() {
        val currentCaptureMode = cameraOfflineData.currentCaptureMode

        // 处理单点击拍照逻辑
        if (isSingleClickAction) {
            takePhotos(currentCaptureMode)
            return
        }

        // 处理录制/直播逻辑
        when {
            currentCaptureMode.isLiveMode && !isLiving -> startLive()
            currentCaptureMode.isLiveMode && isLiving -> stopLive()
            !currentCaptureMode.isLiveMode && !instaCameraManager.isCameraWorking -> startRecord(
                currentCaptureMode
            )

            !currentCaptureMode.isLiveMode && instaCameraManager.isCameraWorking -> stopRecord(
                currentCaptureMode
            )

            else -> {}
        }
    }

    private fun startRecord(captureMode: CaptureMode) {
        if (isSingleClickAction) return
        if (!instaCameraManager.isSdCardEnabled) {
            emitEvent(CaptureEvent.CameraCaptureEvent(CaptureEvent.CaptureStatus.SD_DISABLE))
            return
        }
        when (captureMode) {
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

    private fun takePhotos(captureMode: CaptureMode) {
        if (!isSingleClickAction) return
        when (captureMode) {
            CaptureMode.CAPTURE_NORMAL -> instaCameraManager.startNormalCapture()
            CaptureMode.HDR_CAPTURE -> instaCameraManager.startHDRCapture()
            CaptureMode.NIGHT_SCENE -> instaCameraManager.startNightScene()
            CaptureMode.BURST -> instaCameraManager.startBurstCapture()
            else -> {}
        }
    }

    private fun stopRecord(captureMode: CaptureMode) {
        if (isSingleClickAction) return
        when (captureMode) {
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

    private suspend fun reopenPreviewStream(): Boolean {
        instaCameraManager.closePreviewStream()
        return openPreviewStream()
    }

    private suspend fun setOfflineCaptureSettingValueToCamera(): Boolean {
        logger.d("setOfflineCaptureSettingValueToCamera function invoke")
        return suspendCancellableCoroutine {
            logger.d("setOfflineCaptureSettingValueToCamera current capture mode is ${cameraOfflineData.currentCaptureMode}")
            with(instaCameraManager) {
                // 批量设置
                beginSettingOptions()
                getSupportCaptureSettingList(cameraOfflineData.currentCaptureMode).forEach { setting ->
                    val value = cameraOfflineData.getCaptureSetting(
                        cameraOfflineData.currentCaptureMode,
                        setting
                    )
                    logger.d("$setting = $value")
                    setCaptureSettingValue(cameraOfflineData.currentCaptureMode, setting, value)
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

    private suspend fun openPreviewStream(): Boolean {
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

    private suspend fun checkCameraSensorMode(): Boolean {
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

    private suspend fun fetchCameraOptions(): Boolean {
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

    private suspend fun initCameraSupportConfig(): Boolean {
        // http通信需要先绑定相机网络
        NetworkManager.cameraNet?.let { connectivityManager.bindProcessToNetwork(it) } ?: return false
        return suspendCancellableCoroutine {
            logger.d("initCameraSupportConfig function invoke")
            instaCameraManager.initCameraSupportConfig(object : ICaptureSupportConfigCallback {
                override fun onComplete() {
                    logger.d("initCameraSupportConfig success")
                    // http通信结束，解除相机网络绑定
                    connectivityManager.bindProcessToNetwork(null)
                    it.resume(true)
                }

                override fun onFailed(s: String) {
                    logger.d("initCameraSupportConfig failed : $s")
                    // http通信结束，解除相机网络绑定
                    connectivityManager.bindProcessToNetwork(null)
                    it.resume(false)
                }
            })
        }
    }

    fun closePreviewStream() {
        instaCameraManager.closePreviewStream()
        instaCameraManager.setPreviewStatusChangedListener(null)
    }


    override fun onCameraStatusChanged(enabled: Boolean, connectType: Int) {
        if (connectType == InstaCameraManager.CONNECT_TYPE_WIFI && !enabled) {
            emitEvent(CameraWiFiDisconnectEvent)
        }
    }

    override fun onOpening() {
        logger.d("onOpening")
    }

    override fun onOpened() {
        logger.d("onOpened")
        isStreamOpened = true
        openPreviewStreamListener?.invoke(true)
    }

    override fun onIdle() {
        logger.d("onIdle")
        isStreamOpened = false
    }

    override fun onError() {
        logger.d("onError")
        isStreamOpened = false
        openPreviewStreamListener?.invoke(false)
    }

    override fun onCleared() {
        isFetchingOptions = false
        instaCameraManager.setPreviewStatusChangedListener(null)
        instaCameraManager.setCaptureStatusListener(null)
        instaCameraManager.setCameraLockScreen(false)
        super.onCleared()
    }

    override fun onCaptureStarting() {
        emitEvent(CaptureEvent.CameraCaptureEvent(CaptureEvent.CaptureStatus.STARTING))
    }

    override fun onCaptureWorking() {
        emitEvent(CaptureEvent.CameraCaptureEvent(CaptureEvent.CaptureStatus.WORKING))
    }

    override fun onCaptureStopping() {
        emitEvent(CaptureEvent.CameraCaptureEvent(CaptureEvent.CaptureStatus.STOPPING))
    }

    override fun onCaptureFinish(paths: Array<String>?) {
        emitEvent(CaptureEvent.CameraCaptureEvent(CaptureEvent.CaptureStatus.FINISH))
        onCaptureFinishEnd()
    }

    private fun onCaptureFinishEnd() {
        viewModelScope.launch {
            if (instaCameraManager.supportConfig.supportNewCaptureControlFlow()) {
                return@launch
            }
            if (cameraOfflineData.currentCaptureMode.isVideoMode || cameraOfflineData.currentCaptureMode in arrayOf(CaptureMode.INTERVAL_SHOOTING, CaptureMode.STARLAPSE_SHOOTING)) {
                reopenPreviewStream()
                emitEvent(CaptureEvent.RestartPlayerViewEvent)
            } else {
                if (cameraOfflineData.currentCaptureMode.isPhotoMode) {
                    // 拍照时相机有内部切换H264/H265的bug，检测到发生变化后重启预览
                    val oldIsH265 = instaCameraManager.isH265StreamEncode
                    fetchCameraOptions()
                    if (instaCameraManager.isH265StreamEncode != oldIsH265) {
                        reopenPreviewStream()
                        emitEvent(CaptureEvent.RestartPlayerViewEvent)
                    }
                }
                if (instaCameraManager.previewStatus == InstaCameraManager.PREVIEW_STATUS_OPENED) {
                    setOfflineCaptureSettingValueToCamera()
                }
            }
        }
    }

    override fun onCaptureError(i: Int) {
        emitEvent(CaptureEvent.CameraCaptureEvent(errorCode = i))
    }

    override fun onCaptureTimeChanged(captureTime: Long) {
        val timeLapseWorking = instaCameraManager.isCameraWorking(CaptureMode.TIMELAPSE)
        if (timeLapseWorking) {
            val recordResolution = instaCameraManager.getRecordResolution(CaptureMode.TIMELAPSE)
            val interval = instaCameraManager.getInterval(CaptureMode.TIMELAPSE)
            // Расчёт длительности готового видео вынесен в :lib (тестируется на JVM).
            val videoTime = TimelapseMath.videoDurationMs(captureTime, interval.nativeValue, recordResolution.fps)
            emitEvent(CaptureEvent.CameraCaptureEvent(CaptureEvent.CaptureStatus.RECORD_TIME, captureTime, videoTime))
        } else {
            emitEvent(CaptureEvent.CameraCaptureEvent(CaptureEvent.CaptureStatus.RECORD_TIME, recordTime = captureTime))
        }
    }

    override fun onCaptureCountChanged(captureCount: Int) {
        emitEvent(CaptureEvent.CameraCaptureEvent(CaptureEvent.CaptureStatus.CAPTURE_COUNT, captureCount = captureCount))
    }

    override fun onCameraPreviewStreamParamsChanged(isPreviewStreamParamsChanged: Boolean) {
        if (instaCameraManager.cameraConnectedType != InstaCameraManager.CONNECT_TYPE_WIFI) return
        if (isFetchingOptions) return
        if (!isPreviewStreamParamsChanged) return

        emitEvent(CaptureEvent.CameraPreviewStreamParamsChangedEvent)

    }

    fun cameraPreviewStreamParamsChanged(playerView: InstaCapturePlayerView) {
        // 预览编码格式改变则重启解码器和播放器
        if (isStreamOpened && instaCameraManager.isH265StreamEncode != (instaCameraManager.videoEncodeType == InstaCameraManager.ENCODE_265)) {
            instaCameraManager.setStreamEncode()
            emitEvent(CaptureEvent.RestartPlayerViewEvent)
            return
        }

        if (playerView.isPlaying) {
            // 预览防抖开关改变则重启播放器
            if (isPreviewFileTypeChange(playerView)) {
                viewModelScope.launch {
                    fetchCameraOptions()
                    emitEvent(CaptureEvent.RestartPlayerViewEvent)
                }
                return
            }

            val captureMode = cameraOfflineData.currentCaptureMode

            val isFlowStateOn = instaCameraManager.isFlowstateOn(cameraOfflineData.currentCaptureMode)

            val assetInfo = instaCameraManager.supportConfig.getConvertAssetInfo(captureMode, isFlowStateOn)

            val assetInfoStab = instaCameraManager.supportConfig.getStabConvertAssetInfo(captureMode, isFlowStateOn)

            val stabOffset = InstaCapturePlayerView.getPlayerOffsetData(assetInfoStab).offsetV1

            // WindowCropInfo参数变化刷新Offset
            val shouldUpdateWindowCrop = shouldUpdateWindowCrop(playerView, assetInfo, stabOffset)

            val windowCropInfo = if (shouldUpdateWindowCrop) { createWindowCropInfo(assetInfo) } else null

            val offsetData = if (shouldUpdateWindowCrop) InstaCapturePlayerView.getPlayerOffsetData(assetInfo) else null

            // 预览分辨率改变则更新播放器分辨率
            val resolution = instaCameraManager.curFirstStreamResolution?.takeIf {
                it.width != playerView.previewWidth || it.height != playerView.previewHeight || it.fps != playerView.previewFps
            }

            logger.d("cameraPreviewStreamParamsChanged   windowCropInfo=$windowCropInfo   offsetData=$offsetData   stabOffset=$stabOffset   resolution=$resolution")
            emitEvent(CaptureEvent.UpdatePlayerViewParamsEvent(windowCropInfo, offsetData, stabOffset, resolution))
        }
    }

    /**
     * 检查窗口裁剪信息是否需要更新
     */
    private fun shouldUpdateWindowCrop(playerView: InstaCapturePlayerView, assetInfo: AssetInfo, stabOffset: String): Boolean {
        val cropInfo = playerView.windowCropInfo ?: return true
        return assetInfo.cropWindowSrcWidth != cropInfo.srcWidth
                || assetInfo.cropWindowSrcHeight != cropInfo.srcHeight
                || assetInfo.cropWindowDstWidth != cropInfo.desWidth
                || assetInfo.cropWindowDstHeight != cropInfo.desHeight
                || assetInfo.cropOffsetX != cropInfo.offsetX
                || assetInfo.cropOffsetY != cropInfo.offsetY
                || !Objects.equals(stabOffset, playerView.stabOffset)
    }

    /**
     * 创建窗口裁剪信息对象
     */
    private fun createWindowCropInfo(assetInfo: AssetInfo): WindowCropInfo {
        return WindowCropInfo().apply {
            srcWidth = assetInfo.cropWindowSrcWidth
            srcHeight = assetInfo.cropWindowSrcHeight
            desWidth = assetInfo.cropWindowDstWidth
            desHeight = assetInfo.cropWindowDstHeight
            offsetX = assetInfo.cropOffsetX
            offsetY = assetInfo.cropOffsetY
        }
    }

    private fun isPreviewFileTypeChange(playerView: InstaCapturePlayerView): Boolean {
        val isFlowStateOn = instaCameraManager.isFlowstateOn(cameraOfflineData.currentCaptureMode)
        return instaCameraManager.supportConfig.getPreviewFileType(cameraOfflineData.currentCaptureMode, isFlowStateOn) != playerView.fileType
    }

}
