package com.arashivision.sdk.demo.ui.vr

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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.arashivision.orientation.vr.StereoEyeLayout
import com.arashivision.orientation.vr.eyeScaleToSeekProgress
import com.arashivision.orientation.vr.seekProgressToEyeScale
import com.arashivision.orientation.vr.seekProgressToSpacingPx
import com.arashivision.orientation.vr.spacingPxToSeekProgress
import com.elvishew.xlog.Logger
import com.elvishew.xlog.XLog

/**
 * Shared base for the two VR split-screen managers (capture and offline player).
 *
 * Holds everything that was duplicated between VrManager and LocalVrManager: the
 * ~30fps PixelCopy loop that mirrors the right-eye render into the left-eye ImageView,
 * the surface/texture lookup, the alpha→black compositing, the VR settings dialog, and
 * the eye scale/spacing adjustment math (delegated to the pure [StereoEyeLayout] in :lib).
 *
 * Subclasses provide what differs: which view is the copy source, which ImageView is the
 * left eye, and the two eye views to apply margins to. Enable/disable and the view-tree
 * construction stay in the subclass because they differ structurally (capture builds a
 * second InstaCapturePlayerView; offline reuses the single SphericalGLSurfaceView).
 */
abstract class BaseVrManager(
    protected val activity: Activity,
    private val getSensitivity: () -> Float,
    private val setSensitivity: (Float) -> Unit,
    protected val logger: Logger = XLog.tag("VrManager").build()
) {
    protected val handler = Handler(Looper.getMainLooper())
    protected val copyIntervalMs: Long = 33L // ~30 fps

    protected var eyeScale: Float = 0.70f
    protected var eyeSpacingPx: Int = -400

    private var copying = false
    private var copyRunnable: Runnable? = null
    private var pixelCopyInProgress = false
    private var reusableBitmap: Bitmap? = null
    private var compositeBitmap: Bitmap? = null

    // --- extension points the subclass must provide ---

    /** The view whose rendered frames are copied into the left eye. */
    protected abstract fun copySourceView(): View?

    /** The ImageView that displays the mirrored left-eye frames. */
    protected abstract fun leftEyeTarget(): ImageView?

    /** The two eye views (left, right) to which inner margins are applied, or null. */
    protected abstract fun eyeViews(): Pair<View, View>?

    /** Views to scale by eyeScale (e.g. left ImageView + right player/source). */
    protected abstract fun scaledViews(): List<View>

    // --- shared copy loop ---

    protected fun restartCopyLoop() {
        stopCopyLoop()
        startCopyLoop()
    }

    protected fun startCopyLoop() {
        if (copying) {
            logger.d("startCopyLoop: already copying")
            return
        }
        val src = copySourceView()
        val dst = leftEyeTarget()
        if (src == null || dst == null) {
            logger.w("startCopyLoop: src or dst is null (src=${src == null}, dst=${dst == null})")
            return
        }
        val width = src.width
        val height = src.height
        if (width <= 0 || height <= 0) {
            logger.w("startCopyLoop: invalid source size ${width}x$height")
            return
        }
        reusableBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        copying = true
        copyRunnable = object : Runnable {
            override fun run() {
                try {
                    val srcSurface = findSurfaceView(src)
                    val srcTexture = if (srcSurface == null) findTextureView(src) else null
                    if (srcSurface != null) {
                        val surface = srcSurface.holder.surface
                        if (surface != null && surface.isValid) {
                            pixelCopyInProgress = true
                            PixelCopy.request(surface, reusableBitmap!!, { result ->
                                pixelCopyInProgress = false
                                if (result == PixelCopy.SUCCESS) {
                                    processAndSetBitmap(reusableBitmap, dst)
                                } else {
                                    logger.e("PixelCopy failed with code: $result")
                                }
                                if (copying) handler.postDelayed(this, copyIntervalMs)
                            }, handler)
                            return
                        } else {
                            logger.e("Invalid surface for PixelCopy")
                        }
                    } else if (srcTexture != null) {
                        var bmp: Bitmap? = null
                        try {
                            bmp = srcTexture.getBitmap(width, height)
                        } catch (e: Exception) {
                            logger.e("TextureView getBitmap failed: ${e.message}")
                        }
                        processAndSetBitmap(bmp, dst)
                        bmp?.recycle()
                    } else {
                        logger.e("No SurfaceView or TextureView found in copy source")
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
        logger.i("Copy loop started (interval=${copyIntervalMs}ms)")
    }

    protected fun stopCopyLoop() {
        if (!copying) return
        copying = false
        try {
            copyRunnable?.let { handler.removeCallbacks(it) }
            copyRunnable = null
            reusableBitmap?.recycle()
            reusableBitmap = null
            leftEyeTarget()?.setImageBitmap(null)
            logger.i("Copy loop stopped")
        } catch (e: Exception) {
            logger.e("stopCopyLoop failed: ${e.message}")
        }
    }

    protected fun isCopying(): Boolean = copying

    private fun processAndSetBitmap(bmp: Bitmap?, dst: ImageView) {
        bmp ?: return
        try {
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
            dst.setImageBitmap(finalBmp)
            dst.invalidate()
        } catch (e: Exception) {
            logger.e("processAndSetBitmap failed: ${e.message}")
        }
    }

    protected fun findSurfaceView(v: View): SurfaceView? {
        if (v is SurfaceView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findSurfaceView(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    protected fun findTextureView(v: View): TextureView? {
        if (v is TextureView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findTextureView(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    // --- shared eye adjustments ---

    protected fun applyVrAdjustments() {
        try {
            scaledViews().forEach { v ->
                v.scaleX = eyeScale
                v.scaleY = eyeScale
            }
            val (left, right) = eyeViews() ?: return
            val layout = StereoEyeLayout(eyeScale, eyeSpacingPx)
            setMarginStartEnd(left, end = layout.leftEyeMarginEndPx)
            setMarginStartEnd(right, start = layout.rightEyeMarginStartPx)
            (left.parent as? View)?.requestLayout()
        } catch (e: Exception) {
            logger.e("applyVrAdjustments failed: ${e.message}")
        }
    }

    private fun setMarginStartEnd(view: View, start: Int? = null, end: Int? = null) {
        val lp = view.layoutParams
        val margin = when (lp) {
            is LinearLayout.LayoutParams -> lp
            is ViewGroup.MarginLayoutParams -> lp
            else -> ViewGroup.MarginLayoutParams(lp)
        }
        if (start != null) margin.marginStart = start
        if (end != null) margin.marginEnd = end
        view.layoutParams = margin
    }

    // --- shared settings dialog ---

    protected fun showVrSettingsDialog() {
        val dialogRoot = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
        }

        val scaleLabel = TextView(activity).apply { text = scaleText() }
        val scaleSeek = SeekBar(activity).apply {
            max = 100
            progress = eyeScaleToSeekProgress(eyeScale)
        }
        val maxPx = dp(200)
        val spacingLabel = TextView(activity).apply { text = spacingText() }
        val spacingSeek = SeekBar(activity).apply {
            max = maxPx * 2
            progress = spacingPxToSeekProgress(eyeSpacingPx, maxPx)
        }
        val sensLabel = TextView(activity).apply { text = sensText(getSensitivity()) }
        val sensSeek = SeekBar(activity).apply {
            max = 200
            progress = (getSensitivity() * 100f).toInt().coerceIn(0, max)
        }

        listOf(scaleLabel, scaleSeek, spacingLabel, spacingSeek, sensLabel, sensSeek)
            .forEach { dialogRoot.addView(it) }

        AlertDialog.Builder(activity)
            .setTitle("VR: Adjust eyes")
            .setView(dialogRoot)
            .setPositiveButton("OK", null)
            .create().also { dialog ->
                scaleSeek.setOnSeekBarChangeListener(simpleSeek { progress ->
                    eyeScale = seekProgressToEyeScale(progress)
                    scaleLabel.text = scaleText()
                    applyVrAdjustments()
                })
                spacingSeek.setOnSeekBarChangeListener(simpleSeek { progress ->
                    eyeSpacingPx = seekProgressToSpacingPx(progress, maxPx)
                    spacingLabel.text = spacingText()
                    applyVrAdjustments()
                })
                sensSeek.setOnSeekBarChangeListener(simpleSeek { progress ->
                    val newSens = progress.toFloat() / 100f
                    setSensitivity(newSens)
                    sensLabel.text = sensText(newSens)
                })
                dialog.show()
            }
    }

    private fun scaleText() = "Scale (size): ${"%.2f".format(eyeScale)}"
    private fun spacingText() = "Spacing (px): $eyeSpacingPx"
    private fun sensText(v: Float) = "Sensitivity: ${"%.2f".format(v)}"

    protected fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), activity.resources.displayMetrics
        ).toInt()

    private inline fun simpleSeek(crossinline onChange: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) = onChange(progress)
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        }
}
