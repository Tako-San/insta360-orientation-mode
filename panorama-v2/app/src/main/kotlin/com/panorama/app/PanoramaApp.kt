package com.panorama.app

import android.app.Application
import com.panorama.android.media.ExoVideoPlayer
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider
import kotlin.concurrent.thread

/** Hilt entry point: the @HiltAndroidApp trigger generates the SingletonComponent that wires the
 *  ports declared in [com.panorama.app.di.AppModule] into the @HiltViewModel graph.
 *
 *  Prewarms the @Singleton [ExoVideoPlayer] on a background thread at startup. Building the
 *  underlying ExoPlayer does synchronous binder IPC into the platform media stack, which can stall
 *  for tens of seconds; doing it lazily on first injection ran it on the main thread during the
 *  player route's composition and froze the UI until it self-released (the "video opens by itself
 *  after ~30s" stall). Forcing the singleton to be constructed off-main here means it is already
 *  built by the time the user picks a video, so navigation/composition never waits on it. Using a
 *  [Provider] keeps this a fire-and-forget warmup; the same singleton is later injected into the
 *  ViewModel. */
@HiltAndroidApp
class PanoramaApp : Application() {

    @Inject
    lateinit var exoVideoPlayer: Provider<ExoVideoPlayer>

    override fun onCreate() {
        super.onCreate()
        thread(name = "ExoPlayer-prewarm", isDaemon = true) { exoVideoPlayer.get() }
    }
}
