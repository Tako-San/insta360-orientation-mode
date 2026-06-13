package com.arashivision.sdk.demo.ui.player

import android.app.Activity
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import com.arashivision.sdk.demo.ui.vr.BaseVrManager

/**
 * VR helper for offline spherical playback. Both eyes share the single
 * SphericalGLSurfaceView ([sourceView]) — the right eye is the view itself, the left eye
 * is [leftEyeImage] mirrored from it via the base class PixelCopy loop.
 *
 * Only the offline-specific bits live here (enable/disable, the settings button, which
 * views to copy/scale); everything shared sits in [BaseVrManager].
 */
class LocalVrManager(
    activity: Activity,
    private val sourceView: View,
    private val leftEyeImage: ImageView,
    private val overlaysToHide: List<View>,
    getSensitivity: () -> Float = { 1.2f },
    setSensitivity: (Float) -> Unit = {}
) : BaseVrManager(activity, getSensitivity, setSensitivity) {

    /** Called when VR mode is enabled or disabled, with the new state. */
    var onVrModeChanged: ((Boolean) -> Unit)? = null

    var isVrMode: Boolean = false
        private set(value) {
            if (field != value) {
                field = value
                onVrModeChanged?.invoke(value)
            }
        }

    private var vrSettingsButton: ImageButton? = null

    override fun copySourceView(): View = sourceView
    override fun leftEyeTarget(): ImageView = leftEyeImage
    override fun scaledViews(): List<View> = listOf(leftEyeImage, sourceView)
    override fun eyeViews(): Pair<View, View>? {
        val parent = sourceView.parent as? LinearLayout ?: return null
        if (parent.childCount < 2) return null
        return parent.getChildAt(0) to parent.getChildAt(1)
    }

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
    }

    fun onResume() {
        if (isVrMode && !isCopying()) startCopyLoop()
    }

    fun onPause() {
        stopCopyLoop()
    }

    /** Public entry kept for the long-press handler in the Activity. */
    fun openVrSettings() {
        if (!isVrMode) return
        showVrSettingsDialog()
    }

    private fun ensureVrSettingsButton() {
        if (vrSettingsButton != null) return
        val sourceParent = sourceView.parent as? View ?: return
        val parent = (sourceParent.parent as? ViewGroup) ?: (sourceParent as? ViewGroup) ?: return
        val sizePx = dp(44)
        val marginPx = dp(12)

        val btn = ImageButton(activity).apply {
            setImageResource(android.R.drawable.ic_menu_manage)
            setBackgroundResource(android.R.color.transparent)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            alpha = 0.85f
            layoutParams = ViewGroup.MarginLayoutParams(sizePx, sizePx)
            x = (parent.width - sizePx - marginPx).coerceAtLeast(0).toFloat()
            y = marginPx.toFloat()
            visibility = View.GONE
            setOnClickListener { openVrSettings() }
        }
        parent.addView(btn)
        vrSettingsButton = btn
    }

    private fun updateVrSettingsButtonPosition() {
        val btn = vrSettingsButton ?: return
        val parent = btn.parent as? View ?: return
        val marginPx = dp(12)
        parent.post {
            btn.x = (parent.width - btn.width - marginPx).coerceAtLeast(0).toFloat()
            btn.y = marginPx.toFloat()
        }
    }
}
