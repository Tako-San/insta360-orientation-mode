package com.arashivision.sdk.demo.ui.capture

import android.app.Activity
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import com.arashivision.sdk.demo.ext.instaCameraManager
import com.arashivision.sdk.demo.ui.player.ReflectiveOrientationSink
import com.arashivision.sdk.demo.ui.vr.BaseVrManager
import com.arashivision.sdkmedia.player.capture.CaptureParamsBuilderV2
import com.arashivision.sdkmedia.player.capture.InstaCapturePlayerView
import com.arashivision.sdkmedia.player.listener.PlayerViewListener

/**
 * VR helper for the live capture stream. Unlike the offline player (which mirrors a single
 * SphericalGLSurfaceView), capture builds a SECOND [InstaCapturePlayerView] for the right eye,
 * binds the camera pipeline to it, and copies its frames into the left [ImageView] via the
 * base-class PixelCopy loop. The right eye gets an IPD yaw offset for the stereo effect.
 *
 * Only the capture-specific bits live here (the second player, pipeline wiring, the dynamic
 * VR container, orientation/IPD); everything shared sits in [BaseVrManager].
 */
class VrManager(
    activity: Activity,
    private val rootContainer: ViewGroup?,
    private val capturePlayerView: InstaCapturePlayerView,
    private val svCaptureMode: View,
    private val ivCaptureSetting: View,
    private val btnCalibrate: View,
    private val calibrateGyro: () -> Unit = {},
    getSensitivity: () -> Float = { 1.2f },
    setSensitivity: (Float) -> Unit = {},
    /** Prepares the main capture player (calls PlayerViewSink.prepare()). Used when VR mode
     *  exits and the main player needs to be re-prepared. */
    private val preparePlayer: () -> Unit = {},
    /** Supplies CaptureParamsBuilderV2 for preparing additional players (e.g. the right-eye VR
     *  player) which are not covered by PlayerViewSink. */
    private val getPlayerParams: () -> CaptureParamsBuilderV2 = { CaptureParamsBuilderV2() },
) : BaseVrManager(activity, getSensitivity, setSensitivity) {

    var isVrMode: Boolean = false
        private set

    private var vrContainer: ViewGroup? = null
    private var leftVrImage: ImageView? = null
    private var rightVrPlayer: InstaCapturePlayerView? = null
    private var rightSink: ReflectiveOrientationSink? = null
    private val vrIpdYawDeg: Float = 3.0f
    private var lastYawDeg: Float = 0f
    private var lastPitchDeg: Float = 0f

    override fun copySourceView(): View? = rightVrPlayer
    override fun leftEyeTarget(): ImageView? = leftVrImage
    override fun scaledViews(): List<View> = listOfNotNull(leftVrImage, rightVrPlayer)
    override fun eyeViews(): Pair<View, View>? {
        val parentLinear = vrContainer?.getChildAt(0) as? LinearLayout ?: return null
        if (parentLinear.childCount < 2) return null
        return parentLinear.getChildAt(0) to parentLinear.getChildAt(1)
    }

    fun toggleVrMode() {
        if (isVrMode) disableVrMode() else enableVrMode()
    }

    fun enableVrMode() {
        if (isVrMode) return
        isVrMode = true
        try {
            capturePlayerView.visibility = View.INVISIBLE
        } catch (e: Exception) {
            logError("Failed to hide main capturePlayerView", e)
        }
        if (rootContainer == null) {
            logError("enableVrMode: rootContainer is null! cannot add vr views", null)
            return
        }
        try {
            vrContainer = FrameLayout(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            rootContainer.addView(vrContainer)
        } catch (e: Exception) {
            logError("Failed to create/add vrContainer", e)
            return
        }

        val contentLinear = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        vrContainer?.addView(contentLinear)

        try {
            leftVrImage = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.BLACK)
                isClickable = false
                isFocusable = false
            }
            contentLinear.addView(leftVrImage)
        } catch (e: Exception) {
            logError("Failed to create leftVrImage", e)
        }

        try {
            rightVrPlayer = InstaCapturePlayerView(activity).apply {
                setLifecycle((activity as? androidx.fragment.app.FragmentActivity)?.lifecycle)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                keepScreenOn = true
            }
            contentLinear.addView(rightVrPlayer)
            rightVrPlayer?.setPlayerViewListener(object : PlayerViewListener {
                override fun onFirstFrameRender() {
                    startCopyLoop()
                }
                override fun onLoadingFinish() {
                    try {
                        instaCameraManager.setPipeline(rightVrPlayer!!.pipeline)
                    } catch (e: Exception) {
                        logError("Failed to set pipeline to right player", e)
                    }
                }
                override fun onReleaseCameraPipeline() {
                    try {
                        instaCameraManager.setPipeline(null)
                    } catch (e: Exception) {
                        logError("Failed to release pipeline", e)
                    }
                }
            })
            try {
                rightVrPlayer?.prepare(getPlayerParams())
            } catch (e: Exception) {
                logError("Unable to obtain capture params to prepare right player", e)
            }
            rightVrPlayer?.play()
        } catch (e: Exception) {
            logError("Failed to create or start rightVrPlayer", e)
        }

        try {
            val sizePx = dp(44)
            val marginPx = dp(12)
            val settingsBtn = ImageButton(activity).apply {
                setImageResource(android.R.drawable.ic_menu_manage)
                setBackgroundResource(android.R.color.transparent)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val flp = FrameLayout.LayoutParams(sizePx, sizePx)
                flp.gravity = Gravity.END or Gravity.TOP
                flp.setMargins(marginPx, marginPx, marginPx, marginPx)
                layoutParams = flp
                alpha = 0.85f
            }
            vrContainer?.addView(settingsBtn)
            settingsBtn.setOnClickListener { openVrSettings() }
        } catch (e: Exception) {
            logError("Failed to add VR settings button", e)
        }

        try {
            ivCaptureSetting.visibility = View.GONE
            btnCalibrate.visibility = View.GONE
            svCaptureMode.visibility = View.GONE
            svCaptureMode.isEnabled = false
            svCaptureMode.setOnTouchListener { _, _ -> true }
        } catch (e: Exception) {
            logError("Failed to hide UI elements", e)
        }
        applyVrAdjustments()
    }

    fun disableVrMode() {
        if (!isVrMode) return
        isVrMode = false
        stopCopyLoop()
        try {
            rightVrPlayer?.destroy()
        } catch (e: Exception) {
            logError("Failed to destroy rightVrPlayer", e)
        }
        try {
            vrContainer?.removeAllViews()
            rootContainer?.removeView(vrContainer)
        } catch (e: Exception) {
            logError("Failed to remove vrContainer", e)
        } finally {
            vrContainer = null
            leftVrImage = null
            rightVrPlayer = null
            rightSink = null
        }
        try {
            ivCaptureSetting.visibility = View.VISIBLE
            btnCalibrate.visibility = View.VISIBLE
            svCaptureMode.visibility = View.VISIBLE
            svCaptureMode.isEnabled = true
            svCaptureMode.setOnTouchListener(null)
        } catch (e: Exception) {
            logError("Failed to restore UI elements", e)
        }
        try {
            capturePlayerView.visibility = View.VISIBLE
            if (capturePlayerView.pipeline == null) {
                runCatching {
                    preparePlayer()
                }.onFailure { logError("Failed to re-prepare main capturePlayerView", it) }
            }
            capturePlayerView.play()
            runCatching {
                instaCameraManager.setPipeline(capturePlayerView.pipeline)
            }.onFailure { logError("Failed to restore camera pipeline to main player", it) }
        } catch (e: Exception) {
            logError("Failed to restore main capturePlayerView", e)
        }
    }

    fun applyOrientation(yawDeg: Float, pitchDeg: Float) {
        lastYawDeg = yawDeg
        lastPitchDeg = pitchDeg
        val player = rightVrPlayer ?: return
        val sink = rightSink
            ?: ReflectiveOrientationSink(player, yawOffsetDeg = vrIpdYawDeg).also { rightSink = it }
        sink.apply(yawDeg, pitchDeg)
    }

    fun setOrientationSnapshot(yawDeg: Float, pitchDeg: Float) {
        lastYawDeg = yawDeg
        lastPitchDeg = pitchDeg
    }

    fun onResume() {
        rightVrPlayer?.play()
        if (rightVrPlayer != null && !isCopying()) startCopyLoop()
    }

    fun onPause() {
        stopCopyLoop()
    }

    fun destroy() {
        stopCopyLoop()
        try {
            rightVrPlayer?.destroy()
        } catch (e: Exception) {
            logError("destroy rightVrPlayer failed", e)
        }
        rightSink = null
    }

    /** Public entry kept for the settings menu in CaptureActivity. */
    fun openVrSettings() {
        if (!isVrMode) return
        showVrSettingsDialog()
    }

    private fun logError(message: String, e: Throwable?) {
        logger.e(if (e != null) "$message: ${e.message}" else message)
    }
}
