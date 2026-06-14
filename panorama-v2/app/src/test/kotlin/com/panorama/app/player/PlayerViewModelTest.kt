package com.panorama.app.player

import android.net.Uri
import com.panorama.android.detection.SidecarLoader
import com.panorama.android.media.ExoVideoPlayer
import com.panorama.android.sensor.OrientationEngine
import com.panorama.core.calibration.AxisConvention
import com.panorama.core.detection.Detection
import com.panorama.core.detection.SidecarDetectionSource
import com.panorama.core.math.GazeState
import com.panorama.core.projection.EquirectProjection
import com.panorama.core.projection.ProjectionModel
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Quaternion
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Unit tests for [PlayerViewModel]. The ViewModel is a thin pump over the :android collaborators,
 *  so MockK stands in for [ExoVideoPlayer]/[OrientationEngine]/[SidecarLoader] and a real
 *  [SidecarDetectionSource] feeds canned detections. The throttle loop runs on the injected
 *  [StandardTestDispatcher] (installed as Main); Robolectric supplies the [Uri] parser.
 *
 *  IMPORTANT: the ViewModel's init starts an endless `while (isActive) { delay(...) }` throttle
 *  loop plus two never-completing StateFlow collectors. We hand the VM `runTest`'s backgroundScope
 *  so those live there: it auto-cancels at the end of each test and is excluded from runTest's final
 *  join, so the test finishes without hanging. We also NEVER call `advanceUntilIdle()` (it would
 *  spin that loop forever and OOM) — instead we [advanceTimeBy] a finite span past one tick. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val exo = mockk<ExoVideoPlayer>(relaxed = true)
    private val engine = mockk<OrientationEngine>(relaxed = true)
    private val sidecarLoader = mockk<SidecarLoader>(relaxed = true)
    private val projection: ProjectionModel = EquirectProjection()
    private val axisConvention = AxisConvention()

    private val isPlaying = MutableStateFlow(false)
    private val positionMs = MutableStateFlow(0L)
    private val durationMs = MutableStateFlow(0L)
    private val identityGaze = GazeState(Quaternion(), 0f, 0f, 0f)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { exo.isPlaying } returns isPlaying
        every { exo.positionMs } returns positionMs
        every { exo.durationMs } returns durationMs
        every { engine.currentGaze() } returns identityGaze
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(scope: CoroutineScope, tickerIntervalMs: Long = 33L) = PlayerViewModel(
        exo = exo,
        orientationEngine = engine,
        sidecarLoader = sidecarLoader,
        projection = projection,
        axisConvention = axisConvention,
        throttleDispatcher = testDispatcher,
        tickerIntervalMs = tickerIntervalMs,
        backgroundScope = scope,
    )

    @Test
    fun `play sets isPlaying in state`() = runTest(testDispatcher) {
        val vm = viewModel(backgroundScope)
        vm.play()
        verify { exo.play() }

        isPlaying.value = true
        advanceTimeBy(50)            // let the isPlaying collector run (no advanceUntilIdle: endless loop)
        assertTrue(vm.state.value.isPlaying)
    }

    @Test
    fun `toggleVr flips vrEnabled`() = runTest(testDispatcher) {
        val vm = viewModel(backgroundScope)
        vm.toggleVr()
        advanceTimeBy(50)
        assertTrue(vm.state.value.vrEnabled)
        vm.toggleVr()
        advanceTimeBy(50)
        assertFalse(vm.state.value.vrEnabled)
    }

    @Test
    fun `recalibrate calls engine and bumps nonce`() = runTest(testDispatcher) {
        val vm = viewModel(backgroundScope)
        val before = vm.state.value.calibrationNonce
        vm.recalibrate()
        advanceTimeBy(50)
        verify { engine.calibrate() }
        assertEquals(before + 1, vm.state.value.calibrationNonce)
    }

    @Test
    fun `arrow appears for out-of-FOV detection`() = runTest(testDispatcher) {
        // centerNorm x=0.0 -> yaw -180 deg: directly behind the identity gaze, outside the FOV.
        val behind = SidecarDetectionSource(timelineFor(listOf(Detection(Float2(0.0f, 0.5f)))))
        every { sidecarLoader.load(any()) } returns behind

        val vm = viewModel(backgroundScope)
        vm.selectMedia(Uri.parse("file:///v.mp4"), Uri.parse("file:///v.json"))
        advanceTimeBy(100)           // past at least one throttle tick (33 ms)
        assertTrue("expected arrow visible for behind detection", vm.state.value.arrow.visible)
    }

    @Test
    fun `arrow hidden for in-FOV detection`() = runTest(testDispatcher) {
        // centerNorm (0.5,0.5) -> forward: inside the identity-gaze FOV, no arrow.
        val centre = SidecarDetectionSource(timelineFor(listOf(Detection(Float2(0.5f, 0.5f)))))
        every { sidecarLoader.load(any()) } returns centre

        val vm = viewModel(backgroundScope)
        vm.selectMedia(Uri.parse("file:///v.mp4"), Uri.parse("file:///v.json"))
        advanceTimeBy(100)
        assertFalse("expected no arrow for centre detection", vm.state.value.arrow.visible)
    }

    @Test
    fun `seek delegates`() = runTest(testDispatcher) {
        val vm = viewModel(backgroundScope)
        vm.seek(5000)
        verify { exo.seekTo(5000) }
    }

    /** Builds a [SidecarDetectionSource]'s timeline over a one-frame sidecar with the given detections. */
    private fun timelineFor(detections: List<Detection>): com.panorama.core.detection.DetectionTimeline {
        val objects = detections.map {
            com.panorama.core.detection.SidecarObject(
                centerNorm = listOf(it.centerNorm.x, it.centerNorm.y),
            )
        }
        val sidecar = com.panorama.core.detection.Sidecar(
            frames = listOf(com.panorama.core.detection.SidecarFrame(timeMs = 0L, objects = objects)),
        )
        return com.panorama.core.detection.DetectionTimeline(sidecar)
    }
}
