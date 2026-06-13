package com.arashivision.sdk.demo.ui.capture

import com.arashivision.sdk.demo.R
import com.arashivision.sdk.demo.ui.capture.CaptureEvent.InitStep.CHECK_SENSOR
import com.arashivision.sdk.demo.ui.capture.CaptureEvent.InitStep.FETCH_CAMERA_OPTIONS
import com.arashivision.sdk.demo.ui.capture.CaptureEvent.InitStep.INIT_SUPPORT_CONFIG
import com.arashivision.sdk.demo.ui.capture.CaptureEvent.InitStep.OPEN_PREVIEW_STREAM

val stepToLoadingTextMap = mapOf(
    CHECK_SENSOR to R.string.capture_init_check_camera_sensor_mode,
    INIT_SUPPORT_CONFIG to R.string.capture_init_init_support_config,
    FETCH_CAMERA_OPTIONS to R.string.capture_init_fetch_camera_options,
    OPEN_PREVIEW_STREAM to R.string.capture_opening_preview_stream
)

val stepToErrorTextMap = mapOf(
    CHECK_SENSOR to R.string.capture_init_switch_camera_sensor_mode_failed,
    INIT_SUPPORT_CONFIG to R.string.capture_init_init_support_config_failed,
    FETCH_CAMERA_OPTIONS to R.string.capture_init_fetch_camera_options_failed,
    OPEN_PREVIEW_STREAM to R.string.capture_opening_preview_stream_failed
)
