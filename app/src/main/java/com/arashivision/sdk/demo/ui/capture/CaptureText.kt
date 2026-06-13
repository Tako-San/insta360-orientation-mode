package com.arashivision.sdk.demo.ui.capture

import android.content.Context
import com.arashivision.sdk.demo.R
import com.arashivision.sdkcamera.camera.model.AEB
import com.arashivision.sdkcamera.camera.model.BurstCapture
import com.arashivision.sdkcamera.camera.model.CaptureMode
import com.arashivision.sdkcamera.camera.model.CaptureSetting
import com.arashivision.sdkcamera.camera.model.DarkEisType
import com.arashivision.sdkcamera.camera.model.EV
import com.arashivision.sdkcamera.camera.model.EVInterval
import com.arashivision.sdkcamera.camera.model.Exposure
import com.arashivision.sdkcamera.camera.model.GammaMode
import com.arashivision.sdkcamera.camera.model.HdrStatus
import com.arashivision.sdkcamera.camera.model.ILogStatus
import com.arashivision.sdkcamera.camera.model.ISO
import com.arashivision.sdkcamera.camera.model.ISOTopLimit
import com.arashivision.sdkcamera.camera.model.InternalSplicing
import com.arashivision.sdkcamera.camera.model.Interval
import com.arashivision.sdkcamera.camera.model.LiveBitrate
import com.arashivision.sdkcamera.camera.model.PanoExposureMode
import com.arashivision.sdkcamera.camera.model.PhotoResolution
import com.arashivision.sdkcamera.camera.model.RawType
import com.arashivision.sdkcamera.camera.model.RecordDuration
import com.arashivision.sdkcamera.camera.model.RecordResolution
import com.arashivision.sdkcamera.camera.model.Shutter
import com.arashivision.sdkcamera.camera.model.ShutterMode
import com.arashivision.sdkcamera.camera.model.WB
import com.arashivision.sdkcamera.camera.model.proto.PhotoHdrType
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

fun getCaptureModeTextResId(captureMode: CaptureMode): Int? {
    return when (captureMode) {
        CaptureMode.CAPTURE_NORMAL -> R.string.capture_mode_normal_capture
        CaptureMode.HDR_CAPTURE -> R.string.capture_mode_hdr_capture
        CaptureMode.NIGHT_SCENE -> R.string.capture_mode_night_scene
        CaptureMode.BURST -> R.string.capture_mode_burst
        CaptureMode.INTERVAL_SHOOTING -> R.string.capture_mode_interval_shooting
        CaptureMode.STARLAPSE_SHOOTING -> R.string.capture_mode_star_lapse
        CaptureMode.RECORD_NORMAL -> R.string.capture_mode_normal_record
        CaptureMode.BULLETTIME -> R.string.capture_mode_bullet_time
        CaptureMode.TIMELAPSE -> R.string.capture_mode_timelapse
        CaptureMode.HDR_RECORD -> R.string.capture_mode_hdr_record
        CaptureMode.TIME_SHIFT -> R.string.capture_mode_time_shift
        CaptureMode.LOOPER_RECORDING -> R.string.capture_mode_looper_recording
        CaptureMode.LIVE -> R.string.capture_mode_live
        CaptureMode.LIVE_ANIMATION -> R.string.capture_mode_live_animation
        CaptureMode.SUPER_RECORD -> R.string.capture_mode_super_record
        CaptureMode.SLOW_MOTION -> R.string.capture_mode_slow_motion
        CaptureMode.SELFIE_RECORD -> R.string.capture_mode_selfie_record
        CaptureMode.PURE_RECORD -> R.string.capture_mode_pure_record
        CaptureMode.VIDEO_NONE, CaptureMode.PHOTO_NONE -> null
    }
}


