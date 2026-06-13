package com.arashivision.sdk.demo.ui.player

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.elvishew.xlog.XLog

/**
 * Local VR helper for offline spherical playback.
 */
class LocalVrManager(
    private val activity: Activity,
    private val sourceView: View,
    private val leftEyeImage: ImageView,
    private val overlaysToHide: List<View>,
    private val getSensitivity: () -> Float = { 1.2f },
    private val setSensitivity: (Float) -> Unit = {}
) {
    private val logger = XLog.tag("LocalVrManager").build()

    /** Called when VR mode is enabled or disabled, with the new state. */
    var onVrModeChanged: ((Boolean) -> Unit)? = null

    var isVrMode: Boolean = false
        private set(value) {
            if (field != value) {
                field = value
                onVrModeChanged?.invoke(value)
            }
        }

    private var copying = false
    private var copyRunnable: Runnable? = null
    private var pixelCopyInProgress = false
    private val handler = Handler(Looper.getMainLooper())
    private val copyIntervalMs = 33L

    private var reusableBitmap: Bitmap? = null
    private var compositeBitmap: Bitmap? = null

    private var eyeScale: Float = 0.70f
    private var eyeSpacingPx: Int = -400
    private var vrSettingsButton: ImageButton? = null

    fun toggleVrMode() {
        if (isVrMode) disableVrMode() else enableVrMode()
    }

    fun enableVrMode() {
        if (isVrMode) return
        isVrMode = true
        leftEyeImage.visibility = View.VISIBLE
        overlaysToHide.forEach { it.visibility = View.GONE }
        ensureVrSettingsButton()
        vrSettingsButton?.visibility = View.VISIBLE
        updateVrSettingsButtonPosition()
        sourceView.post {
            if (!isVrMode) return@post
            applyVrAdjustments()
            restartCopyLoop()
        }
    }

    fun disableVrMode() {
        if (!isVrMode) return
        isVrMode = false
        overlaysToHide.forEach { it.visibility = View.VISIBLE }
        vrSettingsButton?.visibility = View.GONE
        leftEyeImage.visibility = View.GONE
        stopCopyLoop()
        leftEyeImage.setImageBitmap(null)
    }

    fun onResume() {
        if (isVrMode && !copying) startCopyLoop()
    }

    fun onPause() {
        stopCopyLoop()
    }

    private fun ensureVrSettingsButton() {
        if (vrSettingsButton != null) return
        val sourceParent = sourceView.parent as? View ?: return
        val parent = (sourceParent.parent as? ViewGroup) ?: (sourceParent as? ViewGroup) ?: return
        val sizeDp = 44
        val marginDp = 12
        val sizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, sizeDp.toFloat(), activity.resources.displayMetrics).toInt()
        val marginPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, marginDp.toFloat(), activity.resources.displayMetrics).toInt()

        val btn = ImageButton(activity).apply {
            setImageResource(android.R.drawable.ic_menu_manage)
            setBackgroundResource(android.R.color.transparent)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            alpha = 0.85f
            layoutParams = ViewGroup.MarginLayoutParams(sizePx, sizePx)
            x = (parent.width - sizePx - marginPx).coerceAtLeast(0).toFloat()
            y = marginPx.toFloat()
            visibility = View.GONE
            setOnClickListener { showVrSettingsDialog() }
        }
        parent.addView(btn)
        vrSettingsButton = btn
    }

    private fun updateVrSettingsButtonPosition() {
        val btn = vrSettingsButton ?: return
        val parent = btn.parent as? View ?: return
        val marginPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, activity.resources.displayMetrics).toInt()
        parent.post {
            btn.x = (parent.width - btn.width - marginPx).coerceAtLeast(0).toFloat()
            btn.y = marginPx.toFloat()
        }
    }

    fun showVrSettingsDialog() {
        if (!isVrMode) return

        val dialogRoot = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, activity.resources.displayMetrics).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val scaleLabel = TextView(activity).apply { text = "Scale (size): ${"%.2f".format(eyeScale)}" }
        val scaleSeek = SeekBar(activity).apply {
            max = 100
            progress = ((eyeScale - 0.5f) * 100).toInt().coerceIn(0, 100)
        }

        val spacingLabel = TextView(activity).apply { text = "Spacing (px): $eyeSpacingPx" }
        val spacingSeek = SeekBar(activity).apply {
            val maxDp = 200
            val maxPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, maxDp.toFloat(), activity.resources.displayMetrics).toInt()
            max = maxPx * 2
            progress = (eyeSpacingPx + maxPx).coerceIn(0, max)
        }

        val sensLabel = TextView(activity).apply { text = "Sensitivity: ${"%.2f".format(getSensitivity())}" }
        val sensSeek = SeekBar(activity).apply {
            max = 200
            progress = (getSensitivity() * 100f).toInt().coerceIn(0, max)
        }

        dialogRoot.addView(scaleLabel)
        dialogRoot.addView(scaleSeek)
        dialogRoot.addView(spacingLabel)
        dialogRoot.addView(spacingSeek)
        dialogRoot.addView(sensLabel)
        dialogRoot.addView(sensSeek)

        AlertDialog.Builder(activity)
            .setTitle("VR: Adjust eyes")
            .setView(dialogRoot)
            .setPositiveButton("OK", null)
            .create().also { dialog ->
                scaleSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        eyeScale = 0.5f + progress / 100f
                        scaleLabel.text = "Scale (size): ${"%.2f".format(eyeScale)}"
                        applyVrAdjustments()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar?) = Unit
                })
                spacingSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        val maxDp = 200
                        val maxPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, maxDp.toFloat(), activity.resources.displayMetrics).toInt()
                        eyeSpacingPx = progress - maxPx
                        spacingLabel.text = "Spacing (px): $eyeSpacingPx"
                        applyVrAdjustments()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar?) = Unit
                })
                sensSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        val newSens = progress.toFloat() / 100f
                        setSensitivity(newSens)
                        sensLabel.text = "Sensitivity: ${"%.2f".format(newSens)}"
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar?) = Unit
                })
                dialog.show()
            }
    }

    private fun applyVrAdjustments() {
        leftEyeImage.scaleX = eyeScale
        leftEyeImage.scaleY = eyeScale
        sourceView.scaleX = eyeScale
        sourceView.scaleY = eyeScale

        val parentLinear = sourceView.parent as? LinearLayout ?: return
        if (parentLinear.childCount < 2) return

        val left = parentLinear.getChildAt(0)
        val right = parentLinear.getChildAt(1)
        val half = eyeSpacingPx / 2

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
            }
        }

        setMarginStartEnd(left, end = half)
        setMarginStartEnd(right, start = half)
        parentLinear.requestLayout()
    }

    private fun findSurfaceView(v: View): SurfaceView? {
        if (v is SurfaceView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findSurfaceView(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun findTextureView(v: View): TextureView? {
        if (v is TextureView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findTextureView(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun restartCopyLoop() {
        stopCopyLoop()
        startCopyLoop()
    }
    private fun startCopyLoop() {
        if (copying) return
        val width = sourceView.width
        val height = sourceView.height
        if (width <= 0 || height <= 0) {
            logger.w("startCopyLoop: invalid source size ${width}x$height")
            return
        }

        reusableBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        copying = true

        copyRunnable = object : Runnable {
            override fun run() {
                try {
                    val srcSurface = findSurfaceView(sourceView)
                    val srcTexture = if (srcSurface == null) findTextureView(sourceView) else null

                    if (srcSurface != null) {
                        val surface = srcSurface.holder.surface
                        if (surface != null && surface.isValid) {
                            pixelCopyInProgress = true
                            PixelCopy.request(surface, reusableBitmap!!, { result ->
                                pixelCopyInProgress = false
                                if (result == PixelCopy.SUCCESS) {
                                    processAndSetBitmap(reusableBitmap)
                                }
                                if (copying) handler.postDelayed(this, copyIntervalMs)
                            }, handler)
                            return
                        }
                    }

                    if (srcTexture != null) {
                        val bmp = srcTexture.getBitmap(width, height)
                        processAndSetBitmap(bmp)
                        bmp?.recycle()
                    }
                } catch (t: Throwable) {
                    logger.e("copy loop error: ${t.message}")
                } finally {
                    if (copying && !pixelCopyInProgress) {
                        handler.postDelayed(this, copyIntervalMs)
                    }
                }
            }
        }

        handler.post(copyRunnable!!)
    }

    private fun processAndSetBitmap(bmp: Bitmap?) {
        bmp ?: return
        val finalBmp = if (bmp.hasAlpha()) {
            if (compositeBitmap == null || compositeBitmap!!.width != bmp.width || compositeBitmap!!.height != bmp.height) {
                compositeBitmap?.recycle()
                compositeBitmap = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            }
            val canvas = Canvas(compositeBitmap!!)
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bmp, 0f, 0f, null)
            compositeBitmap
        } else {
            bmp
        }
        leftEyeImage.setImageBitmap(finalBmp)
        leftEyeImage.invalidate()
    }

    private fun stopCopyLoop() {
        if (!copying) return
        copying = false
        copyRunnable?.let { handler.removeCallbacks(it) }
        copyRunnable = null
        reusableBitmap?.recycle()
        reusableBitmap = null
    }
}
