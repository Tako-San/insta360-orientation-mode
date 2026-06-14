package com.panorama.android.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.exoplayer.video.spherical.CameraMotionListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Thin wrapper over a media3 [Player] that decodes video into a [Surface] (the OES texture handed
 *  in by [com.panorama.android.gl.PanoramaGlView.onVideoSurfaceReady]) and exposes playback state
 *  as coroutine flows for the Phase-3 ViewModel.
 *
 *  The constructor takes the [Player] interface, not a concrete ExoPlayer, so unit tests can mock
 *  it; the real engine is assembled by the [create] factory, which is only exercised on-device.
 *
 *  Surface attachment is deliberately decoupled from [open]: the GL surface becomes ready on the
 *  GL thread independently of media preparation, so the caller wires [setVideoSurface] from the
 *  surface-ready callback rather than this wrapper trying to order the two. */
class ExoVideoPlayer(private val player: Player) {

    private val _isPlaying = MutableStateFlow(player.isPlaying)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** Last known playback position. The ViewModel refreshes it from [refreshPosition]; the
     *  detection lookup reads it once per frame. */
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    /** Last known media duration in ms, refreshed alongside the position by [refreshPosition] on the
     *  player's own looper. Exposed as a flow so the (off-main) ViewModel never reads the player
     *  directly — media3 is single-thread-affine and a cross-thread getter throws. */
    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }
        })
    }

    /** Sets the media item from [uri] and prepares the player; does not start playback. */
    fun open(uri: Uri) = onPlayerThread {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
    }

    fun play() = onPlayerThread { player.play() }

    fun pause() = onPlayerThread { player.pause() }

    fun seekTo(positionMs: Long) = onPlayerThread { player.seekTo(positionMs) }

    /** Attaches (or, with null, detaches) the video output [Surface].
     *  The surface becomes ready on the GL thread, but media3 [Player] is single-thread-affine to
     *  its application looper — calling it from any other thread throws "Player is accessed on the
     *  wrong thread". So we hop onto the player's own looper before touching it. */
    fun setVideoSurface(surface: Surface?) = onPlayerThread { player.setVideoSurface(surface) }

    /** Routes decoded-frame metadata to [listener] for SphericalGLSurfaceView's equirect projection.
     *  Hops onto the player looper. These two sinks are declared on [ExoPlayer], not the [Player]
     *  interface this wrapper holds; the cast is safe because the only production player is the
     *  ExoPlayer built by [create], and the spherical view fundamentally requires the ExoPlayer API. */
    fun setVideoFrameMetadataListener(listener: VideoFrameMetadataListener) =
        onPlayerThread { (player as ExoPlayer).setVideoFrameMetadataListener(listener) }

    /** Routes camera-motion (projection) data to [listener], required by SphericalGLSurfaceView.
     *  Hops onto the player looper. Same ExoPlayer-cast rationale as above. */
    fun setCameraMotionListener(listener: CameraMotionListener) =
        onPlayerThread { (player as ExoPlayer).setCameraMotionListener(listener) }

    /** Pulls the current playback position and duration from the player into [positionMs] /
     *  [durationMs]. Called by the ViewModel's poller (off the main thread), so it hops onto the
     *  player's application looper first — media3 is single-thread-affine and reading
     *  currentPosition/duration from any other thread throws "Player is accessed on the wrong
     *  thread". The wrapper owns no scheduling of its own beyond this safe hop. */
    fun refreshPosition() = onPlayerThread { sampleTransport() }

    /** Runs [block] on the player's application looper. media3 [Player] is single-thread-affine to
     *  that looper, and EVERY access (setMediaItem/play/seek/surface/getters) throws "Player is
     *  accessed on the wrong thread" off it. The player is built on a dedicated background looper
     *  (see [create]), so UI-thread callers (open/play/pause/seek) and the GL thread
     *  (setVideoSurface) must all hop here; a caller already on the looper runs inline. */
    private inline fun onPlayerThread(crossinline block: () -> Unit) {
        val looper = player.applicationLooper
        if (looper.thread === Thread.currentThread()) block()
        else Handler(looper).post { block() }
    }

    private fun sampleTransport() {
        _positionMs.value = player.currentPosition
        val dur = player.duration
        if (dur != C.TIME_UNSET) _durationMs.value = dur
    }

    fun release() = onPlayerThread { player.release() }

    companion object {
        /** Builds an [ExoVideoPlayer] over a real [ExoPlayer] on a dedicated background looper.
         *  Not unit-tested.
         *
         *  ExoPlayer.Builder(context).build() makes synchronous binder IPC to the platform media
         *  stack (MediaServer / MediaCodecList enumeration) on the calling thread, which can stall
         *  for tens of seconds (binder timeout) when that stack is slow/contended. Running it on the
         *  main thread froze the UI until it self-released (the ~30s "video opens by itself" stall).
         *  So the player is built and pinned to its own [HandlerThread]: ExoPlayer is thread-affine
         *  to the looper it is built on (its applicationLooper), and callers already hop onto
         *  applicationLooper before touching it (see [setVideoSurface] / [refreshPosition]).
         *
         *  Pair this with an early [PanoramaApp]-time call so the (slow) build happens before the
         *  user reaches the player; this factory only blocks the CALLING thread, never the main one
         *  unless called from main. */
        fun create(context: Context): ExoVideoPlayer {
            val looper = HandlerThread("ExoPlayer").apply { start() }.looper
            // The ENTIRE construction — the ExoPlayer build AND the ExoVideoPlayer wrapper init —
            // must run on this looper: the wrapper's init reads player.isPlaying and calls
            // player.addListener, and media3 is thread-affine to the looper the player was built on,
            // so touching it from any other thread throws "Player is accessed on the wrong thread".
            return buildOn(looper) {
                val player = ExoPlayer.Builder(context).setLooper(looper).build()
                ExoVideoPlayer(player)
            }
        }

        /** Runs [build] on [looper] and waits for the result (ExoPlayer must be constructed — and
         *  first touched — on the looper it will be affine to). Blocks only the CALLING thread, which
         *  must not be the main thread; the prewarm path guarantees that. */
        private inline fun <T> buildOn(looper: Looper, crossinline build: () -> T): T {
            if (Looper.myLooper() === looper) return build()
            var result: T? = null
            val lock = Object()
            Handler(looper).post {
                val r = build()
                synchronized(lock) { result = r; lock.notifyAll() }
            }
            synchronized(lock) { while (result == null) lock.wait() }
            return result!!
        }
    }
}
