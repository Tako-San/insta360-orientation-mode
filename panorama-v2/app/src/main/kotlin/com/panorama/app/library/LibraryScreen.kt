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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File

private const val TAG = "LibraryScreen"

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
    // Capture the latest onPlay so the deferred navigation effect never invokes a stale lambda.
    val currentOnPlay by rememberUpdatedState(onPlay)
    var pickedSidecar by remember { mutableStateOf<Uri?>(null) }

    // Navigation must NOT run directly from the picker's result callback: that callback fires while
    // the Activity is mid STARTED->RESUMED (the SAF picker is closing). A navigate() issued then
    // mutates the NavController back stack, but NavHost (Navigation-Compose) only advances the new
    // entry to a rendered/visible state once the host lifecycle settles at RESUMED — and it does not
    // queue the call for replay. So the player could stay invisible until some unrelated event
    // re-pumped NavHost — the "picker closes but the screen stays on Library until I tap something
    // else" bug. (A bare counter + LaunchedEffect does NOT fix this: the effect runs in that same
    // unsettled window.) The callback parks the picked Uri and bumps a request counter; the effect
    // then suspends on withResumed until genuinely RESUMED before navigating, so the back-stack
    // mutation coincides with the state in which NavHost schedules a recomposition frame.
    //
    // The key is a monotonic counter, not the Uri: an Uri key would not re-fire the effect when the
    // same file is picked twice. The counter only ever increases, so every pick re-runs the effect
    // exactly once; navVideo simply holds the payload for that run.
    var navRequest by remember { mutableIntStateOf(0) }
    var navVideo by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(navRequest) {
        Log.i(TAG, "LaunchedEffect fired, navRequest=$navRequest navVideo=$navVideo")
        if (navRequest > 0) {
            navVideo?.let { uri ->
                // navigate() runs directly in this LaunchedEffect, i.e. on NavHost's own composition
                // dispatcher (AndroidUiDispatcher.Main). It must NOT be wrapped in withResumed: that
                // runs the block on Dispatchers.Main.immediate (a plain Handler dispatcher), so the
                // navigate's snapshot write to visibleEntries never wakes GlobalSnapshotManager's
                // apply-notification consumer (which lives on AndroidUiDispatcher) — the Recomposer
                // is never told the back stack changed and the player route never composes until an
                // unrelated tap pumps the dispatcher. The effect already runs on a RESUMED entry, so
                // no lifecycle gate is needed for correctness.
                Log.i(TAG, "navigating to player with $uri")
                currentOnPlay(uri, pickedSidecar)
            }
        }
    }

    // The SAF read grant from OpenDocument is tied to THIS Activity result and this exact Uri
    // object; it is lost once we navigate (the player route re-parses the Uri from a string, and
    // ExoPlayer opens it later on a background thread) and is not reliably persistable from a
    // DocumentsProvider. So while the grant is still live here, on the main thread, we copy the
    // picked content:// stream into a cache file and carry a file:// Uri forward — file:// needs
    // no grant, so ExoPlayer/SidecarLoader read it freely.
    // TODO: stream large videos instead of fully copying into cache.
    val sidecarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) { pickedSidecar = copyToCache(context, uri, "sidecar") ?: uri } }

    val videoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        Log.i(TAG, "videoLauncher callback, picked uri=$uri")
        if (uri != null) {
            // Copy while the SAF read grant is still live (callback thread), then park the file://
            // Uri and bump the request counter so the LaunchedEffect above navigates once RESUMED.
            navVideo = copyToCache(context, uri, "video") ?: uri
            navRequest++
            Log.i(TAG, "parked navVideo=$navVideo navRequest=$navRequest")
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
 *  needs no grant, so it survives navigation and a background-thread open. */
internal fun copyToCache(context: Context, source: Uri, prefix: String): Uri? = runCatching {
    val dest = File(context.cacheDir, "$prefix-${System.currentTimeMillis()}")
    context.contentResolver.openInputStream(source)?.use { input ->
        dest.outputStream().use { output -> input.copyTo(output) }
    } ?: return null
    Uri.fromFile(dest)
}.getOrNull()
