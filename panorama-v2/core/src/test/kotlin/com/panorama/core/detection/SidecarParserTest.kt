package com.panorama.core.detection

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Pins the sidecar JSON contract: well-formed input parses frames + objects (label preserved),
 *  and malformed/empty input degrades to an empty Sidecar instead of throwing — the runtime must
 *  never crash on a missing or corrupt sidecar. */
class SidecarParserTest : FunSpec({

    test("parses frames and objects with label preserved") {
        val json = """
            {"frames":[
              {"timeMs":0,"objects":[{"centerNorm":[0.5,0.5],"label":"drone"}]},
              {"timeMs":100,"objects":[{"centerNorm":[0.1,0.2]}]}
            ]}
        """.trimIndent()
        val sidecar = SidecarParser.parse(json)
        sidecar.frames.size shouldBe 2
        sidecar.frames[0].timeMs shouldBe 0L
        sidecar.frames[0].objects.size shouldBe 1
        sidecar.frames[0].objects[0].centerNorm shouldBe listOf(0.5f, 0.5f)
        sidecar.frames[0].objects[0].label shouldBe "drone"
        sidecar.frames[1].objects[0].label shouldBe null
    }

    test("empty string parses to empty sidecar without crashing") {
        SidecarParser.parse("").frames shouldBe emptyList()
    }

    test("garbage parses to empty sidecar without crashing") {
        SidecarParser.parse("{garbage").frames shouldBe emptyList()
    }
})
