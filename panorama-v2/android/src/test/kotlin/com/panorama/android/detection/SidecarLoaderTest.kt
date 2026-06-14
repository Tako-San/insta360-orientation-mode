package com.panorama.android.detection

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Unit tests for [SidecarLoader]. Robolectric gives a working [android.content.ContentResolver]
 *  and [Uri] parser; the test writes a temp JSON file and hands the loader a file:// Uri, which
 *  the resolver opens like any content stream. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SidecarLoaderTest {

    private fun resolver() =
        RuntimeEnvironment.getApplication().contentResolver

    /** Writes [text] to a temp file and returns its file:// [Uri]. */
    private fun tempUri(text: String): Uri {
        val file = File.createTempFile("sidecar", ".json")
        file.writeText(text)
        file.deleteOnExit()
        return Uri.fromFile(file)
    }

    @Test
    fun `loads sidecar from uri and queries detections`() {
        val json = """
            {"frames":[
              {"timeMs":0,"objects":[{"centerNorm":[0.5,0.5],"label":"drone"}]},
              {"timeMs":1000,"objects":[{"centerNorm":[0.25,0.75]}]}
            ]}
        """.trimIndent()
        val loader = SidecarLoader(resolver())

        val source = loader.load(tempUri(json))

        assertTrue(source.available)
        val atZero = source.detectionsAt(0)
        assertEquals(1, atZero.size)
        assertEquals(0.5f, atZero[0].centerNorm.x, 1e-6f)
        assertEquals("drone", atZero[0].label)

        val atOne = source.detectionsAt(1000)
        assertEquals(0.25f, atOne[0].centerNorm.x, 1e-6f)
    }

    @Test
    fun `empty or corrupt uri yields empty unavailable source`() {
        val loader = SidecarLoader(resolver())

        val source = loader.load(tempUri("not json at all"))

        assertFalse(source.available)
        assertTrue(source.detectionsAt(0).isEmpty())
    }
}
