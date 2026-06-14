package com.panorama.core.detection

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire object inside a sidecar frame: centre as a plain [x,y] list, optional bbox + label. */
@Serializable
data class SidecarObject(
    val centerNorm: List<Float> = emptyList(),
    val bboxNorm: List<Float>? = null,
    val label: String? = null,
)

/** Wire frame: a video timestamp (ms) and the objects detected at that time. */
@Serializable
data class SidecarFrame(
    val timeMs: Long = 0L,
    val objects: List<SidecarObject> = emptyList(),
)

/** Top-level wire shape: a time-ordered list of frames. */
@Serializable
data class Sidecar(
    val frames: List<SidecarFrame> = emptyList(),
)

/** Parses sidecar JSON into the [Sidecar] wire model. A missing or corrupt sidecar must never
 *  crash the runtime, so any parse failure (including empty input) degrades to an empty Sidecar. */
object SidecarParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(text: String): Sidecar =
        try {
            json.decodeFromString<Sidecar>(text)
        } catch (_: Exception) {
            Sidecar()
        }
}
