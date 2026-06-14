package com.panorama.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Tuning knobs for the viewer. For v2 these are local UI state only — they are NOT yet wired into
 *  the live pipeline.
 *
 *  TODO(Phase 4+): thread these through to the live collaborators:
 *   - sensitivity -> a gain on the gaze delta in OrientationEngine,
 *   - IPD yaw    -> PanoramaRenderer.ipdYawDeg (StereoEyeLayout straddle),
 *   - FOV        -> the h/v FOV radians used by ArrowResolver / PanoramaFov.
 *  They are surfaced now so the screen + nav graph are complete and the controls exist for the
 *  on-device tuning pass; the binding is intentionally deferred (see plan Phase 4). */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    var sensitivity by remember { mutableFloatStateOf(1.0f) }
    var ipdYawDeg by remember { mutableFloatStateOf(2.5f) }
    var fovDeg by remember { mutableFloatStateOf(90f) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Settings")

        Text("Gyro sensitivity: ${"%.2f".format(sensitivity)}")
        Slider(value = sensitivity, onValueChange = { sensitivity = it }, valueRange = 0.25f..3f)

        Text("IPD yaw straddle: ${"%.1f".format(ipdYawDeg)}°")
        Slider(value = ipdYawDeg, onValueChange = { ipdYawDeg = it }, valueRange = 0f..8f)

        Text("Horizontal FOV: ${"%.0f".format(fovDeg)}°")
        Slider(value = fovDeg, onValueChange = { fovDeg = it }, valueRange = 60f..120f)

        TextButton(onClick = onBack) { Text("Back") }
    }
}
