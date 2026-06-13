package com.arashivision.sdk.demo.ui.player

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.arashivision.sdk.demo.R
import com.arashivision.sdk.demo.base.BaseActivity
import com.arashivision.sdk.demo.base.BaseEvent
import com.arashivision.sdk.demo.databinding.ActivityLocalSphericalPlayerBinding
import com.arashivision.sdk.demo.ui.capture.GyroOrientationController
import com.arashivision.orientation.PlayerOrientationCoordinator
import com.arashivision.orientation.detection.VideoDetectionSidecarParser
import com.arashivision.orientation.detection.VideoDetectionTimeline
import com.arashivision.orientation.detection.VideoDetectedObject
import com.arashivision.orientation.panorama.EquirectangularProjection
import com.arashivision.orientation.panorama.PanoramaDirection
import com.arashivision.orientation.panorama.PanoramaFovMath
import com.arashivision.orientation.panorama.TargetFovState
import com.elvishew.xlog.XLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
@OptIn(UnstableApi::class)
class LocalSphericalPlayerActivity :
    BaseActivity<ActivityLocalSphericalPlayerBinding, LocalSphericalPlayerViewModel>() {

    private val logger = XLog.tag(LocalSphericalPlayerActivity::class.java.simpleName).build()

    private var player: ExoPlayer? = null

    private lateinit var gyroController: GyroOrientationController
    private lateinit var vrManager: LocalVrManager

    private val uiHandler = Handler(Looper.getMainLooper())
    private val detectionParser = VideoDetectionSidecarParser()
    // Зонд A: поворот сферы media3 через приватный GL-рендерер (setYaw/setPitch у media3 нет).
    private val playerSink: OrientationApplier by lazy { Media3SphericalOrientationSink(binding.sphericalView) }
    private var currentGazeDirection: PanoramaDirection = EquirectangularProjection.fromYawPitch(0.0, 0.0)

    // Оркестрация ориентации плеера (чистая логика в :lib, тестируется на JVM): инверсия
    // знаков под media3 + адаптивное сглаживание (давит дрожь на покое, не тормозит повороты).
    private val orientationCoordinator = PlayerOrientationCoordinator()
    private val detectionUpdateRunnable = object : Runnable {
        override fun run() {
            updateCurrentDetections()
            uiHandler.postDelayed(this, DETECTION_UPDATE_INTERVAL_MS)
        }
    }

    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri ?: return@registerForActivityResult
            persistReadPermission(uri)
            viewModel.onVideoSelected(uri)
            binding.tvSelectedVideo.text = uri.toString()
            startPlayback(uri)
        }

    private val pickJsonLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri ?: return@registerForActivityResult
            persistReadPermission(uri)
            loadDetectionJson(uri)
        }

    @RequiresApi(Build.VERSION_CODES.R)
    @androidx.annotation.OptIn(UnstableApi::class)
    override fun initView() {
        super.initView()

        binding.sphericalView.setDefaultStereoMode(C.STEREO_MODE_MONO)
        // Disable Media3's built-in sensor rotation — we take full control via
        // GyroOrientationController so the view direction == our gaze direction exactly.
        binding.sphericalView.setUseSensorRotation(false)

        gyroController = GyroOrientationController(
            context = this,
            getDisplayRotation = { display?.rotation ?: Surface.ROTATION_0 },
            applyOrientation = { yaw, pitch -> tryApplyOrientation(yaw, pitch) }
        )

        vrManager = LocalVrManager(
            activity = this,
            sourceView = binding.sphericalView,
            leftEyeImage = binding.vrLeftEye,
            overlaysToHide = listOf(binding.btnPlayPause, binding.ivCaptureSetting, binding.btnCalibrate),
            getSensitivity = { gyroController.sensivity },
            setSensitivity = { v -> gyroController.sensivity = v }
        )
        vrManager.onVrModeChanged = { isVrMode ->
            binding.directionArrowOverlay.setVrMode(isVrMode)
        }
        syncPlayPauseButton()
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    @SuppressLint("ClickableViewAccessibility")
    override fun initListener() {
        super.initListener()
        binding.btnPickVideo.setOnClickListener { pickVideoLauncher.launch(arrayOf("video/*")) }
        binding.btnPickJson.setOnClickListener { pickJsonLauncher.launch(JSON_MIME_TYPES) }

        binding.btnPlayPause.setOnClickListener {
            val newState = !(player?.isPlaying ?: false)
            player?.playWhenReady = newState
            syncPlayPauseButton()
        }

        binding.btnCalibrate.setOnClickListener {
            gyroController.calibrate()
            toast(R.string.gyro_recentered)
        }

        binding.btnVrToggle.setOnClickListener { vrManager.toggleVrMode() }
        binding.btnVrToggle.setOnLongClickListener {
            vrManager.showVrSettingsDialog()
            true
        }

        binding.ivCaptureSetting.setOnClickListener { showPlaybackSettings() }

        // in VR mode we block touches on player so accidental drags don't desync head-tracking
        binding.sphericalView.setOnTouchListener { _, _ -> vrManager.isVrMode }
    }

    override fun onEvent(event: BaseEvent) = Unit

    override fun onStart() {
        super.onStart()
        if (player == null) {
            player = ExoPlayer.Builder(this).build().also { exo ->
                exo.repeatMode = Player.REPEAT_MODE_ALL
                exo.setVideoSurfaceView(binding.sphericalView)
                viewModel.currentVideoUri?.let { uri ->
                    exo.setMediaItem(MediaItem.fromUri(uri))
                    exo.prepare()
                    exo.playWhenReady = true
                }
            }
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onResume() {
        super.onResume()
        binding.sphericalView.onResume()
        gyroController.start()
        vrManager.onResume()
        player?.playWhenReady = true
        syncPlayPauseButton()
        uiHandler.removeCallbacks(detectionUpdateRunnable)
        uiHandler.post(detectionUpdateRunnable)
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onPause() {
        vrManager.onPause()
        gyroController.stop()
        binding.sphericalView.onPause()
        uiHandler.removeCallbacks(detectionUpdateRunnable)
        player?.playWhenReady = false
        super.onPause()
    }

    override fun onStop() {
        uiHandler.removeCallbacks(detectionUpdateRunnable)
        player?.release()
        player = null
        super.onStop()
    }

    override fun onBackPressed() {
        if (vrManager.isVrMode) {
            vrManager.disableVrMode()
            return
        }
        super.onBackPressed()
    }

    private fun showPlaybackSettings() {
        val actions = mutableListOf(
            getString(R.string.pick_local_video),
            getString(R.string.add_detection_json),
            if (player?.isPlaying == true) getString(R.string.pause) else getString(R.string.play),
            if (viewModel.sensorRotationEnabled) getString(R.string.disable_gyro_control) else getString(R.string.enable_gyro_control),
            getString(R.string.recenter_view)
        )
        if (vrManager.isVrMode) {
            actions.add("VR: Adjust eyes")
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.capture_settings)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    getString(R.string.pick_local_video) -> pickVideoLauncher.launch(arrayOf("video/*"))
                    getString(R.string.add_detection_json) -> pickJsonLauncher.launch(JSON_MIME_TYPES)
                    getString(R.string.pause), getString(R.string.play) -> {
                        val newState = !(player?.isPlaying ?: false)
                        player?.playWhenReady = newState
                        syncPlayPauseButton()
                    }
                    getString(R.string.disable_gyro_control), getString(R.string.enable_gyro_control) -> {
                        val enabled = viewModel.toggleSensorRotation()
                        // We keep Media3's built-in sensor OFF permanently — our
                        // GyroOrientationController handles all rotation via setYaw/setPitch.
                        gyroController.setzOrientationEnabled(enabled)
                    }
                    getString(R.string.recenter_view) -> {
                        gyroController.calibrate()
                        toast(R.string.gyro_recentered)
                    }
                    "VR: Adjust eyes" -> vrManager.showVrSettingsDialog()
                }
            }
            .show()
    }

    private fun loadDetectionJson(uri: Uri) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("Unable to open JSON file")
                    VideoDetectionTimeline(detectionParser.parse(json))
                }
            }

            result.onSuccess { timeline ->
                viewModel.onJsonSelected(uri, timeline)
                binding.tvSelectedJson.text = uri.toString()
                toast(getString(R.string.detection_json_loaded, timeline.frameCount))
                updateCurrentDetections()
            }.onFailure { error ->
                logger.e("Detection JSON load failed: ${error.message}")
                toast(getString(R.string.detection_json_load_failed, error.message ?: "unknown"), true)
            }
        }
    }

    private fun persistReadPermission(uri: Uri) {
        kotlin.runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun startPlayback(uri: Uri) {
        val exo = player ?: return
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.prepare()
        exo.playWhenReady = true
        syncPlayPauseButton()
    }

    private fun syncPlayPauseButton() {
        val playing = player?.isPlaying == true
        binding.btnPlayPause.text = if (playing) getString(R.string.pause) else getString(R.string.play)
    }

    private fun updateCurrentDetections() {
        val positionMs = player?.currentPosition ?: 0L
        val frame = viewModel.currentDetectionFrame(positionMs)

        binding.tvDetectionDebug.text = if (frame == null) {
            binding.directionArrowOverlay.hideArrow()
            getString(R.string.no_detection_json_selected)
        } else {
            val detections = frame.objects
            val trackIds = detections.joinToString { objectDetection ->
                "#${objectDetection.trackId}"
            }.ifEmpty { "—" }
            updateDirectionArrow(detections)
            val baseInfo = getString(
                R.string.current_detection_frame,
                frame.frameIdx,
                frame.timeSec,
                detections.size,
                trackIds
            )
            // Append gaze angles and FOV for debugging
            val gazeYaw = "%.1f".format(Math.toDegrees(currentGazeDirection.yawRad))
            val gazePitch = "%.1f".format(Math.toDegrees(currentGazeDirection.pitchRad))
            val hFov = "%.0f".format(Math.toDegrees(HORIZONTAL_FOV_RAD))
            val vFov = "%.0f".format(Math.toDegrees(VERTICAL_FOV_RAD))
            "$baseInfo\nGaze: yaw=$gazeYaw° pitch=$gazePitch° | FOV: H=${hFov}° V=${vFov}°"
        }
    }

    private fun updateDirectionArrow(detections: List<VideoDetectedObject>) {
        val firstResult = detections.firstNotNullOfOrNull { detection ->
            val targetDirection = EquirectangularProjection.fromNormalized(
                x = detection.centerNorm.x.coerceIn(0.0, 1.0),
                y = detection.centerNorm.y.coerceIn(0.0, 1.0)
            )
            val result = PanoramaFovMath.resolveTargetQuat(
                gaze = currentGazeDirection,
                target = targetDirection,
                horizontalFovRad = HORIZONTAL_FOV_RAD,
                verticalFovRad = VERTICAL_FOV_RAD
            )
            // Log quaternion debug info for the FIRST detection each tick
            if (detection === detections.first()) {
                logQuaternionDebug(detection, targetDirection, result)
            }
            result.takeUnless { it.isInsideFov }
        }

        val arrowAngleRad = firstResult?.arrowAngleRad
        if (arrowAngleRad == null) {
            binding.directionArrowOverlay.hideArrow()
        } else {
            binding.directionArrowOverlay.showArrow(arrowAngleRad)
        }
    }

    private var quatLogCounter = 0
    private fun logQuaternionDebug(
        detection: VideoDetectedObject,
        targetDir: PanoramaDirection,
        result: TargetFovState
    ) {
        // Log every 10th tick (~2 seconds) to avoid log spam
        quatLogCounter++
        if (quatLogCounter % 10 != 0) return

        val gazeQ = currentGazeDirection.orientation
        val targetQ = targetDir.orientation
        val gazeYaw = Math.toDegrees(currentGazeDirection.yawRad)
        val gazePitch = Math.toDegrees(currentGazeDirection.pitchRad)
        val targetYaw = Math.toDegrees(targetDir.yawRad)
        val targetPitch = Math.toDegrees(targetDir.pitchRad)

        logger.d(
            "ARROW_DEBUG | " +
            "trackId=${detection.trackId} " +
            "centerNorm=(${"%.4f".format(detection.centerNorm.x)},${"%.4f".format(detection.centerNorm.y)}) " +
            "insideFov=${result.isInsideFov} " +
            "yawDelta=${"%.1f".format(Math.toDegrees(result.yawDeltaRad))}° " +
            "pitchDelta=${"%.1f".format(Math.toDegrees(result.pitchDeltaRad))}° " +
            "arrowAngle=${result.arrowAngleRad?.let { "%.1f".format(Math.toDegrees(it)) + "°" } ?: "HIDDEN"}"
        )
        logger.d(
            "ARROW_QUAT | " +
            "GAZE yaw=${"%.1f".format(gazeYaw)}° pitch=${"%.1f".format(gazePitch)}° " +
            "q=(${"%.4f".format(gazeQ.x)},${"%.4f".format(gazeQ.y)},${"%.4f".format(gazeQ.z)},${"%.4f".format(gazeQ.w)})"
        )
        logger.d(
            "ARROW_QUAT | " +
            "TARGET yaw=${"%.1f".format(targetYaw)}° pitch=${"%.1f".format(targetPitch)}° " +
            "q=(${"%.4f".format(targetQ.x)},${"%.4f".format(targetQ.y)},${"%.4f".format(targetQ.z)},${"%.4f".format(targetQ.w)}) " +
            "FOV h=${"%.0f".format(Math.toDegrees(HORIZONTAL_FOV_RAD))}° v=${"%.0f".format(Math.toDegrees(VERTICAL_FOV_RAD))}°"
        )
    }

    private fun tryApplyOrientation(yawDeg: Float, pitchDeg: Float) {
        if (!viewModel.sensorRotationEnabled) return

        // Координатор (чистая логика :lib) инвертирует знаки под media3 и сглаживает.
        // Источник — калибровочно-относительные gaze-углы гиро (живые, в отличие от
        // кватернионного toEulerAngles, чей yaw сломан по осям).
        val smoothed = orientationCoordinator.coordinate(
            rawGazeYawDeg = gyroController.getGazeYawDeg(),
            rawGazePitchDeg = gyroController.getGazePitchDeg()
        )
        val gazeYawDeg = smoothed.yawDeg
        val gazePitchDeg = smoothed.pitchDeg

        currentGazeDirection = EquirectangularProjection.fromYawPitch(
            yawRad = Math.toRadians(gazeYawDeg.toDouble()),
            pitchRad = Math.toRadians(gazePitchDeg.coerceIn(-MAX_PITCH_DEG, MAX_PITCH_DEG).toDouble())
        )

        // Повернуть сферу media3 через приватный рендерер (onScrollChange, градусы).
        playerSink.apply(gazeYawDeg, gazePitchDeg)
    }

    companion object {
        private val JSON_MIME_TYPES = arrayOf("application/json", "text/json", "text/plain", "application/octet-stream", "*/*")
        private const val DETECTION_UPDATE_INTERVAL_MS = 200L
        private const val MAX_PITCH_DEG = 90f

        // Adjustable FOV parameters (in radians). The arrow disappears when the target
        // is within these half-angles from the gaze direction.
        // Typical phone screen FOV: 30°–60° horizontal, 20°–50° vertical.
        // VR headset FOV: 80°–110° per eye.
        // Tune these to match your device — if the arrow never disappears, try larger values.
        @JvmField var HORIZONTAL_FOV_RAD: Double = Math.toRadians(60.0)  // 60° total HFOV → ±30°
        @JvmField var VERTICAL_FOV_RAD: Double = Math.toRadians(45.0)    // 45° total VFOV → ±22.5°
    }
}
