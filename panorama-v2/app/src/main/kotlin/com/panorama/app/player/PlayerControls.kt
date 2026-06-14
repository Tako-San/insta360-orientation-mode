package com.panorama.app.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Transport + mode controls overlaid at the bottom of the player. The slider reflects
 *  [positionMs]; while the user drags it the value is echoed locally and committed on release via
 *  [onSeek]. All actions are plain callbacks into [PlayerViewModel]. */
@Composable
fun PlayerControls(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    vrEnabled: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onRecalibrate: () -> Unit,
    onToggleVr: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Seek slider. Duration may be unknown (<= 0) before the player prepares; fall back to a
        // 1 ms range so the thumb stays clamped at the start rather than NaN-ing the Slider.
        val max = if (durationMs > 0L) durationMs.toFloat() else 1f
        Slider(
            value = positionMs.toFloat().coerceIn(0f, max),
            onValueChange = { onSeek(it.toLong()) },
            valueRange = 0f..max,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onPlayPause) {
                Text(if (isPlaying) "Pause" else "Play")
            }
            FilledTonalButton(onClick = onRecalibrate) {
                Text("Recalibrate")
            }
            FilledTonalButton(onClick = onToggleVr) {
                Text(if (vrEnabled) "VR On" else "VR Off")
            }
        }
    }
}
