package com.arashivision.orientation.detection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Parses detection sidecar JSON files produced for offline panoramic videos. */
class VideoDetectionSidecarParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(input: String): VideoDetectionSidecar {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "Detection JSON is empty" }

        val framesArray: JsonArray = when (trimmed.first()) {
            '[' -> json.parseToJsonElement(trimmed).jsonArray
            '{' -> parseObjectRootOrObjectSequence(trimmed)
            else -> error("Detection JSON must start with '[' or '{'")
        }

        val frames = framesArray
            .map { parseFrame(it.jsonObject) }
            .sortedWith(compareBy<VideoDetectionFrame> { it.timeSec }.thenBy { it.frameIdx })

        return VideoDetectionSidecar(frames)
    }

    private fun parseObjectRootOrObjectSequence(jsonText: String): JsonArray {
        return try {
            val root = json.parseToJsonElement(jsonText).jsonObject
            when {
                root.containsKey("frame_idx") -> JsonArray(listOf(root))
                else -> findFramesArray(root)
            }
        } catch (e: Exception) {
            // «Голая» comma-separated последовательность объектов без обрамляющих [ ].
            json.parseToJsonElement("[${jsonText.trimEnd().trimEnd(',')}]").jsonArray
        }
    }

    private fun findFramesArray(root: JsonObject): JsonArray {
        val supportedKeys = listOf("frames", "detections", "data")
        val key = supportedKeys.firstOrNull { root[it] is JsonArray }
        return key?.let { root[it]!!.jsonArray }
            ?: error("Detection JSON object must contain one of: ${supportedKeys.joinToString()}")
    }

    private fun parseFrame(frameJson: JsonObject): VideoDetectionFrame {
        val objectsArray = (frameJson["objects"] as? JsonArray) ?: JsonArray(emptyList())
        return VideoDetectionFrame(
            frameIdx = frameJson.getValue("frame_idx").jsonPrimitive.int,
            timeSec = frameJson.getValue("time_sec").jsonPrimitive.double,
            objects = objectsArray.map { parseObject(it.jsonObject) }
        )
    }

    private fun parseObject(objectJson: JsonObject): VideoDetectedObject {
        return VideoDetectedObject(
            trackId = objectJson.getValue("track_id").jsonPrimitive.int,
            bboxXyxy = objectJson.getValue("bbox_xyxy").jsonArray.toBboxXyxy(),
            centerXy = objectJson.getValue("center_xy").jsonArray.toPoint2d(),
            centerNorm = objectJson.getValue("center_norm").jsonArray.toPoint2d()
        )
    }

    private fun JsonArray.toBboxXyxy(): BboxXyxy {
        require(size == 4) { "bbox_xyxy must contain four numbers" }
        return BboxXyxy(
            left = this[0].jsonPrimitive.double,
            top = this[1].jsonPrimitive.double,
            right = this[2].jsonPrimitive.double,
            bottom = this[3].jsonPrimitive.double
        )
    }

    private fun JsonArray.toPoint2d(): Point2d {
        require(size == 2) { "point array must contain two numbers" }
        return Point2d(
            x = this[0].jsonPrimitive.double,
            y = this[1].jsonPrimitive.double
        )
    }
}
