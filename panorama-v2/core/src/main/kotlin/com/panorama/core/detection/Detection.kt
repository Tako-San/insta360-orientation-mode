package com.panorama.core.detection

import dev.romainguy.kotlin.math.Float2

/** A normalized axis-aligned box in [0,1] image space. Optional on a [Detection] — the sidecar
 *  may carry only a centre point. */
data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Domain detection consumed by the FOV / arrow logic. centerNorm is the detection centre in
 *  [0,1] equirect image space, carried as a kotlin-math [Float2] so it composes directly with
 *  [com.panorama.core.calibration.ViewCalibration.detectionDirToWorld]. The wire JSON keeps the
 *  centre as a plain [x,y] list; the parser converts it here. */
data class Detection(
    val centerNorm: Float2,
    val bboxNorm: Rect? = null,
    val label: String? = null,
)