fun getCaptureSettingNameResId(captureSetting: CaptureSetting): Int {
    return when (captureSetting) {
        CaptureSetting.EXPOSURE -> R.string.capture_setting_exposure_name
        CaptureSetting.EV -> R.string.capture_setting_ev_name
        CaptureSetting.EV_INTERVAL -> R.string.capture_setting_ev_interval_name
        CaptureSetting.SHUTTER -> R.string.capture_setting_shutter_name
        CaptureSetting.SHUTTER_MODE -> R.string.capture_setting_shutter_mode_name
        CaptureSetting.ISO -> R.string.capture_setting_iso_name
        CaptureSetting.ISO_TOP_LIMIT -> R.string.capture_setting_iso_max_name
        CaptureSetting.RECORD_RESOLUTION -> R.string.capture_setting_record_resolution_name
        CaptureSetting.PHOTO_RESOLUTION -> R.string.capture_setting_photo_resolution_name
        CaptureSetting.WB -> R.string.capture_setting_wb_name
        CaptureSetting.AEB -> R.string.capture_setting_aeb_name
        CaptureSetting.INTERVAL -> R.string.capture_setting_interval_name
        CaptureSetting.GAMMA_MODE -> R.string.capture_setting_gamma_mode_name
        CaptureSetting.RAW_TYPE -> R.string.capture_setting_format_name
        CaptureSetting.RECORD_DURATION -> R.string.capture_setting_record_duration_name
        CaptureSetting.DARK_EIS_ENABLE -> R.string.capture_setting_dark_eis
        CaptureSetting.PANO_EXPOSURE_MODE -> R.string.capture_setting_exposure_isolated
        CaptureSetting.BURST_CAPTURE -> R.string.capture_setting_burst_capture_num_name
        CaptureSetting.INTERNAL_SPLICING -> R.string.capture_setting_internal_splicing_name
        CaptureSetting.HDR_STATUS -> R.string.capture_setting_hdr_status_name
        CaptureSetting.PHOTO_HDR_TYPE -> R.string.capture_setting_photo_hdr_type_name
        CaptureSetting.LIVE_BITRATE -> R.string.capture_setting_live_bitrate
        CaptureSetting.I_LOG -> R.string.capture_setting_i_log
    }
}


