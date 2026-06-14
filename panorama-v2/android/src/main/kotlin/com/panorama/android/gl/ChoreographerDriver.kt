package com.panorama.android.gl

import android.view.Choreographer

/** Frame-tick scheduler abstraction. The production implementation posts onto
 *  [android.view.Choreographer] (one callback per display VSYNC); tests swap in a fake so the
 *  pacing logic in [ChoreographerDriver] is unit-testable with no Android dependency. */
interface FrameScheduler {
    /** Schedule [cb] to run on the next frame. */
    fun post(cb: () -> Unit)

    /** Cancel any pending callback (idle state). */
    fun remove()
}

/** Choreographer-backed [FrameScheduler]: posts the driver's tick onto the display VSYNC clock.
 *  A single [Choreographer.FrameCallback] is reused; [post] re-arms it, [remove] cancels it. */
class ChoreographerFrameScheduler(
    private val choreographer: Choreographer = Choreographer.getInstance(),
) : FrameScheduler {

    private var pending: (() -> Unit)? = null
    private val frameCallback = Choreographer.FrameCallback {
        // Consume before invoking so the driver's re-post (start loop) arms the NEXT frame, not this
        // same callback instance redundantly.
        val cb = pending
        pending = null
        cb?.invoke()
    }

    override fun post(cb: () -> Unit) {
        pending = cb
        choreographer.postFrameCallback(frameCallback)
    }

    override fun remove() {
        pending = null
        choreographer.removeFrameCallback(frameCallback)
    }
}

/** Self-reposting frame-pump with an explicit lifecycle (spec section 9, fix 2): the one and only
 *  idle state is "no callback posted". v1's bug was a Choreographer callback that kept rendering
 *  after pause because nothing removed it; here [stop] is the single, explicit teardown.
 *
 *  - [start]: begins rendering every VSYNC. The posted callback runs [onFrame] then re-posts
 *    itself, so playback renders continuously until [stop]. Idempotent while already running.
 *  - [stop]: removes the callback — the loop ends, the driver returns to idle.
 *  - [renderOnce]: a single [onFrame] with no re-post, for repainting one frame after a seek while
 *    paused without spinning up the loop.
 *
 *  Not thread-safe: drive it from one thread (the main/UI thread, where Choreographer lives). */
class ChoreographerDriver(
    private val scheduler: FrameScheduler,
    private val onFrame: () -> Unit,
) {
    private var running = false

    fun start() {
        if (running) return
        running = true
        postLoop()
    }

    fun stop() {
        if (!running) return
        running = false
        scheduler.remove()
    }

    fun renderOnce() {
        onFrame()
    }

    private fun postLoop() {
        scheduler.post {
            // Guard against a tick that lands after stop() flipped the flag.
            if (!running) return@post
            onFrame()
            postLoop()
        }
    }
}
