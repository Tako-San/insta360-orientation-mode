package com.arashivision.sdk.demo.ui.capture

import com.arashivision.sdk.demo.base.BaseEvent
import com.arashivision.sdk.demo.base.EventStatus
import com.arashivision.sdk.demo.capture.CameraOfflineData
import com.arashivision.sdk.demo.ui.capture.camera.CameraSDKAdapter
import com.arashivision.sdkcamera.camera.model.CaptureMode
import com.elvishew.xlog.LogConfiguration
import com.elvishew.xlog.XLog
import com.elvishew.xlog.printer.Printer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureConnectionControllerTest {

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
        scope: TestScope,
        events: MutableList<BaseEvent>,
        offlineDataSlot: Array<CameraOfflineData?> = arrayOf(null),
        setOfflineLambda: suspend () -> Unit = {},
        offlineData: CameraOfflineData = mockk(relaxed = true),
    ): CaptureConnectionController {
        return CaptureConnectionController(
            adapter = adapter,
            scope = scope,
            emitEvent = { events.add(it) },
            onOfflineDataReady = { offlineDataSlot[0] = it },
            setOfflineCaptureSettingValueToCamera = setOfflineLambda,
            createOfflineData = { offlineData },
        )
    }

    private fun happyAdapter(newFlow: Boolean = true): CameraSDKAdapter = mockk<CameraSDKAdapter>(relaxed = true).also {
        coEvery { it.ensurePanoramaSensor() } returns true
        coEvery { it.fetchCameraOptions() } returns true
        coEvery { it.initSupportConfig() } returns true
        coEvery { it.openPreviewStream() } returns true
        every { it.supportsNewCaptureControlFlow } returns newFlow
        every { it.supportCaptureModes } returns listOf(CaptureMode.RECORD_NORMAL)
    }

    // -------------------------------------------------------------------------
    // initCapture tests
    // -------------------------------------------------------------------------

    @Test
    fun `happy path emits events in exact order and calls onOfflineDataReady`() = runTest {
        val adapter = happyAdapter()
        val events = mutableListOf<BaseEvent>()
        val offlineSlot = arrayOf<CameraOfflineData?>(null)
        val offlineData = mockk<CameraOfflineData>(relaxed = true).also {
            every { it.currentCaptureMode } returns CaptureMode.RECORD_NORMAL
        }
        val ctrl = buildController(adapter, this, events, offlineSlot, offlineData = offlineData)

        ctrl.initCapture()
        advanceUntilIdle()

        // Verify exact event sequence
        assertEquals(6, events.size)

        val e0 = events[0] as CaptureEvent.InitCaptureEvent
        assertEquals(EventStatus.START, e0.status)
        assertEquals(null, e0.step)

        val e1 = events[1] as CaptureEvent.InitCaptureEvent
        assertEquals(EventStatus.PROGRESS, e1.status)
        assertEquals(CaptureEvent.InitStep.CHECK_SENSOR, e1.step)

        val e2 = events[2] as CaptureEvent.InitCaptureEvent
        assertEquals(EventStatus.PROGRESS, e2.status)
        assertEquals(CaptureEvent.InitStep.FETCH_CAMERA_OPTIONS, e2.step)

        val e3 = events[3] as CaptureEvent.InitCaptureEvent
        assertEquals(EventStatus.PROGRESS, e3.status)
        assertEquals(CaptureEvent.InitStep.INIT_SUPPORT_CONFIG, e3.step)

        val e4 = events[4] as CaptureEvent.InitCaptureEvent
        assertEquals(EventStatus.PROGRESS, e4.status)
        assertEquals(CaptureEvent.InitStep.OPEN_PREVIEW_STREAM, e4.step)

        val e5 = events[5] as CaptureEvent.InitCaptureEvent
        assertEquals(EventStatus.SUCCESS, e5.status)

        // onOfflineDataReady was invoked
        assertEquals(offlineData, offlineSlot[0])
    }

    @Test
    fun `sensor check fails emits FAILED and stops sequence`() = runTest {
        val adapter = happyAdapter().also {
            coEvery { it.ensurePanoramaSensor() } returns false
        }
        val events = mutableListOf<BaseEvent>()
        val ctrl = buildController(adapter, this, events)

        ctrl.initCapture()
        advanceUntilIdle()

        assertEquals(3, events.size)
        assertEquals(EventStatus.START, (events[0] as CaptureEvent.InitCaptureEvent).status)
        assertEquals(EventStatus.PROGRESS, (events[1] as CaptureEvent.InitCaptureEvent).status)
        val failed = events[2] as CaptureEvent.InitCaptureEvent
        assertEquals(EventStatus.FAILED, failed.status)
        assertEquals(CaptureEvent.InitStep.CHECK_SENSOR, failed.step)

        // No further adapter calls after sensor failure
        coVerify(exactly = 0) { adapter.fetchCameraOptions() }
    }

    @Test
    fun `initSupportConfig fails emits FAILED and does not open preview stream`() = runTest {
        val adapter = happyAdapter().also {
            coEvery { it.initSupportConfig() } returns false
        }
        val events = mutableListOf<BaseEvent>()
        val ctrl = buildController(adapter, this, events)

        ctrl.initCapture()
        advanceUntilIdle()

        val failed = events.last() as CaptureEvent.InitCaptureEvent
        assertEquals(EventStatus.FAILED, failed.status)
        assertEquals(CaptureEvent.InitStep.INIT_SUPPORT_CONFIG, failed.step)

        coVerify(exactly = 0) { adapter.openPreviewStream() }
    }

    @Test
    fun `openPreviewStream fails emits FAILED(OPEN_PREVIEW_STREAM)`() = runTest {
        val adapter = happyAdapter().also {
            coEvery { it.openPreviewStream() } returns false
        }
        val events = mutableListOf<BaseEvent>()
        val ctrl = buildController(adapter, this, events)

        ctrl.initCapture()
        advanceUntilIdle()

        val failed = events.last() as CaptureEvent.InitCaptureEvent
        assertEquals(EventStatus.FAILED, failed.status)
        assertEquals(CaptureEvent.InitStep.OPEN_PREVIEW_STREAM, failed.step)
    }

    @Test
    fun `old flow calls setOffline lambda once on successful init`() = runTest {
        val adapter = happyAdapter(newFlow = false)
        val events = mutableListOf<BaseEvent>()
        var setOfflineCallCount = 0
        val ctrl = buildController(adapter, this, events, setOfflineLambda = { setOfflineCallCount++ })

        ctrl.initCapture()
        advanceUntilIdle()

        assertEquals(1, setOfflineCallCount)
    }

    @Test
    fun `new flow does NOT call setOffline lambda on successful init`() = runTest {
        val adapter = happyAdapter(newFlow = true)
        val events = mutableListOf<BaseEvent>()
        var setOfflineCallCount = 0
        val ctrl = buildController(adapter, this, events, setOfflineLambda = { setOfflineCallCount++ })

        ctrl.initCapture()
        advanceUntilIdle()

        assertEquals(0, setOfflineCallCount)
    }

    // -------------------------------------------------------------------------
    // switchCaptureMode tests
    // -------------------------------------------------------------------------

    @Test
    fun `switchCaptureMode out of range emits FAILED`() = runTest {
        val adapter = happyAdapter().also {
            every { it.supportCaptureModes } returns listOf(CaptureMode.RECORD_NORMAL)
        }
        val events = mutableListOf<BaseEvent>()
        val offlineData = mockk<CameraOfflineData>(relaxed = true)
        val ctrl = buildController(adapter, this, events)

        ctrl.switchCaptureMode(99, offlineData)
        advanceUntilIdle()

        assertEquals(2, events.size)
        assertEquals(EventStatus.START, (events[0] as CaptureEvent.SwitchCaptureModeEvent).status)
        assertEquals(EventStatus.FAILED, (events[1] as CaptureEvent.SwitchCaptureModeEvent).status)
    }

    @Test
    fun `switchCaptureMode valid position new flow emits SUCCESS without calling setOffline`() = runTest {
        val adapter = happyAdapter(newFlow = true).also {
            every { it.supportCaptureModes } returns listOf(CaptureMode.RECORD_NORMAL)
        }
        val events = mutableListOf<BaseEvent>()
        var setOfflineCallCount = 0
        val offlineData = mockk<CameraOfflineData>(relaxed = true).also {
            coEvery { it.setCaptureMode(CaptureMode.RECORD_NORMAL) } returns true
        }
        val ctrl = buildController(adapter, this, events, setOfflineLambda = { setOfflineCallCount++ })

        ctrl.switchCaptureMode(0, offlineData)
        advanceUntilIdle()

        assertEquals(EventStatus.SUCCESS, (events.last() as CaptureEvent.SwitchCaptureModeEvent).status)
        assertEquals(0, setOfflineCallCount)
    }

    @Test
    fun `switchCaptureMode valid position old flow calls setOffline`() = runTest {
        val adapter = happyAdapter(newFlow = false).also {
            every { it.supportCaptureModes } returns listOf(CaptureMode.RECORD_NORMAL)
        }
        val events = mutableListOf<BaseEvent>()
        var setOfflineCallCount = 0
        val offlineData = mockk<CameraOfflineData>(relaxed = true).also {
            coEvery { it.setCaptureMode(CaptureMode.RECORD_NORMAL) } returns true
        }
        val ctrl = buildController(adapter, this, events, setOfflineLambda = { setOfflineCallCount++ })

        ctrl.switchCaptureMode(0, offlineData)
        advanceUntilIdle()

        assertEquals(EventStatus.SUCCESS, (events.last() as CaptureEvent.SwitchCaptureModeEvent).status)
        assertEquals(1, setOfflineCallCount)
    }

    @Test
    fun `switchCaptureMode setCaptureMode returns false emits FAILED`() = runTest {
        val adapter = happyAdapter().also {
            every { it.supportCaptureModes } returns listOf(CaptureMode.RECORD_NORMAL)
        }
        val events = mutableListOf<BaseEvent>()
        val offlineData = mockk<CameraOfflineData>(relaxed = true).also {
            coEvery { it.setCaptureMode(CaptureMode.RECORD_NORMAL) } returns false
        }
        val ctrl = buildController(adapter, this, events)

        ctrl.switchCaptureMode(0, offlineData)
        advanceUntilIdle()

        assertEquals(EventStatus.FAILED, (events.last() as CaptureEvent.SwitchCaptureModeEvent).status)
    }
}
