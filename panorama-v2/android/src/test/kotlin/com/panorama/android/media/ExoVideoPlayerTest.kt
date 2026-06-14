package com.panorama.android.media

import android.net.Uri
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Unit tests for [ExoVideoPlayer]. The wrapper takes a media3 [Player] interface (not a concrete
 *  ExoPlayer) so MockK can stand in for the decoder; the real ExoPlayer is built only by the
 *  [ExoVideoPlayer.create] factory, which is exercised on-device, not here. Robolectric supplies
 *  the [Uri] parser and main Looper the wrapper relies on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExoVideoPlayerTest {

    /** Captures the [Player.Listener] registered in init so tests can drive callbacks by hand. */
    private fun mockPlayerWithListener(): Pair<Player, () -> Player.Listener> {
        val player = mockk<Player>(relaxed = true)
        val slot = slot<Player.Listener>()
        every { player.addListener(capture(slot)) } returns Unit
        return player to { slot.captured }
    }

    @Test
    fun `play and pause delegate to player and update isPlaying`() {
        val (player, listener) = mockPlayerWithListener()
        val wrapper = ExoVideoPlayer(player)

        wrapper.play()
        verify { player.play() }

        // The wrapper learns it is playing only from the player callback, not from play() itself.
        listener().onIsPlayingChanged(true)
        assertTrue(wrapper.isPlaying.value)

        wrapper.pause()
        verify { player.pause() }

        listener().onIsPlayingChanged(false)
        assertEquals(false, wrapper.isPlaying.value)
    }

    @Test
    fun `seekTo delegates`() {
        val (player, _) = mockPlayerWithListener()
        val wrapper = ExoVideoPlayer(player)

        wrapper.seekTo(5000)
        verify { player.seekTo(5000) }
    }

    @Test
    fun `open sets media item and prepares`() {
        val (player, _) = mockPlayerWithListener()
        val wrapper = ExoVideoPlayer(player)

        wrapper.open(Uri.parse("file:///video.mp4"))
        verify { player.setMediaItem(any<MediaItem>()) }
        verify { player.prepare() }
    }

    @Test
    fun `setVideoSurface delegates on the player's application looper`() {
        val (player, _) = mockPlayerWithListener()
        // The wrapper hops onto the player's application looper before touching it (media3 is
        // single-thread-affine). Robolectric runs this test on the main thread, so reporting the
        // main looper makes the wrapper take its synchronous in-thread branch.
        every { player.applicationLooper } returns android.os.Looper.getMainLooper()
        val wrapper = ExoVideoPlayer(player)
        val surface = mockk<Surface>(relaxed = true)

        wrapper.setVideoSurface(surface)
        verify { player.setVideoSurface(surface) }
    }

    @Test
    fun `release releases player`() {
        val (player, _) = mockPlayerWithListener()
        val wrapper = ExoVideoPlayer(player)

        wrapper.release()
        verify { player.release() }
    }

    @Test
    fun `durationMs reflects player duration`() {
        val (player, _) = mockPlayerWithListener()
        every { player.duration } returns 42_000L
        val wrapper = ExoVideoPlayer(player)

        assertEquals(42_000L, wrapper.durationMs)
    }
}
