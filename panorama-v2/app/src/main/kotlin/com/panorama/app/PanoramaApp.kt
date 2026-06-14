package com.panorama.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Hilt entry point: the @HiltAndroidApp trigger generates the SingletonComponent that wires the
 *  ports declared in [com.panorama.app.di.AppModule] into the @HiltViewModel graph. */
@HiltAndroidApp
class PanoramaApp : Application()
