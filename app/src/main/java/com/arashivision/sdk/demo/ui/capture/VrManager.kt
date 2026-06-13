package com.arashivision.sdk.demo.ui.capture

import android.app.Activity
import android.graphics.Bitmap as AndroidBitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.view.drawToBitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.arashivision.sdk.demo.ext.instaCameraManager
import com.arashivision.sdkmedia.player.capture.InstaCapturePlayerView
import com.arashivision.sdkmedia.player.listener.PlayerViewListener
import com.elvishew.xlog.Logger
import com.elvishew.xlog.XLog

import android.app.AlertDialog
import android.util.TypedValue
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.view.Gravity
import android.view.ViewGroup.MarginLayoutParams

/**
VrManager — отдельный класс, который включает/выключает VR-режим.

Этот класс использует копирование битмапов из правого плеера в ImageView слева.
 */
class VrManager(
    private val activity: Activity,
    private val rootContainer: ViewGroup?,
    private val capturePlayerView: InstaCapturePlayerView,
    private val svCaptureMode: View,
    private val ivCaptureSetting: View,
    private val btnCalibrate: View,
    private val calibrateGyro: () -> Unit = {},
    private val getSensitivity: () -> Float = { 1.2f },
    private val setSensitivity: (Float) -> Unit = {}
) {
    private val logger: Logger = XLog.tag("VrManager").build()
    var isVrMode: Boolean = false
        private set
    private var vrContainer: ViewGroup? = null
    private var leftVrImage: ImageView? = null
    private var rightVrPlayer: InstaCapturePlayerView? = null
    private var rightSink: com.arashivision.sdk.demo.ui.player.ReflectiveOrientationSink? = null
    private var reusableBitmap: AndroidBitmap? = null
    private var compositeBitmap: AndroidBitmap? = null
    private val handler = Handler(Looper.getMainLooper())
    private var copyRunnable: Runnable? = null
    private var copying = false
    private val copyIntervalMs: Long = 33L // ~30 fps
    private val vrIpdYawDeg: Float = 3.0f
    private var eyeScale: Float = 0.7f
    private var eyeSpacingPx: Int = -400
    private var lastYawDeg: Float = 0f
    private var lastPitchDeg: Float = 0f

    init {
        applyVrAdjustments()
        logger.i("VrManager created")
    }

    fun toggleVrMode() {
        if (isVrMode) disableVrMode() else enableVrMode()
    }

    fun enableVrMode() {
        if (isVrMode) {
            return
        }
        logger.i("enableVrMode: starting")
        isVrMode = true
        try {
            capturePlayerView.visibility = View.INVISIBLE
        } catch (e: Exception) {
            logger.e("Failed to hide main capturePlayerView: ${e.message}")
        }
        if (rootContainer == null) {
            logger.e("enableVrMode: rootContainer is null! cannot add vr views")
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
            logger.e("Failed to create/add vrContainer: ${e.message}")
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
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                lp.marginEnd = 0
                lp.marginStart = 0
                layoutParams = lp
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.BLACK)
                isClickable = false
                isFocusable = false
            }
            contentLinear.addView(leftVrImage)
            logger.d("Left ImageView added")
        } catch (e: Exception) {
            logger.e("Failed to create leftVrImage: ${e.message}")
        }

        try {
            rightVrPlayer = InstaCapturePlayerView(activity).apply {
                setLifecycle((activity as? androidx.fragment.app.FragmentActivity)?.lifecycle)
                val lp = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1f
                )
                lp.marginStart = 0
                lp.marginEnd = 0
                layoutParams = lp
                keepScreenOn = true
            }
            contentLinear.addView(rightVrPlayer)
            rightVrPlayer?.setPlayerViewListener(object : PlayerViewListener {
                override fun onFirstFrameRender() {
                    logger.i("rightVrPlayer:onFirstFrameRender")
                    startCopyLoop()
                }
                override fun onLoadingFinish() {
                    try {
                        instaCameraManager.setPipeline(rightVrPlayer!!.pipeline)
                    } catch (e: Exception) {
                        logger.e("Failed to set pipeline to right player: ${e.message}")
                    }
                }
                override fun onReleaseCameraPipeline() {
                    try {
                        instaCameraManager.setPipeline(null)
                    } catch (e: Exception) {
                        logger.e("Failed to release pipeline: ${e.message}")
                    }
                }
            })

            try {
                val params = (activity as? CaptureActivity)?.viewModel?.getCaptureParams()
                rightVrPlayer?.prepare(params)
            } catch (e: Exception) {
                logger.w("Unable to obtain capture params to prepare right player: ${e.message}")
            }
            rightVrPlayer?.play()
        } catch (e: Exception) {
            logger.e("Failed to create or start rightVrPlayer: ${e.message}")
        }

        try {
            val sizeDp = 44
            val sizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, sizeDp.toFloat(), activity.resources.displayMetrics).toInt()
            val settingsBtn = ImageButton(activity).apply {
                setImageResource(android.R.drawable.ic_menu_manage)
                setBackgroundResource(android.R.color.transparent)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val flp = FrameLayout.LayoutParams(sizePx, sizePx)
                flp.gravity = Gravity.END or Gravity.TOP
                val marginDp = 12
                val marginPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, marginDp.toFloat(), activity.resources.displayMetrics).toInt()
                flp.setMargins(marginPx, marginPx, marginPx, marginPx)
                layoutParams = flp
                alpha = 0.85f
            }
            vrContainer?.addView(settingsBtn)
            settingsBtn.setOnClickListener {
                showVrSettingsDialog()
            }
        } catch (e: Exception) {
            logger.e("Failed to add VR settings button: ${e.message}")
        }

        try {
            ivCaptureSetting.visibility = View.GONE
            btnCalibrate.visibility = View.GONE
            svCaptureMode.visibility = View.GONE
            svCaptureMode.isEnabled = false
            svCaptureMode.setOnTouchListener { _, _ -> true }
        } catch (e: Exception) {
            logger.e("Failed to hide UI elements: ${e.message}")
        }
        applyVrAdjustments()
        logger.i("enableVrMode: finished")
    }

    fun disableVrMode() {
        if (!isVrMode) {
            logger.w("disableVrMode called but not in VR mode")
            return
        }
        logger.i("disableVrMode: starting")
        isVrMode = false
        stopCopyLoop()
        try {
            rightVrPlayer?.destroy()
        } catch (e: Exception) {
            logger.e("Failed to destroy rightVrPlayer: ${e.message}")
        }
        try {
            leftVrImage?.setImageBitmap(null)
        } catch (e: Exception) {
            logger.e("Failed to clear left image bitmap: ${e.message}")
        }
        reusableBitmap?.let {
            it.recycle()
            reusableBitmap = null
        }
        compositeBitmap?.let {
            it.recycle()
            compositeBitmap = null
        }
        try {
            vrContainer?.removeAllViews()
            rootContainer?.removeView(vrContainer)
            logger.d("vrContainer removed from root")
        } catch (e: Exception) {
            logger.e("Failed to remove vrContainer: ${e.message}")
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
            logger.d("UI elements restored")
        } catch (e: Exception) {
            logger.e("Failed to restore UI elements: ${e.message}")
        }
        try {
            capturePlayerView.visibility = View.VISIBLE
            val currentPipeline = capturePlayerView.pipeline
            if (currentPipeline == null) {
                runCatching {
                    val params = (activity as? CaptureActivity)?.viewModel?.getCaptureParams()
                    if (params != null) {
                        capturePlayerView.prepare(params)
                    }
                }.onFailure {
                    logger.w("Failed to re-prepare main capturePlayerView: ${it.message}")
                }
            }
            capturePlayerView.play()
            runCatching {
                instaCameraManager.setPipeline(capturePlayerView.pipeline)
            }.onFailure {
                logger.w("Failed to restore camera pipeline to main player: ${it.message}")
            }
            logger.d("Main capturePlayerView shown and play() called")
        } catch (e: Exception) {
            logger.e("Failed to restore main capturePlayerView: ${e.message}")
        }
        logger.i("disableVrMode: finished")
    }

    fun applyOrientation(yawDeg: Float, pitchDeg: Float) {
        lastYawDeg = yawDeg
        lastPitchDeg = pitchDeg
        val player = rightVrPlayer ?: return
        val sink = rightSink
            ?: com.arashivision.sdk.demo.ui.player.ReflectiveOrientationSink(player, yawOffsetDeg = vrIpdYawDeg)
                .also { rightSink = it }
        sink.apply(yawDeg, pitchDeg)
    }

    fun setOrientationSnapshot(yawDeg: Float, pitchDeg: Float) {
        lastYawDeg = yawDeg
        lastPitchDeg = pitchDeg
    }

    fun onResume() {
        logger.d("VrManager.onResume called")
        rightVrPlayer?.play()
        if (rightVrPlayer != null && !copying) {
            startCopyLoop()
        }
    }

    fun onPause() {
        logger.d("VrManager.onPause called")
        stopCopyLoop()
    }

    fun destroy() {
        logger.i("VrManager.destroy called")
        stopCopyLoop()
        try {
            rightVrPlayer?.destroy()
        } catch (e: Exception) {
            logger.w("destroy rightVrPlayer failed: ${e.message}")
        }
        rightSink = null
    }

    // --------- copy loop (bitmap from right player -> left ImageView) ---------
    private var pixelCopyInProgress = false
    private fun findSurfaceView(v: View): SurfaceView? {
        if (v is SurfaceView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val found = findSurfaceView(v.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }
    private fun findTextureView(v: View): TextureView? {
        if (v is TextureView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val found = findTextureView(v.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }
    private fun startCopyLoop() {
        if (copying) {
            logger.d("startCopyLoop: already copying")
            return
        }
        val src = rightVrPlayer
        val dst = leftVrImage
        if (src == null || dst == null) {
            logger.w("startCopyLoop: src or dst is null (src=${src == null}, dst=${dst == null})")
            return
        }
        val width = src.width
        val height = src.height
        if (width <= 0 || height <= 0) {
            logger.w("Invalid src dimensions: ${width}x${height} - aborting copy loop")
            return
        }
        reusableBitmap = AndroidBitmap.createBitmap(width, height, AndroidBitmap.Config.ARGB_8888)
        copying = true
        copyRunnable = object : Runnable {
            override fun run() {
                try {
                    val renderView = findSurfaceView(src) ?: findTextureView(src)
                    if (renderView == null) {
                        logger.e("No SurfaceView or TextureView found in src - falling back to drawToBitmap")
                        var bmp: AndroidBitmap? = null
                        try {
                            bmp = src.drawToBitmap()
                            if (!(bmp.width > 0 && bmp.height > 0)) {
                                logger.w("drawToBitmap returned empty bitmap w=${bmp.width} h=${bmp.height}")
                                bmp.recycle()
                                bmp = null
                            }
                        } catch (e: Exception) {
                            logger.w("drawToBitmap exception: ${e.message}")
                        }
                        processAndSetBitmap(bmp, dst)
                        bmp?.recycle()
                    } else {
                        var bmp: AndroidBitmap? = null
                        if (renderView is SurfaceView) {
                            val surface = renderView.holder.surface
                            if (surface == null || !surface.isValid) {
                                logger.e("Invalid surface for PixelCopy")
                            } else {
                                pixelCopyInProgress = true
                                PixelCopy.request(surface, reusableBitmap!!, { result ->
                                    pixelCopyInProgress = false
                                    if (result == PixelCopy.SUCCESS) {
                                        bmp = reusableBitmap
                                        processAndSetBitmap(bmp, dst)
                                    } else {
                                        logger.e("PixelCopy failed with code: $result")
                                    }
                                    if (copying) handler.postDelayed(this, copyIntervalMs)
                                }, handler)
                                return
                            }
                        } else if (renderView is TextureView) {
                            try {
                                bmp = renderView.getBitmap(width, height)
                            } catch (e: Exception) {
                                logger.e("TextureView getBitmap failed: ${e.message}")
                            }
                        }
                        processAndSetBitmap(bmp, dst)
                        bmp?.recycle()
                    }
                } catch (t: Throwable) {
                    logger.e("copy loop throwable: ${t.message}")
                } finally {
                    if (copying && !pixelCopyInProgress) {
                        handler.postDelayed(this, copyIntervalMs)
                    }
                }
            }
        }
        handler.post(copyRunnable!!)
        logger.i("Copy loop started (interval=${copyIntervalMs}ms)")
    }

    private fun processAndSetBitmap(bmp: AndroidBitmap?, dst: ImageView) {
        if (bmp == null) return
        try {
            val finalBmp = if (bmp.hasAlpha()) {
                if (compositeBitmap == null || compositeBitmap!!.width != bmp.width || compositeBitmap!!.height != bmp.height) {
                    compositeBitmap?.recycle()
                    compositeBitmap = AndroidBitmap.createBitmap(bmp.width, bmp.height, AndroidBitmap.Config.ARGB_8888)
                }
                val canvas = Canvas(compositeBitmap!!)
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(bmp, 0f, 0f, null)
                compositeBitmap
            } else {
                logger.d("Using original bitmap (opaque)")
                bmp
            }
            dst.setImageBitmap(finalBmp)
            dst.invalidate()
            //logger.i("setImageBitmap OK. dst.visible=${dst.visibility == View.VISIBLE} dst.alpha=${dst.alpha} bmp.size=${finalBmp?.width}x${finalBmp?.height}")
        } catch (e: Exception) {
            logger.e("processAndSetBitmap failed: ${e.message}")
        }
    }

    private fun stopCopyLoop() {
        if (!copying) return
        copying = false
        try {
            copyRunnable?.let { handler.removeCallbacks(it) }
            copyRunnable = null
            reusableBitmap?.recycle()
            reusableBitmap = null
            logger.i("Copy loop stopped")
        } catch (e: Exception) {
            logger.e("stopCopyLoop failed: ${e.message}")
        }
    }

    fun showVrSettingsDialog() {
        if (!isVrMode) return
        val dialogRoot = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, activity.resources.displayMetrics).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val scaleLabel = TextView(activity).apply {
            text = "Scale (size): ${"%.2f".format(eyeScale)}"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val scaleSeek = SeekBar(activity).apply {
            max = 100
            progress = ((eyeScale - 0.5f) * 100).toInt().coerceIn(0, 100)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val spacingLabel = TextView(activity).apply {
            text = "Spacing (px): $eyeSpacingPx"
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, activity.resources.displayMetrics).toInt()
            layoutParams = lp
        }
        val spacingSeek = SeekBar(activity).apply {
            val maxDp = 200
            val maxPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, maxDp.toFloat(), activity.resources.displayMetrics).toInt()
            max = maxPx * 2
            progress = (eyeSpacingPx + maxPx).coerceIn(0, max)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val sensLabel = TextView(activity).apply {
            val currentSens = getSensitivity()
            text = "Sensitivity: ${"%.2f".format(currentSens)}"
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, activity.resources.displayMetrics).toInt()
            layoutParams = lp
        }
        val sensSeek = SeekBar(activity).apply {
            // map 0..200 -> 0.00..2.00 (0.01 step)
            max = 200
            progress = ( (getSensitivity() * 100f).toInt() ).coerceIn(0, max) // стартовое положение
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        dialogRoot.addView(scaleLabel)
        dialogRoot.addView(scaleSeek)
        dialogRoot.addView(spacingLabel)
        dialogRoot.addView(spacingSeek)
        dialogRoot.addView(sensLabel)
        dialogRoot.addView(sensSeek)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("VR: Adjust eyes")
            .setView(dialogRoot)
            .setPositiveButton("OK", null)
            .create()

        scaleSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                eyeScale = 0.5f + progress.toFloat() / 100f
                scaleLabel.text = "Scale (size): ${"%.2f".format(eyeScale)}"
                applyVrAdjustments()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        spacingSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val maxDp = 200
                val maxPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, maxDp.toFloat(), activity.resources.displayMetrics).toInt()
                eyeSpacingPx = progress - maxPx
                spacingLabel.text = "Spacing (px): ${eyeSpacingPx}"
                applyVrAdjustments()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        sensSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val newSens = progress.toFloat() / 100f
                setSensitivity(newSens)
                sensLabel.text = "Sensitivity: ${"%.2f".format(newSens)}"
                applyVrAdjustments()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        dialog.show()
    }

    private fun applyVrAdjustments() {
        try {
            leftVrImage?.let { iv ->
                iv.scaleX = eyeScale
                iv.scaleY = eyeScale
            }
            rightVrPlayer?.let { pv ->
                pv.scaleX = eyeScale
                pv.scaleY = eyeScale
            }

            val parentLinear = (vrContainer?.getChildAt(0) as? LinearLayout)
            if (parentLinear != null && parentLinear.childCount >= 2) {
                val left = parentLinear.getChildAt(0)
                val right = parentLinear.getChildAt(1)
                val half = (eyeSpacingPx / 2)

                fun setMarginStartEnd(view: View, start: Int? = null, end: Int? = null) {
                    val lp = view.layoutParams
                    when (lp) {
                        is LinearLayout.LayoutParams -> {
                            if (start != null) lp.marginStart = start
                            if (end != null) lp.marginEnd = end
                            view.layoutParams = lp
                        }
                        is ViewGroup.MarginLayoutParams -> {
                            if (start != null) lp.marginStart = start
                            if (end != null) lp.marginEnd = end
                            view.layoutParams = lp
                        }
                        else -> {
                            val newLp = ViewGroup.MarginLayoutParams(lp)
                            if (start != null) newLp.marginStart = start
                            if (end != null) newLp.marginEnd = end
                            view.layoutParams = newLp
                        }
                    }
                }

                setMarginStartEnd(left, end = half)
                setMarginStartEnd(right, start = half)

                parentLinear.requestLayout()
            }
        } catch (e: Exception) {
            logger.e("applyVrAdjustments failed: ${e.message}")
        }
    }
}
