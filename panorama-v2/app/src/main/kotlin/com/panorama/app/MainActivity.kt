package com.panorama.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.panorama.app.library.LibraryScreen
import com.panorama.app.library.copyToCache
import com.panorama.app.player.PlayerScreen
import com.panorama.app.settings.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** The single launcher Activity. Hosts the Compose nav graph:
 *   - "library" (start): SAF media picker + a route to settings,
 *   - "player": the 360 viewer, taking optional URL-encoded video/sidecar URIs as query args,
 *   - "settings": tuning sliders (deferred binding).
 *
 *  URIs are passed as URL-encoded strings through nav arguments and decoded back to [Uri] in the
 *  player route. @AndroidEntryPoint lets the player's [PlayerScreen] obtain its @HiltViewModel.
 *
 *  An external ACTION_VIEW with a video URI (e.g. "Open with" from a file manager) jumps straight to
 *  the player; like the SAF picker, the URI is copied to cache first so its read grant survives. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewUri = if (intent?.action == Intent.ACTION_VIEW) intent?.data else null
        setContent {
            MaterialTheme {
                Surface {
                    val nav = rememberNavController()
                    val context = LocalContext.current
                    // Honor an external "open this video" intent once, before the user touches the UI.
                    LaunchedEffect(viewUri) {
                        if (viewUri != null) {
                            val playable = copyToCache(context, viewUri, "video") ?: viewUri
                            // navigate() runs directly in this LaunchedEffect (NavHost's composition
                            // dispatcher). It must NOT be wrapped in withResumed: that hops onto
                            // Dispatchers.Main.immediate, whose snapshot write never wakes the
                            // apply-notification pump on AndroidUiDispatcher, so the player route
                            // would not compose until an unrelated event (see LibraryScreen).
                            nav.navigate(playerRoute(playable, null))
                        }
                    }
                    NavHost(navController = nav, startDestination = ROUTE_LIBRARY) {
                        composable(ROUTE_LIBRARY) {
                            LibraryScreen(
                                onPlay = { videoUri, sidecarUri ->
                                    android.util.Log.i(
                                        "MainActivity",
                                        "onPlay -> navigate; lifecycle=${lifecycle.currentState} video=$videoUri",
                                    )
                                    nav.navigate(playerRoute(videoUri, sidecarUri))
                                },
                                onOpenSettings = { nav.navigate(ROUTE_SETTINGS) },
                            )
                        }
                        composable(
                            route = "$ROUTE_PLAYER?$ARG_VIDEO={$ARG_VIDEO}&$ARG_SIDECAR={$ARG_SIDECAR}",
                            arguments = listOf(
                                navArgument(ARG_VIDEO) {
                                    type = NavType.StringType; nullable = true; defaultValue = null
                                },
                                navArgument(ARG_SIDECAR) {
                                    type = NavType.StringType; nullable = true; defaultValue = null
                                },
                            ),
                        ) { entry ->
                            val videoUri = entry.arguments?.getString(ARG_VIDEO)?.let { decodeUri(it) }
                            val sidecarUri = entry.arguments?.getString(ARG_SIDECAR)?.let { decodeUri(it) }
                            android.util.Log.i(
                                "MainActivity",
                                "PlayerScreen composing; rawArg=${entry.arguments?.getString(ARG_VIDEO)} decoded=$videoUri",
                            )
                            PlayerScreen(videoUri = videoUri, sidecarUri = sidecarUri)
                        }
                        composable(ROUTE_SETTINGS) {
                            SettingsScreen(onBack = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val ROUTE_LIBRARY = "library"
        const val ROUTE_PLAYER = "player"
        const val ROUTE_SETTINGS = "settings"
        const val ARG_VIDEO = "videoUri"
        const val ARG_SIDECAR = "sidecarUri"

        fun playerRoute(videoUri: Uri, sidecarUri: Uri?): String {
            val v = encode(videoUri.toString())
            val s = sidecarUri?.let { encode(it.toString()) } ?: ""
            return "$ROUTE_PLAYER?$ARG_VIDEO=$v&$ARG_SIDECAR=$s"
        }

        fun encode(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8.name())

        fun decodeUri(s: String): Uri? =
            if (s.isEmpty()) null else Uri.parse(URLDecoder.decode(s, StandardCharsets.UTF_8.name()))
    }
}
