package com.panorama.app.library

import android.content.Context
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
import java.io.File

/** Entry screen: pick a 360 video (and, optionally, its detection sidecar) via the Storage Access
 *  Framework, then open the player.
 *
 *  Two separate [ActivityResultContracts.OpenDocument] launchers are used so the (optional) sidecar
 *  can be chosen independently of the video. Picking the video calls [onPlay] straight from the
 *  result callback; the sidecar, when picked first, is remembered and carried along on the next
 *  video pick.
 *
 *  [onPlay] only flips a parent [Screen] state (see MainActivity), so it is safe to call directly
 *  from the picker callback even though the Activity is still below RESUMED at that point — unlike
 *  NavController.navigate(), a state write is never gated on lifecycle. */
@Composable
fun LibraryScreen(
    onPlay: (videoUri: Uri, sidecarUri: Uri?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    var pickedSidecar by remember { mutableStateOf<Uri?>(null) }

    // The SAF read grant from OpenDocument is tied to THIS Activity result and this exact Uri
    // object; it is lost once we leave this screen (the player re-uses the Uri later, and ExoPlayer
    // opens it on a background thread) and is not reliably persistable from a DocumentsProvider. So
    // while the grant is still live here, on the main thread, we copy the picked content:// stream
    // into a cache file and carry a file:// Uri forward — file:// needs no grant, so
    // ExoPlayer/SidecarLoader read it freely.
    // TODO: stream large videos instead of fully copying into cache.
    val sidecarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) { pickedSidecar = copyToCache(context, uri, "sidecar") ?: uri } }

    val videoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            // Copy while the SAF read grant is still live (callback thread), then open the player.
            val playable = copyToCache(context, uri, "video") ?: uri
            onPlay(playable, pickedSidecar)
        }
    }

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

/** Copies the [source] content:// stream into the app cache and returns a file:// [Uri] for it, or
 *  null if the copy failed (the caller then falls back to the original Uri). Runs synchronously on
 *  the picker callback (main) thread while the SAF read grant is still live; the resulting file
 *  needs no grant, so it survives the screen switch and a background-thread open. */
internal fun copyToCache(context: Context, source: Uri, prefix: String): Uri? = runCatching {
    val dest = File(context.cacheDir, "$prefix-${System.currentTimeMillis()}")
    context.contentResolver.openInputStream(source)?.use { input ->
        dest.outputStream().use { output -> input.copyTo(output) }
    } ?: return null
    Uri.fromFile(dest)
}.getOrNull()
