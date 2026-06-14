package com.panorama.app.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Entry screen: pick a 360 video (and, optionally, its detection sidecar) via the Storage Access
 *  Framework, then navigate to the player.
 *
 *  Two separate [ActivityResultContracts.OpenDocument] launchers are used so the (optional) sidecar
 *  can be chosen independently of the video. Picking the video immediately navigates; the sidecar,
 *  when picked first, is remembered and carried along on the next video pick. */
@Composable
fun LibraryScreen(
    onPlay: (videoUri: Uri, sidecarUri: Uri?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    var pickedSidecar by remember { mutableStateOf<Uri?>(null) }

    // SAF grants are per-call and lost once the launcher result is consumed; ExoPlayer opens the
    // URI later on a background thread, so we MUST persist the read permission or it fails with
    // SecurityException (black video). takePersistableUriPermission keeps the grant across that hop.
    fun persistRead(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    val sidecarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) { persistRead(uri); pickedSidecar = uri } }

    val videoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) { persistRead(uri); onPlay(uri, pickedSidecar) } }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(onClick = { videoLauncher.launch(arrayOf("video/*")) }) {
            Text("Open 360 video")
        }
        OutlinedButton(onClick = { sidecarLauncher.launch(arrayOf("application/json", "*/*")) }) {
            Text(if (pickedSidecar != null) "Sidecar selected (optional)" else "Pick detections sidecar (optional)")
        }
        OutlinedButton(onClick = onOpenSettings) {
            Text("Settings")
        }
    }
}
