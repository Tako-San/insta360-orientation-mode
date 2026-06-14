package com.panorama.app.di

import android.content.ContentResolver
import android.content.Context
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import com.panorama.android.detection.SidecarLoader
import com.panorama.android.media.ExoVideoPlayer
import com.panorama.android.sensor.OrientationEngine
import com.panorama.android.sensor.SensorReader
import com.panorama.core.calibration.AxisConvention
import com.panorama.core.projection.EquirectProjection
import com.panorama.core.projection.ProjectionModel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The Hilt SingletonComponent graph for :app.
 *
 *  Two domain ports are true singletons here: [ProjectionModel] (the sphere geometry provider) and
 *  [AxisConvention] (the single calibration knob, spec section 6 "Site A"). [DetectionSource] is
 *  deliberately NOT bound — it depends on the chosen video's sidecar URI, so it is created
 *  per-playback inside the ViewModel via the injected [SidecarLoader].
 *
 *  The Android runtime collaborators ([ExoVideoPlayer], [OrientationEngine], [SidecarLoader]) are
 *  assembled from the application [Context] and provided as singletons so the single Activity +
 *  its ViewModel share one of each. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideProjectionModel(): ProjectionModel = EquirectProjection()

    /** The one place axis signs live; one instance shared by the engine, renderer and arrow logic. */
    @Provides
    @Singleton
    fun provideAxisConvention(): AxisConvention = AxisConvention()

    @Provides
    @Singleton
    fun provideExoVideoPlayer(@ApplicationContext context: Context): ExoVideoPlayer =
        ExoVideoPlayer.create(context)

    @Provides
    @Singleton
    fun provideSidecarLoader(@ApplicationContext context: Context): SidecarLoader =
        SidecarLoader(context.contentResolver)

    /** The sensor pipeline: a real [SensorReader] over the platform [SensorManager], a live
     *  display-rotation supplier so a device re-orientation is picked up per sample, and the shared
     *  [AxisConvention]. start()/stop()/calibrate() are driven from the Activity lifecycle and the
     *  ViewModel. */
    @Provides
    @Singleton
    fun provideOrientationEngine(
        @ApplicationContext context: Context,
        axisConvention: AxisConvention,
    ): OrientationEngine {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return OrientationEngine(
            sampleSource = SensorReader(sensorManager),
            displayRotation = { context.currentDisplayRotation() },
            axisConvention = axisConvention,
        )
    }

    /** Read the rotation via [DisplayManager], NOT Context#getDisplay: this runs from the
     *  application (non-visual) Context on the sensor thread, and Context#getDisplay throws
     *  "not associated with a display" on a non-visual Context (Android 11+). DisplayManager works
     *  from any Context. */
    private fun Context.currentDisplayRotation(): Int {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        return display?.rotation ?: Surface.ROTATION_0
    }
}
