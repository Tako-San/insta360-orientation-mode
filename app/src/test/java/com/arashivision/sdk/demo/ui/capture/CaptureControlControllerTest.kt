package com.arashivision.sdk.demo.ui.capture

import com.arashivision.sdk.demo.base.BaseEvent
import com.arashivision.sdk.demo.ui.capture.camera.CameraSDKAdapter
import com.arashivision.sdk.demo.ui.capture.camera.LiveCallbacks
import com.arashivision.sdkcamera.camera.model.CaptureMode
import com.elvishew.xlog.LogConfiguration
import com.elvishew.xlog.XLog
import com.elvishew.xlog.printer.Printer
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CaptureControlControllerTest {

    @Before
    fun initLog() {
        // XLog must be initialized before the controller's lazy logger is accessed.
        // Repeated init throws IllegalStateException — swallow it.
        try {
            XLog.init(LogConfiguration.Builder().build(), Printer { _, _, _ -> })
        } catch (_: IllegalStateException) {
        }
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private fun buildController(
        adapter: CameraSDKAdapter,
        events: MutableList<BaseEvent>,
        getLiveRtmp: () -> String = { "rtmp://test/live" },
    ): CaptureControlController = CaptureControlController(
        adapter = adapter,
        emitEvent = { events.add(it) },
        getLiveRtmp = getLiveRtmp,
    )

    private fun relaxedAdapter(): CameraSDKAdapter = mockk(relaxed = true)

    // -------------------------------------------------------------------------
    // 1. Video mode, sd-card enabled, camera not working → startRecord called
    // -------------------------------------------------------------------------

    @Test
    fun `video mode sd enabled camera not working calls startRecord`() {
        val adapter = relaxedAdapter().also {
            every { it.isCameraWorking() } returns false
            every { it.isSdCardEnabled } returns true
        }
        val events = mutableListOf<BaseEvent>()
        val ctrl = buildController(adapter, events)

        ctrl.startCapture(CaptureMode.RECORD_NORMAL)

        verify(exactly = 1) { adapter.startRecord(CaptureMode.RECORD_NORMAL) }
        assertTrue(events.none { it is CaptureEvent.CameraCaptureEvent && (it as CaptureEvent.CameraCaptureEvent).status == CaptureEvent.CaptureStatus.SD_DISABLE })
    }

    // -------------------------------------------------------------------------
    // 2. Video mode, sd-card disabled → emits SD_DISABLE, startRecord NOT called
    // -------------------------------------------------------------------------

    @Test
    fun `video mode sd disabled emits SD_DISABLE and skips startRecord`() {
        val adapter = relaxedAdapter().also {
            every { it.isCameraWorking() } returns false
            every { it.isSdCardEnabled } returns false
        }
        val events = mutableListOf<BaseEvent>()
        val ctrl = buildController(adapter, events)

        ctrl.startCapture(CaptureMode.RECORD_NORMAL)

        assertEquals(1, events.size)
        val evt = events[0] as CaptureEvent.CameraCaptureEvent
        assertEquals(CaptureEvent.CaptureStatus.SD_DISABLE, evt.status)
        verify(exactly = 0) { adapter.startRecord(any()) }
    }

    // -------------------------------------------------------------------------
    // 3. Video mode, camera already working → stopRecord called
    // -------------------------------------------------------------------------

    @Test
    fun `video mode camera working calls stopRecord`() {
        val adapter = relaxedAdapter().also {
            every { it.isCameraWorking() } returns true
        }
        val events = mutableListOf<BaseEvent>()
        val ctrl = buildController(adapter, events)

        ctrl.startCapture(CaptureMode.RECORD_NORMAL)

        verify(exactly = 1) { adapter.stopRecord(CaptureMode.RECORD_NORMAL) }
        verify(exactly = 0) { adapter.startRecord(any()) }
    }

    // -------------------------------------------------------------------------
    // 4. Photo single-click mode → takePhoto called, startRecord NOT called
    // -------------------------------------------------------------------------

    @Test
    fun `photo single-click mode calls takePhoto not startRecord`() {
        val adapter = relaxedAdapter()
        val events = mutableListOf<BaseEvent>()
        val ctrl = buildController(adapter, events)

        ctrl.startCapture(CaptureMode.CAPTURE_NORMAL)

        verify(exactly = 1) { adapter.takePhoto(CaptureMode.CAPTURE_NORMAL) }
        verify(exactly = 0) { adapter.startRecord(any()) }
    }

    // -------------------------------------------------------------------------
    // 5. Live mode, not living, rtmp non-empty → emits START_LIVE + startLive;
    //    callbacks.onStarted() → isLiving=true + PUSH_STARTED emitted
    // -------------------------------------------------------------------------

    @Test
    fun `live mode not living emits START_LIVE and calls startLive then onStarted emits PUSH_STARTED`() {
        val callbackSlot = slot<LiveCallbacks>()
        val adapter = relaxedAdapter().also {
            every { it.startLive(any(), capture(callbackSlot)) } returns Unit
        }
        val events = mutableListOf<BaseEvent>()
        val ctrl = buildController(adapter, events, getLiveRtmp = { "rtmp://test/live" })

        ctrl.startCapture(CaptureMode.LIVE)

        // START_LIVE must be emitted before the SDK call
        assertTrue(events.any {
            it is CaptureEvent.CameraLiveEvent &&
                it.status == CaptureEvent.LiveStatus.START_LIVE
        })
        verify(exactly = 1) { adapter.startLive(eq("rtmp://test/live"), any()) }

        // Simulate the SDK firing onStarted
        assertTrue(callbackSlot.isCaptured)
        callbackSlot.captured.onStarted()

        assertTrue(events.any {
            it is CaptureEvent.CameraLiveEvent &&
                it.status == CaptureEvent.LiveStatus.PUSH_STARTED
        })

        // isLiving is now true — a second startCapture should stopLive, not startLive again
        ctrl.startCapture(CaptureMode.LIVE)
        verify(exactly = 1) { adapter.stopLive() }
    }

    // -------------------------------------------------------------------------
    // 6. Live mode, rtmp empty → emits RTMP_EMPTY, startLive NOT called
    // -------------------------------------------------------------------------

    @Test
    fun `live mode rtmp empty emits RTMP_EMPTY and skips startLive`() {
        val adapter = relaxedAdapter()
        val events = mutableListOf<BaseEvent>()
        val ctrl = buildController(adapter, events, getLiveRtmp = { "" })

        ctrl.startCapture(CaptureMode.LIVE)

        assertEquals(1, events.size)
        val evt = events[0] as CaptureEvent.CameraLiveEvent
        assertEquals(CaptureEvent.LiveStatus.RTMP_EMPTY, evt.status)
        verify(exactly = 0) { adapter.startLive(any(), any()) }
    }

    // -------------------------------------------------------------------------
    // 7. Live mode, already living → stopLive called + STOP_LIVE emitted
    // -------------------------------------------------------------------------

    @Test
    fun `live mode already living calls stopLive and emits STOP_LIVE`() {
        val callbackSlot = slot<LiveCallbacks>()
        val adapter = relaxedAdapter().also {
            every { it.startLive(any(), capture(callbackSlot)) } returns Unit
        }
        val events = mutableListOf<BaseEvent>()
        val ctrl = buildController(adapter, events, getLiveRtmp = { "rtmp://test/live" })

        // Drive isLiving = true via the callback
        ctrl.startCapture(CaptureMode.LIVE)
        callbackSlot.captured.onStarted()

        // Clear events so we only assert on what the second call emits
        events.clear()

        // Second call: isLiving is true → should stopLive
        ctrl.startCapture(CaptureMode.LIVE)

        verify(exactly = 1) { adapter.stopLive() }
        assertTrue(events.any {
            it is CaptureEvent.CameraLiveEvent &&
                it.status == CaptureEvent.LiveStatus.STOP_LIVE
        })
        // startLive must NOT be called a second time
        verify(exactly = 1) { adapter.startLive(any(), any()) }
    }
}
