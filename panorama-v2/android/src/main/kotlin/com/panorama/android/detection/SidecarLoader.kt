package com.panorama.android.detection

import android.content.ContentResolver
import android.net.Uri
import com.panorama.core.detection.DetectionTimeline
import com.panorama.core.detection.SidecarDetectionSource
import com.panorama.core.detection.SidecarParser

/** Reads a detection sidecar from a content:// or file:// [Uri] and adapts it into a
 *  [SidecarDetectionSource] for the renderer.
 *
 *  This is the Android IO boundary of spec section 4.6: byte reading via [ContentResolver] lives
 *  here, while parsing and per-frame lookup live in pure-JVM `:core`. By contract, any read or
 *  parse failure degrades to an empty (unavailable) source rather than throwing, so a missing or
 *  corrupt sidecar simply disables arrows. */
class SidecarLoader(private val resolver: ContentResolver) {

    fun load(uri: Uri): SidecarDetectionSource {
        val text = runCatching {
            resolver.openInputStream(uri)?.use { it.reader().readText() }
        }.getOrNull().orEmpty()
        val sidecar = SidecarParser.parse(text)
        return SidecarDetectionSource(DetectionTimeline(sidecar))
    }
}
