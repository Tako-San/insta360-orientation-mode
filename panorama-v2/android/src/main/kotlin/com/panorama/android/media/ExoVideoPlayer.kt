package com.panorama.android.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
    fun open(uri: Uri) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
    }

    fun play() = player.play()

    fun pause() = player.pause()

    fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    /** Attaches (or, with null, detaches) the video output [Surface].
     *  The surface becomes ready on the GL thread, but media3 [Player] is single-thread-affine to
     *  its application looper — calling it from any other thread throws "Player is accessed on the
     *  wrong thread". So we hop onto the player's own looper before touching it. */
    fun setVideoSurface(surface: Surface?) {
        val looper = player.applicationLooper
        if (looper.thread === Thread.currentThread()) {
            player.setVideoSurface(surface)
        } else {
            Handler(looper).post { player.setVideoSurface(surface) }
        }
    }

    /** Pulls the current playback position and duration from the player into [positionMs] /
     *  [durationMs]. Called by the ViewModel's poller (off the main thread), so it hops onto the
     *  player's application looper first — media3 is single-thread-affine and reading
     *  currentPosition/duration from any other thread throws "Player is accessed on the wrong
     *  thread". The wrapper owns no scheduling of its own beyond this safe hop. */
    fun refreshPosition() {
        val looper = player.applicationLooper
        if (looper.thread === Thread.currentThread()) {
            sampleTransport()
        } else {
            Handler(looper).post { sampleTransport() }
        }
    }

    private fun sampleTransport() {
        _positionMs.value = player.currentPosition
        val dur = player.duration
        if (dur != C.TIME_UNSET) _durationMs.value = dur
    }

    fun release() = player.release()

    companion object {
        /** Builds an [ExoVideoPlayer] over a real [ExoPlayer]. Not unit-tested. */
        fun create(context: Context): ExoVideoPlayer =
            ExoVideoPlayer(ExoPlayer.Builder(context).build())
    }
}
