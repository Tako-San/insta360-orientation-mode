package com.arashivision.sdk.demo.ui.capture.player

import com.arashivision.insta360.basemedia.asset.WindowCropInfo
import com.arashivision.insta360.basemedia.model.offset.OffsetData
import com.arashivision.sdk.demo.pref.Pref
import com.arashivision.sdk.demo.ui.capture.camera.CaptureWindowCrop
import com.arashivision.sdk.demo.ui.capture.camera.PlayerOffsets
import com.arashivision.sdk.demo.ui.capture.camera.StreamResolution
import com.arashivision.sdkmedia.player.capture.CaptureParamsBuilderV2
import com.arashivision.sdkmedia.player.capture.InstaCapturePlayerView
import com.arashivision.sdkmedia.player.config.InstaStabType

/**
 * Confines all SDK player data types (CaptureParamsBuilderV2, OffsetData, WindowCropInfo)
 * to this single class. The Activity interacts exclusively through the PlayerViewSink interface.
 */
class InstaPlayerViewSink(private val view: InstaCapturePlayerView) : PlayerViewSink {

    override val isPlaying: Boolean
        get() = view.isPlaying

    override val currentWindowCrop: CaptureWindowCrop?
        get() = view.windowCropInfo?.let {
            CaptureWindowCrop(it.srcWidth, it.srcHeight, it.desWidth, it.desHeight, it.offsetX, it.offsetY)
        }

    override val currentStabOffset: String?
        get() = view.stabOffset

    override val currentResolution: StreamResolution
        get() = StreamResolution(view.previewWidth, view.previewHeight, view.previewFps)

    override val currentFileType: Int
        get() = view.fileType

    override fun prepare() {
        view.prepare(buildParams())
    }

    override fun applyOffset(playerOffset: PlayerOffsets, stabOffset: String) {
        val od = OffsetData().apply {
            setOffsetV1(playerOffset.offsetV1)
            setOffsetV2(playerOffset.offsetV2)
            setOffsetV3(playerOffset.offsetV3)
            setOffsetV6(playerOffset.offsetV6)
        }
        view.setOffset(od, stabOffset)
    }

    override fun applyWindowCrop(crop: CaptureWindowCrop) {
        view.windowCropInfo = WindowCropInfo().apply {
            srcWidth = crop.srcWidth
            srcHeight = crop.srcHeight
            desWidth = crop.dstWidth
            desHeight = crop.dstHeight
            offsetX = crop.offsetX
            offsetY = crop.offsetY
        }
    }

    override fun applyResolution(res: StreamResolution) {
        view.setPreviewResolution(res.width, res.height, res.fps)
    }

    /**
     * Builds a [CaptureParamsBuilderV2] with the standard settings. Exposed (not part of
     * [PlayerViewSink]) for callers that need to prepare additional SDK player views (e.g. the
     * VR right-eye player), keeping [CaptureParamsBuilderV2] confined to this file.
     */
    fun buildParams(): CaptureParamsBuilderV2 = CaptureParamsBuilderV2().apply {
        stabCacheFrameNum = Pref.getStabCacheFrameNum()
        setStabType(InstaStabType.STAB_TYPE_OFF)
    }
}
