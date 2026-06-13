package com.arashivision.sdk.demo.ui.capture

import com.arashivision.sdk.demo.base.BaseEvent
import com.arashivision.sdk.demo.ui.capture.camera.CameraSDKAdapter
import com.arashivision.sdk.demo.ui.capture.camera.CaptureWindowCrop
import com.arashivision.sdk.demo.ui.capture.camera.PlayerOffsets
import com.arashivision.sdk.demo.ui.capture.camera.PreviewUpdateInputs
import com.arashivision.sdk.demo.ui.capture.camera.StreamResolution
import com.arashivision.sdkcamera.camera.model.CaptureMode
import com.elvishew.xlog.LogConfiguration
import com.elvishew.xlog.XLog
import com.elvishew.xlog.printer.Printer
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewParamsControllerTest {

    @Before
    fun initLog() {
        try {
            XLog.init(LogConfiguration.Builder().build(), Printer { _, _, _ -> })
        } catch (_: IllegalStateException) {
        }
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private fun relaxedAdapter(): CameraSDKAdapter = mockk(relaxed = true)

    private fun buildController(
        adapter: CameraSDKAdapter,
        events: MutableList<BaseEvent>,
        setOfflineSetting: suspend () -> Unit = {},
    ): Pair<PreviewParamsController, TestScope> {
        val scope = TestScope(StandardTestDispatcher())
        val ctrl = PreviewParamsController(
            adapter = adapter,
            scope = scope,
            emitEvent = { events.add(it) },
            setOfflineCaptureSettingValueToCamera = setOfflineSetting,
        )
        return ctrl to scope
    }

    // -------------------------------------------------------------------------
    // 1. Encode mismatch → applyStreamEncode + RestartPlayerViewEvent; no previewUpdateInputs
    // -------------------------------------------------------------------------

    @Test
    fun `encode mismatch calls applyStreamEncode and emits RestartPlayerViewEvent`() = runTest {
        val adapter = relaxedAdapter().also {
            every { it.isEncodeMismatch() } returns true
        }
        val events = mutableListOf<BaseEvent>()
        val (ctrl, _) = buildController(adapter, events)

        ctrl.cameraPreviewStreamParamsChanged(
            mode = CaptureMode.RECORD_NORMAL,
            isPlaying = true,
            currentCrop = null,
            currentStabOffset = null,
            currentResolution = null,
            currentFileType = 0,
        )

        verify(exactly = 1) { adapter.applyStreamEncode() }
        assertEquals(1, events.size)
        assertTrue(events[0] is CaptureEvent.RestartPlayerViewEvent)
        verify(exactly = 0) { adapter.previewUpdateInputs(any(), any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // 2. Not playing → no event, previewUpdateInputs not called
    // -------------------------------------------------------------------------

    @Test
    fun `not playing returns early with no event`() = runTest {
        val adapter = relaxedAdapter().also {
            every { it.isEncodeMismatch() } returns false
        }
        val events = mutableListOf<BaseEvent>()
        val (ctrl, _) = buildController(adapter, events)

        ctrl.cameraPreviewStreamParamsChanged(
            mode = CaptureMode.RECORD_NORMAL,
            isPlaying = false,
            currentCrop = null,
            currentStabOffset = null,
            currentResolution = null,
            currentFileType = 0,
        )

        assertTrue(events.isEmpty())
        verify(exactly = 0) { adapter.previewUpdateInputs(any(), any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // 3. File type changed → fetches options and emits RestartPlayerViewEvent
    // -------------------------------------------------------------------------

    @Test
    fun `file type changed emits RestartPlayerViewEvent and fetches camera options`() = runTest {
        val adapter = relaxedAdapter().also {
            every { it.isEncodeMismatch() } returns false
            every { it.isPreviewFileTypeChanged(any(), any()) } returns true
        }
        val events = mutableListOf<BaseEvent>()
        val (ctrl, scope) = buildController(adapter, events)

        ctrl.cameraPreviewStreamParamsChanged(
            mode = CaptureMode.RECORD_NORMAL,
            isPlaying = true,
            currentCrop = null,
            currentStabOffset = null,
            currentResolution = null,
            currentFileType = 0,
        )

        scope.advanceUntilIdle()

        coVerify(exactly = 1) { adapter.fetchCameraOptions() }
        assertTrue(events.any { it is CaptureEvent.RestartPlayerViewEvent })
        verify(exactly = 0) { adapter.previewUpdateInputs(any(), any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // 4. Params changed → emits UpdatePlayerViewParamsEvent with correct fields
    // -------------------------------------------------------------------------

    @Test
    fun `params changed emits UpdatePlayerViewParamsEvent carrying windowCrop and resolution`() = runTest {
        val crop = CaptureWindowCrop(1920, 960, 1920, 960, 0, 0)
        val offset = PlayerOffsets("v1-offset-string", "", "", "")
        val res = StreamResolution(1920, 960, 30)
        val inputs = PreviewUpdateInputs(
            windowCrop = crop,
            playerOffset = offset,
            stabOffset = "stab-v1",
            resolution = res,
        )
        val adapter = relaxedAdapter().also {
            every { it.isEncodeMismatch() } returns false
            every { it.isPreviewFileTypeChanged(any(), any()) } returns false
            every { it.previewUpdateInputs(any(), any(), any(), any()) } returns inputs
        }
        val events = mutableListOf<BaseEvent>()
        val (ctrl, _) = buildController(adapter, events)

        ctrl.cameraPreviewStreamParamsChanged(
            mode = CaptureMode.RECORD_NORMAL,
            isPlaying = true,
            currentCrop = null,
            currentStabOffset = null,
            currentResolution = null,
            currentFileType = 0,
        )

        assertEquals(1, events.size)
        val evt = events[0] as CaptureEvent.UpdatePlayerViewParamsEvent
        assertEquals(crop, evt.windowCrop)
        assertEquals(offset, evt.playerOffset)
        assertEquals("stab-v1", evt.stabOffset)
        assertEquals(res, evt.resolution)
    }

    // -------------------------------------------------------------------------
    // 5. Nothing changed (previewUpdateInputs returns null) → no UpdatePlayerViewParamsEvent
    // -------------------------------------------------------------------------

    @Test
    fun `nothing changed emits no UpdatePlayerViewParamsEvent`() = runTest {
        val adapter = relaxedAdapter().also {
            every { it.isEncodeMismatch() } returns false
            every { it.isPreviewFileTypeChanged(any(), any()) } returns false
            every { it.previewUpdateInputs(any(), any(), any(), any()) } returns null
        }
        val events = mutableListOf<BaseEvent>()
        val (ctrl, _) = buildController(adapter, events)

        ctrl.cameraPreviewStreamParamsChanged(
            mode = CaptureMode.RECORD_NORMAL,
            isPlaying = true,
            currentCrop = null,
            currentStabOffset = null,
            currentResolution = null,
            currentFileType = 0,
        )

        assertFalse(events.any { it is CaptureEvent.UpdatePlayerViewParamsEvent })
    }

    // -------------------------------------------------------------------------
    // 6. onPreviewStreamParamsChanged() → emits CameraPreviewStreamParamsChangedEvent
    // -------------------------------------------------------------------------

    @Test
    fun `onPreviewStreamParamsChanged emits CameraPreviewStreamParamsChangedEvent`() = runTest {
        val adapter = relaxedAdapter()
        val events = mutableListOf<BaseEvent>()
        val (ctrl, _) = buildController(adapter, events)

        ctrl.onPreviewStreamParamsChanged()

        assertEquals(1, events.size)
        assertTrue(events[0] is CaptureEvent.CameraPreviewStreamParamsChangedEvent)
    }

    // -------------------------------------------------------------------------
    // 7. onCaptureFinish video mode + old flow → reopenPreviewStream + RestartPlayerViewEvent
    // -------------------------------------------------------------------------

    @Test
    fun `onCaptureFinish video mode old flow calls reopenPreviewStream and emits RestartPlayerViewEvent`() = runTest {
        val adapter = relaxedAdapter().also {
            every { it.supportsNewCaptureControlFlow } returns false
        }
        val events = mutableListOf<BaseEvent>()
        val (ctrl, scope) = buildController(adapter, events)

        ctrl.onCaptureFinish(CaptureMode.RECORD_NORMAL)
        scope.advanceUntilIdle()

        coVerify(exactly = 1) { adapter.reopenPreviewStream() }
        assertTrue(events.any { it is CaptureEvent.RestartPlayerViewEvent })
    }

    // -------------------------------------------------------------------------
    // 8. onCaptureFinish new flow → returns immediately, reopenPreviewStream NOT called
    // -------------------------------------------------------------------------

    @Test
    fun `onCaptureFinish new capture control flow returns immediately without reopenPreviewStream`() = runTest {
        val adapter = relaxedAdapter().also {
            every { it.supportsNewCaptureControlFlow } returns true
        }
        val events = mutableListOf<BaseEvent>()
        val (ctrl, scope) = buildController(adapter, events)

        ctrl.onCaptureFinish(CaptureMode.RECORD_NORMAL)
        scope.advanceUntilIdle()

        coVerify(exactly = 0) { adapter.reopenPreviewStream() }
        assertTrue(events.none { it is CaptureEvent.RestartPlayerViewEvent })
    }
}
