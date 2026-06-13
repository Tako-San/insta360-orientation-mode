package com.arashivision.sdk.demo.ui.player

import android.net.Uri
import com.arashivision.sdk.demo.base.BaseViewModel
import com.arashivision.orientation.detection.VideoDetectionFrame
import com.arashivision.orientation.detection.VideoDetectionTimeline
import com.arashivision.orientation.detection.VideoDetectedObject

class LocalSphericalPlayerViewModel : BaseViewModel() {

    var currentVideoUri: Uri? = null
        private set

    var currentJsonUri: Uri? = null
        private set

    private var detectionTimeline: VideoDetectionTimeline? = null

    var sensorRotationEnabled: Boolean = true
        private set

    fun onVideoSelected(uri: Uri) {
        currentVideoUri = uri
    }

    fun onJsonSelected(uri: Uri, timeline: VideoDetectionTimeline) {
        currentJsonUri = uri
        detectionTimeline = timeline
    }

    fun currentDetectionFrame(positionMs: Long): VideoDetectionFrame? =
        detectionTimeline?.frameAt(positionMs)

    fun currentDetections(positionMs: Long): List<VideoDetectedObject> =
        detectionTimeline?.detectionsAt(positionMs).orEmpty()

    fun loadedDetectionFrameCount(): Int = detectionTimeline?.frameCount ?: 0

    fun toggleSensorRotation(): Boolean {
        sensorRotationEnabled = !sensorRotationEnabled
        return sensorRotationEnabled
    }
}
