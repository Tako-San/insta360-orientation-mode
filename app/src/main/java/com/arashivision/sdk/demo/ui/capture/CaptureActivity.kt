package com.arashivision.sdk.demo.ui.capture

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.Surface
import android.view.View
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.arashivision.sdk.demo.R
import com.arashivision.sdk.demo.base.BaseActivity
import com.arashivision.sdk.demo.base.BaseEvent
import com.arashivision.sdk.demo.base.EventStatus.FAILED
import com.arashivision.sdk.demo.base.EventStatus.PROGRESS
import com.arashivision.sdk.demo.base.EventStatus.START
import com.arashivision.sdk.demo.base.EventStatus.SUCCESS
import com.arashivision.sdk.demo.databinding.ActivityCaptureBinding
import com.arashivision.sdk.demo.ext.durationFormat
import com.arashivision.sdk.demo.ext.instaCameraManager
import com.arashivision.sdk.demo.ext.vibrate
import com.arashivision.sdk.demo.ui.capture.adapter.CaptureModeAdapter
import com.arashivision.sdk.demo.view.CaptureShutterButton
import com.arashivision.sdk.demo.view.discretescrollview.DSVOrientation
import com.arashivision.sdk.demo.view.discretescrollview.FadingEdgeDecoration
import com.arashivision.sdk.demo.view.discretescrollview.transform.ScaleTransformer
import com.arashivision.sdk.demo.ui.capture.player.InstaPlayerViewSink
import com.arashivision.sdk.demo.view.picker.PickData
import com.arashivision.sdkcamera.camera.model.CaptureMode
import com.arashivision.sdkcamera.camera.model.CaptureSetting
import com.arashivision.sdkmedia.player.listener.PlayerViewListener
import com.elvishew.xlog.Logger
import com.elvishew.xlog.XLog

class CaptureActivity : BaseActivity<ActivityCaptureBinding, CaptureViewModel>() {

    private val logger: Logger = XLog.tag(CaptureActivity::class.java.simpleName).build()
    private var captureModeAdapter: CaptureModeAdapter? = null

    private lateinit var gyroController: GyroOrientationController

    private lateinit var vrManager: VrManager

    private val captureSink by lazy {
        com.arashivision.sdk.demo.ui.player.ReflectiveOrientationSink(binding.capturePlayerView)
    }

    private val playerSink by lazy { InstaPlayerViewSink(binding.capturePlayerView) }

