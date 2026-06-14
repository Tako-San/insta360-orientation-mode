package com.panorama.android.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure pacing-logic tests for [ChoreographerDriver]: no Android, no Robolectric. The real
 *  Choreographer is abstracted behind [FrameScheduler], so a fake scheduler can drive frame ticks
 *  deterministically and we assert the start/stop/renderOnce cadence directly. */
class ChoreographerDriverTest {

    /** Records the posted callback and exposes a [tick] that fires it once, mimicking one VSYNC. */
    private class FakeScheduler : FrameScheduler {
        private var callback: (() -> Unit)? = null
        var posted = false
            private set

        override fun post(cb: () -> Unit) {
            callback = cb
            posted = true
        }

        override fun remove() {
            callback = null
            posted = false
        }

        /** Fire the currently-posted callback once (one VSYNC). Mirrors Choreographer: the posted
         *  callback is consumed, and only re-fires next tick if the driver re-posted itself. */
        fun tick() {
            val cb = callback ?: return
            callback = null
            posted = false
            cb()
        }
    }

    @Test
    fun `start drives one render per frame tick`() {
        val scheduler = FakeScheduler()
        var renders = 0
        val driver = ChoreographerDriver(scheduler) { renders++ }

        driver.start()
        repeat(5) { scheduler.tick() }

        assertEquals("each tick should re-post and render exactly once", 5, renders)
    }

    @Test
    fun `stop halts rendering`() {
        val scheduler = FakeScheduler()
        var renders = 0
        val driver = ChoreographerDriver(scheduler) { renders++ }

        driver.start()
        scheduler.tick()
        scheduler.tick()
        driver.stop()
        val before = renders

        // No callback is posted after stop, so further ticks are no-ops.
        repeat(5) { scheduler.tick() }

        assertEquals("stop must remove the callback so ticks no longer render", before, renders)
        assertFalse("scheduler should hold no pending callback after stop", scheduler.posted)
    }

    @Test
    fun `renderOnce fires exactly one`() {
        val scheduler = FakeScheduler()
        var renders = 0
        val driver = ChoreographerDriver(scheduler) { renders++ }

        driver.renderOnce()

        assertEquals("renderOnce renders exactly once", 1, renders)
        assertFalse("renderOnce must not start the loop", scheduler.posted)
        // Ticking does nothing because no callback was posted.
        scheduler.tick()
        assertEquals("no loop means no further renders", 1, renders)
    }

    @Test
    fun `play continuous pause halt seek-paused one renderOnce`() {
        val scheduler = FakeScheduler()
        var renders = 0
        val driver = ChoreographerDriver(scheduler) { renders++ }

        // play -> continuous: ticks render every VSYNC.
        driver.start()
        repeat(3) { scheduler.tick() }
        assertEquals(3, renders)

        // pause -> halt: ticks stop rendering.
        driver.stop()
        repeat(3) { scheduler.tick() }
        assertEquals("paused must not render", 3, renders)

        // seek while paused -> exactly one render, loop stays stopped.
        driver.renderOnce()
        assertEquals("seek renders exactly one frame", 4, renders)
        assertFalse("seek must not restart the loop", scheduler.posted)
    }

    @Test
    fun `start is idempotent`() {
        val scheduler = FakeScheduler()
        var renders = 0
        val driver = ChoreographerDriver(scheduler) { renders++ }

        driver.start()
        driver.start() // second start while running is a no-op (does not double-post)
        repeat(2) { scheduler.tick() }

        assertEquals("double start must not double the render rate", 2, renders)
    }
}