fun getCaptureSettingValueName(
    context: Context,
    captureSetting: CaptureSetting,
    value: Any
): String {
    return when (captureSetting) {
        CaptureSetting.EXPOSURE -> when (value as Exposure) {
            Exposure.AUTO -> context.getString(R.string.exposure_auto)
            Exposure.FULL_AUTO -> context.getString(R.string.exposure_full_auto)
            Exposure.ISO_FIRST -> context.getString(R.string.exposure_iso)
            Exposure.SHUTTER_FIRST -> context.getString(R.string.exposure_shutter)
            Exposure.MANUAL -> context.getString(R.string.exposure_manual)
            Exposure.ADAPTIVE -> context.getString(R.string.exposure_isolated)
        }

        CaptureSetting.EV -> {
            val ev = value as EV
            DecimalFormat("##.#", DecimalFormatSymbols(Locale.ENGLISH)).format(ev.nativeValue.toDouble() / 10.0)
        }

        CaptureSetting.EV_INTERVAL -> {
            val evInterval: EVInterval = value as EVInterval
            DecimalFormat("##.#", DecimalFormatSymbols(Locale.ENGLISH)).format(evInterval.nativeValue.toDouble() / 10.0)
        }

        CaptureSetting.SHUTTER -> {
            val shutter: Shutter = value as Shutter
            if (shutter == Shutter.SHUTTER_AUTO) {
                context.getString(R.string.auto)
            } else {
                val split: Array<String> = shutter.name.split("_".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                when (split.size) {
                    2 -> split[1]
                    3 -> split[1] + "/" + split[2]
                    else -> context.getString(R.string.auto)
                }
            }
        }

        CaptureSetting.SHUTTER_MODE -> {
            val shutterMode: ShutterMode = value as ShutterMode
            when (shutterMode) {
                ShutterMode.AUTO -> context.getString(R.string.shutter_mode_auto)
                ShutterMode.SPORT -> context.getString(R.string.shutter_mode_sport)
                ShutterMode.FASTER -> context.getString(R.string.shutter_mode_faster)
            }
        }

        CaptureSetting.ISO -> {
            if(value == ISO.ISO_AUTO){
                context.getString(R.string.auto)
            } else {
                (value as ISO).nativeValue.toString()
            }
        }

        CaptureSetting.ISO_TOP_LIMIT -> {
            val isoTopLimit = value as ISOTopLimit
            if (isoTopLimit.nativeValue > 0) {
                isoTopLimit.nativeValue.toString() + "MAX"
            } else {
                context.getString(R.string.auto)
            }
        }

        CaptureSetting.RECORD_RESOLUTION -> {
            val resolution: RecordResolution = value as RecordResolution
            resolution.width.toString() + "x" + resolution.height + " " + resolution.fps + "fps"
        }

        CaptureSetting.PHOTO_RESOLUTION -> {
            val resolution = value as PhotoResolution
            resolution.width.toString() + "x" + resolution.height
        }

        CaptureSetting.WB -> {
            val wb = value as WB
            if (wb.nativeValue == WB.WB_AUTO.nativeValue) {
                context.getString(R.string.auto)
            } else {
                wb.nativeValue.toString() + "K"
            }
        }

        CaptureSetting.AEB -> (value as AEB).nativeValue.toString()
        CaptureSetting.INTERVAL -> {
            val interval = value as Interval
            val timeS = interval.nativeValue.toDouble() / 1000.0
            val decimalFormat = DecimalFormat("#.#", DecimalFormatSymbols(Locale.ENGLISH))
            if (timeS == 0.0) {
                context.getString(R.string.off)
            } else if (timeS < 60) {
                decimalFormat.format(timeS) + "s"
            } else {
                decimalFormat.format(timeS / 60.0) + "min"
            }
        }

        CaptureSetting.GAMMA_MODE -> {
            val gammaMode = value as GammaMode
            when (gammaMode) {
                GammaMode.STAND -> context.getString(R.string.capture_setting_gamma_mode_stand)
                GammaMode.VIVID -> context.getString(R.string.capture_setting_gamma_mode_vivid)
                GammaMode.LOG -> context.getString(R.string.capture_setting_gamma_mode_log)
                GammaMode.FLAT -> context.getString(R.string.capture_setting_gamma_mode_flat)
            }
        }

        CaptureSetting.RAW_TYPE -> {
            val rawType = value as RawType
            rawType.name.replace("_", "/")
        }

        CaptureSetting.RECORD_DURATION -> {
            val recordDuration: RecordDuration = value as RecordDuration
            if (recordDuration.nativeValue == RecordDuration.DURATION_NO_LIMIT.nativeValue) {
                context.getString(R.string.record_duration_no_limit)
            } else {
                val hour: Int = recordDuration.nativeValue / 3600
                val minute: Int = (recordDuration.nativeValue % 3600) / 60
                val second: Int = recordDuration.nativeValue % 60
                var time = ""
                if (hour != 0) {
                    time = "${hour}h"
                }
                if (minute != 0) {
                    time = "$time${minute}m"
                }
                if (second != 0) {
                    time = "$time${second}s"
                }
                time
            }
        }

        CaptureSetting.DARK_EIS_ENABLE -> {
            val darkEisType: DarkEisType = value as DarkEisType
            when (darkEisType) {
                DarkEisType.ON -> context.getString(R.string.on)
                DarkEisType.OFF -> context.getString(R.string.off)
            }
        }

        CaptureSetting.PANO_EXPOSURE_MODE -> {
            val panoExposureMode = value as PanoExposureMode
            when (panoExposureMode) {
                PanoExposureMode.OFF -> context.getString(R.string.auto)
                PanoExposureMode.LIGHT -> context.getString(R.string.capture_setting_pano_exposure_mode_light)
                PanoExposureMode.LSOLATED -> context.getString(R.string.capture_setting_pano_exposure_mode_lsolated)
                PanoExposureMode.ON -> context.getString(R.string.on)
            }
        }

        CaptureSetting.BURST_CAPTURE -> {
            val burstCapture: BurstCapture = value as BurstCapture
            context.getString(
                R.string.capture_setting_burst_capture_text,
                burstCapture.num,
                burstCapture.time
            )
        }

        CaptureSetting.INTERNAL_SPLICING -> {
            val internalSplicing: InternalSplicing = value as InternalSplicing
            when (internalSplicing) {
                InternalSplicing.ON -> context.getString(R.string.on)
                InternalSplicing.OFF -> context.getString(R.string.off)
            }
        }

        CaptureSetting.HDR_STATUS -> {
            val hdrStatus: HdrStatus = value as HdrStatus
            when (hdrStatus) {
                HdrStatus.ON -> context.getString(R.string.on)
                HdrStatus.OFF -> context.getString(R.string.off)
            }
        }

        CaptureSetting.PHOTO_HDR_TYPE -> {
            val photoHdrType: PhotoHdrType = value as PhotoHdrType
            when (photoHdrType.jsonKey) {
                PhotoHdrType.HDR_ON.jsonKey -> context.getString(R.string.on)
                PhotoHdrType.HDR_AEB.jsonKey -> context.getString(R.string.capture_setting_photo_hdr_type_aeb)
                else -> context.getString(R.string.off)
            }
        }

        CaptureSetting.LIVE_BITRATE -> {
            val liveBitrate: LiveBitrate = value as LiveBitrate
            liveBitrate.nativeValue.toString() + "Mbps"
        }

        CaptureSetting.I_LOG -> {
            val iLogStatus: ILogStatus = value as ILogStatus
            when (iLogStatus) {
                ILogStatus.ON -> context.getString(R.string.on)
                ILogStatus.OFF -> context.getString(R.string.off)
            }
        }
    }
}