    override fun onStop() {
        super.onStop()
        if (isFinishing) viewModel.closePreviewStream()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun initView() {
        super.initView()
        binding.capturePlayerView.setLifecycle(this.lifecycle)

        // init gyro controller
        gyroController = GyroOrientationController(
            context = this,
            getDisplayRotation = { windowManager.defaultDisplay.rotation },
            applyOrientation = { yaw, pitch -> tryApplyOrientationToPlayer(yaw, pitch) }
        )

        binding.svCaptureMode.setSlideOnFling(true)
        captureModeAdapter = CaptureModeAdapter()
        binding.svCaptureMode.setAdapter(captureModeAdapter)
        binding.svCaptureMode.setOrientation(DSVOrientation.HORIZONTAL)
        binding.svCaptureMode.setOverScrollEnabled(true)
        binding.svCaptureMode.setSlideOnFling(true)
        binding.svCaptureMode.setSlideOnFlingThreshold(1300)
        binding.svCaptureMode.setItemTransitionTimeMillis(180)

        binding.svCaptureMode.setItemTransformer(
            ScaleTransformer.Builder().setMinScale(0.8f).build()
        )

        binding.svCaptureMode.addItemDecoration(FadingEdgeDecoration())

        binding.pickCaptureSetting.setTitleText(getString(R.string.capture_settings))

        vrManager = VrManager(
            activity = this,
            rootContainer = binding.root,
            capturePlayerView = binding.capturePlayerView,
            svCaptureMode = binding.svCaptureMode,
            ivCaptureSetting = binding.ivCaptureSetting,
            btnCalibrate = binding.btnCalibrate,
            calibrateGyro = { try { gyroController.calibrate() } catch (_: Exception) {} },
            getSensitivity = { gyroController.sensivity },
            setSensitivity = { v -> gyroController.sensivity = v },
            preparePlayer = { playerSink.prepare() },
            getPlayerParams = { playerSink.buildParams() },
        )
    }

    override fun initListener() {
        super.initListener()
        binding.btnCapture.setOnClickListener { viewModel.startCapture() }

        binding.ivCaptureSetting.setOnClickListener { showCaptureSettingView() }

        try {
            binding.btnVrToggle.setOnClickListener {
                if (!vrManager.isVrMode) {
                    vrManager.setOrientationSnapshot(
                        yawDeg = gyroController.getSmoothedYaw(),
                        pitchDeg = gyroController.getSmoothedPitch()
                    )
                }
                vrManager.toggleVrMode()
            }
        } catch (e: Exception) {
            logger.w("VR toggle button not found in layout: ${e.message}")
        }

        binding.btnCalibrate.setOnClickListener {
            try {
                gyroController.calibrate()
                vibrate(50, 10)
                toast("Gyro calibration requested")
                logger.d("Gyro: calibration requested (manual)")
            } catch (e: Exception) {
                logger.e("Gyro calibration failed: ${e.message}")
                toast("Gyro calibration failed")
            }
        }

        binding.svCaptureMode.addOnItemChangedListener { _: RecyclerView.ViewHolder?, position: Int ->
            vibrate(50, 10)
            viewModel.switchCaptureMode(position)
        }

        captureModeAdapter?.setItemClickListener { _: String, position: Int ->
            binding.svCaptureMode.smoothScrollToPosition(position)
        }

        binding.pickCaptureSetting.setOnItemClickListener { position, data ->
            val supportCaptureSettingList: List<CaptureSetting> = viewModel.cameraOfflineData.let {
                instaCameraManager.getSupportCaptureSettingList(it.currentCaptureMode)
            }

            if (this::vrManager.isInitialized && vrManager.isVrMode && position >= supportCaptureSettingList.size) {
                try {
                    binding.pickCaptureSetting.hide()
                    vrManager.openVrSettings()
                } catch (e: Exception) {
                    logger.e("Failed to open VR settings dialog: ${e.message}")
                }
                return@setOnItemClickListener
            }

            val captureSetting: CaptureSetting = supportCaptureSettingList[position]
            viewModel.cameraOfflineData.setCaptureSetting(captureSetting, data) {
                binding.pickCaptureSetting.setData(captureSettingDataList)
            }
            binding.pickCaptureSetting.setData(captureSettingDataList)
        }
    }

    private val captureSettingDataList: List<PickData>
        get() {
            val supportCaptureSettingList: List<CaptureSetting> = viewModel.cameraOfflineData.let {
                instaCameraManager.getSupportCaptureSettingList(it.currentCaptureMode)
            }

            val list = supportCaptureSettingList.map { getCaptureSettingData(it) }.toMutableList()

            try {
                if (this::vrManager.isInitialized && vrManager.isVrMode) {
                    val vrPick = PickData(
                        true,
                        "VR: Adjust eyes",
                        0,
                        listOf("Open VR settings" to 0)
                    )
                    list.add(vrPick)
                }
            } catch (e: Exception) {
            }

            return list
        }

    private fun getCaptureSettingData(captureSetting: CaptureSetting): PickData {
        val title = getString(getCaptureSettingNameResId(captureSetting))
        val captureSettingValue = viewModel.cameraOfflineData.getCaptureSetting(captureSetting)
        val captureSettingSupportList = viewModel.getCaptureSettingSupportValueList(captureSetting)
        val index: Int = captureSettingSupportList.indexOfFirst { captureSettingValue == it }.coerceAtLeast(0)
        val options = captureSettingSupportList.map { value -> getCaptureSettingValueName(this, captureSetting, value) to value }
        return PickData(true, title, index, options)
    }

    private fun showCaptureSettingView() {
        binding.pickCaptureSetting.setData(captureSettingDataList)
        binding.pickCaptureSetting.show()
    }

    override fun onEvent(event: BaseEvent) {
        super.onEvent(event)
        when (event) {
            is CaptureEvent.CameraWiFiDisconnectEvent -> finish()

            is CaptureEvent.InitCaptureEvent -> {
                logger.d("event.status=${event.status}   event.step=${event.step}")
                when (event.status) {
                    START -> showLoading()
                    PROGRESS -> stepToLoadingTextMap[event.step]?.let { showLoading(it) }
                    SUCCESS -> {
                        showLoading(R.string.capture_rendering_player)
                        displayPreviewStream()
                        updateCaptureModeUi(event.captureModeList, event.currentCaptureMode)
                        updateCaptureButton()
                    }
                    FAILED -> {
                        hideLoading()
                        stepToErrorTextMap[event.step]?.let { lastToast(it) }
                        finish()
                    }
                }
            }

            is CaptureEvent.SwitchCaptureModeEvent -> {
                when (event.status) {
                    START -> showLoading(R.string.capture_mode_switching)
                    SUCCESS -> {
                        hideLoading()
                        if (viewModel.isSingleClickAction) {
                            binding.btnCapture.setState(CaptureShutterButton.State.CAPTURE_IDLE)
                        } else {
                            binding.btnCapture.setState(CaptureShutterButton.State.RECORD_IDLE)
                        }
                    }
                    FAILED -> {
                        hideLoading()
                        toast(R.string.capture_mode_switch_failed)
                    }
                    else -> {}
                }
            }

            is CaptureEvent.CameraPreviewStreamParamsChangedEvent -> {
                viewModel.cameraPreviewStreamParamsChanged(playerSink)
            }

            CaptureEvent.RestartPlayerViewEvent -> replay()

            is CaptureEvent.UpdatePlayerViewParamsEvent -> {
                if (event.playerOffset != null && event.stabOffset != null) {
                    playerSink.applyOffset(event.playerOffset!!, event.stabOffset!!)
                }
                event.windowCrop?.let { playerSink.applyWindowCrop(it) }
                event.resolution?.let { playerSink.applyResolution(it) }
            }

            is CaptureEvent.CameraCaptureEvent -> {
                logger.d("  status=${event.status}")
                when (event.status) {
                    CaptureEvent.CaptureStatus.SD_DISABLE -> toast(R.string.toast_no_sd)
                    CaptureEvent.CaptureStatus.STARTING -> showLoading(R.string.capture_preparing)
                    CaptureEvent.CaptureStatus.STOPPING -> showLoading(R.string.capture_stopping)
                    CaptureEvent.CaptureStatus.WORKING -> {
                        hideLoading()
                        binding.ivCaptureSetting.visibility = View.GONE
                        binding.btnCalibrate.visibility = View.GONE
                        binding.svCaptureMode.visibility = View.INVISIBLE
                        if (viewModel.isSingleClickAction) {
                            binding.btnCapture.setState(CaptureShutterButton.State.CAPTURING)
                        } else {
                            binding.btnCapture.setState(CaptureShutterButton.State.RECORDING)
                        }
                    }
                    CaptureEvent.CaptureStatus.FINISH -> captureComplete()
                    CaptureEvent.CaptureStatus.RECORD_TIME -> {
                        binding.tvRecordTime.visibility = View.VISIBLE
                        binding.tvRecordTime.text = event.recordTime.durationFormat()
                        if (event.videoTime != -1L) {
                            binding.tvVideoDuration.visibility = View.VISIBLE
                            binding.ivArrow.visibility = View.VISIBLE
                            binding.tvVideoDuration.text = event.videoTime.durationFormat()
                        }
                    }
                    CaptureEvent.CaptureStatus.CAPTURE_COUNT -> {
                        binding.tvRecordTime.visibility = View.VISIBLE
                        binding.tvRecordTime.text =
                            getString(R.string.capture_count, event.captureCount)
                    }
                    CaptureEvent.CaptureStatus.ERROR -> {
                        captureComplete()
                        toast(getString(R.string.capture_error, event.errorCode))
                    }
                }
            }

            is CaptureEvent.CameraLiveEvent -> {
                when (event.status) {
                    CaptureEvent.LiveStatus.RTMP_EMPTY -> toast(R.string.capture_live_rtmp_empty)
                    CaptureEvent.LiveStatus.START_LIVE ->  showLoading(R.string.capture_live_starting)
                    CaptureEvent.LiveStatus.STOP_LIVE -> showLoading(R.string.capture_live_closing)
                    CaptureEvent.LiveStatus.PUSH_STARTED -> {
                        hideLoading()
                        binding.btnCapture.setState(CaptureShutterButton.State.RECORDING)
                        toast(R.string.capture_live_start_push)
                    }
                    CaptureEvent.LiveStatus.PUSH_FINISHED -> {
                        hideLoading()
                        binding.btnCapture.setState(CaptureShutterButton.State.RECORD_IDLE)
                    }
                    CaptureEvent.LiveStatus.PUSH_ERROR -> {
                        hideLoading()
                        binding.btnCapture.setState(CaptureShutterButton.State.RECORD_IDLE)
                        toast(R.string.capture_live_push_error)
                    }
                }
            }
        }
    }

    private fun captureComplete() {
        hideLoading()
        binding.tvRecordTime.visibility = View.GONE
        binding.tvVideoDuration.visibility = View.GONE
        binding.ivArrow.setVisibility(View.GONE)
        if (viewModel.isSingleClickAction) {
            binding.btnCapture.setState(CaptureShutterButton.State.CAPTURE_IDLE)
        } else {
            binding.btnCapture.setState(CaptureShutterButton.State.RECORD_IDLE)
        }
        if (!vrManager.isVrMode) {
            binding.ivCaptureSetting.visibility = View.VISIBLE
            binding.btnCalibrate.visibility = View.VISIBLE
            binding.svCaptureMode.visibility = View.VISIBLE
        }
    }

    private fun replay() {
        if (isFinishing || isDestroyed) return
        playerSink.prepare()
        binding.capturePlayerView.play()
    }

    private fun displayPreviewStream() {
        binding.capturePlayerView.setPlayerViewListener(object : PlayerViewListener {
            override fun onFirstFrameRender() {
                hideLoading()
                gyroController.calibrate()
                logger.d("Gyro: controller calibrated (requested)")
            }

            override fun onLoadingFinish() {
                instaCameraManager.setPipeline(binding.capturePlayerView.pipeline)
            }

            override fun onReleaseCameraPipeline() {
                instaCameraManager.setPipeline(null)
            }
        })

        playerSink.prepare()
        binding.capturePlayerView.play()
        binding.capturePlayerView.keepScreenOn = true
    }

    private fun updateCaptureModeUi(captureModeList: List<CaptureMode>?, currentCaptureMode: CaptureMode?) {
        captureModeList?.takeIf { it.isNotEmpty() } ?: return
        currentCaptureMode?.takeIf { it in captureModeList } ?: return

        val data = captureModeList.mapNotNull { mode ->
            getCaptureModeTextResId(mode)?.let { getString(it) }
        }

        captureModeAdapter?.setData(data.toMutableList())
        binding.svCaptureMode.scrollToPosition(captureModeList.indexOf(currentCaptureMode))
    }

    private fun updateCaptureButton(){
        if (viewModel.isSingleClickAction) {
            binding.btnCapture.setState(CaptureShutterButton.State.CAPTURE_IDLE)
        } else {
            binding.btnCapture.setState(CaptureShutterButton.State.RECORD_IDLE)
        }
    }

    override fun onBackPressed() {
        if (binding.pickCaptureSetting.isVisible) {
            binding.pickCaptureSetting.hide()
            return
        }
        if (vrManager.isVrMode) {
            vrManager.disableVrMode()
            return
        }
        if (!instaCameraManager.isCameraWorking) super.onBackPressed()
    }

    override fun onDestroy() {
        vrManager.destroy()
        binding.capturePlayerView.destroy()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        gyroController.start()

        try {
            val pipelinePresent = try { binding.capturePlayerView.pipeline != null } catch (e: Exception) { false }
            if (!pipelinePresent) {
                displayPreviewStream()
            } else {
                binding.capturePlayerView.play()
            }
        } catch (t: Throwable) {
            logger.e("onResume preview reinit failed: ${t.message}")
        }

        vrManager.onResume()
    }


    override fun onPause() {
        super.onPause()
        gyroController.stop()
        vrManager.onPause()
    }

    private fun tryApplyOrientationToPlayer(yawDeg: Float, pitchDeg: Float) {
        val pipelinePresent = try {
            binding.capturePlayerView.pipeline != null
        } catch (e: Exception) {
            false
        }
        if (!pipelinePresent) return

        // Pitch is inverted for the online capture player only: the SDK InstaCapturePlayerView
        // maps setPitch the opposite way to our own offline GL renderer (which is already
        // calibrated). Inverting here keeps the offline path untouched.
        val capturePitch = -pitchDeg
        try {
            if (vrManager.isVrMode) {
                vrManager.applyOrientation(yawDeg, capturePitch)
            } else {
                captureSink.apply(yawDeg, capturePitch)
            }
        } catch (e: Exception) {
            logger.e("tryApplyOrientationToPlayer error: ${e.message}")
        }
    }

}
