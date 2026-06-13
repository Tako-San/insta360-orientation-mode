# Variant C: Custom GL Panorama Renderer — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace media3 `SphericalGLSurfaceView` in the offline player with an own
`GLSurfaceView`-based equirectangular renderer driven directly by the gyro quaternion, removing
the private-renderer reflection (N1) and probe-A logging (N2).

**Architecture:** Pure mesh + view/projection matrix math in `:lib` (JVM-tested). GL plumbing
(`PanoramaGLSurfaceView` + `PanoramaRenderer`) in `:app`: a `SurfaceTexture` (OES) receives the
ExoPlayer video; each frame the renderer draws an inward-facing UV sphere with the MVP matrix
built from the gaze quaternion. The same gaze drives the direction arrow — one source of truth.

**Tech Stack:** Kotlin, OpenGL ES 2.0, Android `GLSurfaceView`/`SurfaceTexture`, ExoPlayer
(media3), JUnit4, Kover. Reuses `:lib` `UnitQuaternion` (+X forward, +Y right, +Z up).

---

## File Structure

**`:lib` (pure, JVM-tested):**
- `lib/.../panorama/SphereMesh.kt` — UV-sphere geometry generator + `SphereMeshData`.
- `lib/.../panorama/ViewMatrixMath.kt` — view matrix from quaternion + perspective matrix.
- `lib/.../panorama/SphereMeshTest.kt`, `ViewMatrixMathTest.kt`.

**`:app` (GL, device-verified):**
- `app/.../ui/player/gl/PanoramaRenderer.kt` — `GLSurfaceView.Renderer`.
- `app/.../ui/player/gl/PanoramaGLSurfaceView.kt` — `GLSurfaceView` subclass, exposes
  `videoSurface` + `setOrientation`.
- Modify: `res/layout/activity_local_spherical_player.xml`, `LocalSphericalPlayerActivity.kt`.
- Delete: `app/.../ui/player/Media3SphericalOrientationSink.kt`.

---

## Task 1: `SphereMesh` in :lib (TDD)

**Files:**
- Create: `lib/src/main/kotlin/com/arashivision/orientation/panorama/SphereMesh.kt`
- Test: `lib/src/test/kotlin/com/arashivision/orientation/panorama/SphereMeshTest.kt`

- [ ] **Step 1: Write the failing test**

`SphereMeshTest.kt`:
```kotlin
package com.arashivision.orientation.panorama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class SphereMeshTest {
    @Test
    fun `vertex and index counts match stacks and slices`() {
        val m = SphereMesh.generate(stacks = 32, slices = 64, radius = 1f)
        // (stacks+1)*(slices+1) vertices, 3 floats each for position, 2 for uv
        val verts = (32 + 1) * (64 + 1)
        assertEquals(verts * 3, m.positions.size)
        assertEquals(verts * 2, m.texCoords.size)
        assertEquals(32 * 64 * 6, m.indices.size)
    }

    @Test
    fun `all positions lie on the sphere of given radius`() {
        val m = SphereMesh.generate(stacks = 8, slices = 16, radius = 2f)
        var i = 0
        while (i < m.positions.size) {
            val x = m.positions[i]; val y = m.positions[i + 1]; val z = m.positions[i + 2]
            val r = sqrt(x * x + y * y + z * z)
            assertEquals(2f, r, 1e-3f)
            i += 3
        }
    }

    @Test
    fun `texcoords are within unit range`() {
        val m = SphereMesh.generate(stacks = 8, slices = 16)
        for (uv in m.texCoords) assertTrue(uv in -1e-4f..1.0001f)
    }

    @Test
    fun `indices reference valid vertices`() {
        val m = SphereMesh.generate(stacks = 4, slices = 8)
        val vertexCount = (4 + 1) * (8 + 1)
        for (idx in m.indices) assertTrue(idx.toInt() in 0 until vertexCount)
    }
}
```

- [ ] **Step 2: Run, expect FAIL** — `:lib:test --tests "*SphereMeshTest*"` → unresolved `SphereMesh`.

- [ ] **Step 3: Implement**

`SphereMesh.kt`:
```kotlin
package com.arashivision.orientation.panorama

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/** Interleave-free mesh buffers for an equirectangular UV sphere. */
data class SphereMeshData(
    val positions: FloatArray,  // x,y,z per vertex
    val texCoords: FloatArray,  // u,v per vertex
    val indices: ShortArray     // triangle list
)

/**
 * Pure (JVM) generator of an inward-facing equirectangular UV sphere. The camera sits at the
 * origin and looks at the inner surface, so the triangle winding is set for inward faces.
 * Coordinate system matches :lib UnitQuaternion: +X forward, +Y right, +Z up.
 */
object SphereMesh {
    fun generate(stacks: Int = 32, slices: Int = 64, radius: Float = 1f): SphereMeshData {
        val positions = FloatArray((stacks + 1) * (slices + 1) * 3)
        val texCoords = FloatArray((stacks + 1) * (slices + 1) * 2)
        var p = 0
        var t = 0
        for (i in 0..stacks) {
            val v = i.toFloat() / stacks          // 0..1 top→bottom
            val phi = (v * PI).toFloat()          // polar angle 0..π
            for (j in 0..slices) {
                val u = j.toFloat() / slices      // 0..1 around
                val theta = (u * 2.0 * PI).toFloat()
                // +Z up sphere; longitude around Z, latitude from +Z pole.
                val sinPhi = sin(phi); val cosPhi = cos(phi)
                val x = radius * sinPhi * cos(theta)
                val y = radius * sinPhi * sin(theta)
                val z = radius * cosPhi
                positions[p++] = x; positions[p++] = y; positions[p++] = z
                texCoords[t++] = u; texCoords[t++] = v
            }
        }
        val indices = ShortArray(stacks * slices * 6)
        var idx = 0
        val stride = slices + 1
        for (i in 0 until stacks) {
            for (j in 0 until slices) {
                val a = (i * stride + j).toShort()
                val b = (i * stride + j + 1).toShort()
                val c = ((i + 1) * stride + j).toShort()
                val d = ((i + 1) * stride + j + 1).toShort()
                // inward-facing winding
                indices[idx++] = a; indices[idx++] = c; indices[idx++] = b
                indices[idx++] = b; indices[idx++] = c; indices[idx++] = d
            }
        }
        return SphereMeshData(positions, texCoords, indices)
    }
}
```

- [ ] **Step 4: Run, expect PASS** (4 tests).
- [ ] **Step 5: Commit** — `feat(lib): SphereMesh equirectangular UV-sphere generator + tests`

---

## Task 2: `ViewMatrixMath` in :lib (TDD)

**Files:**
- Create: `lib/src/main/kotlin/com/arashivision/orientation/panorama/ViewMatrixMath.kt`
- Test: `lib/src/test/kotlin/com/arashivision/orientation/panorama/ViewMatrixMathTest.kt`

- [ ] **Step 1: Write the failing test**

`ViewMatrixMathTest.kt`:
```kotlin
package com.arashivision.orientation.panorama

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewMatrixMathTest {
    private val eps = 1e-4f

    @Test
    fun `identity quaternion yields identity rotation`() {
        val out = FloatArray(16)
        ViewMatrixMath.viewFromQuaternion(UnitQuaternion.IDENTITY, out)
        val ident = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)
        for (i in 0..15) assertEquals("idx $i", ident[i], out[i], eps)
    }

    @Test
    fun `rotation part is orthonormal`() {
        val out = FloatArray(16)
        val q = UnitQuaternion.fromYawPitch(Math.toRadians(40.0), Math.toRadians(20.0))
        ViewMatrixMath.viewFromQuaternion(q, out)
        // columns of the 3x3 rotation part must be unit length
        fun col(c: Int) = floatArrayOf(out[c], out[c + 4], out[c + 8])
        for (c in 0..2) {
            val v = col(c)
            val len = Math.sqrt((v[0]*v[0] + v[1]*v[1] + v[2]*v[2]).toDouble()).toFloat()
            assertEquals(1f, len, 1e-3f)
        }
    }

    @Test
    fun `perspective matrix matches reference for known fov and aspect`() {
        val out = FloatArray(16)
        ViewMatrixMath.perspective(fovYDeg = 90f, aspect = 1f, near = 0.1f, far = 10f, out = out)
        // tan(45)=1 → m[0]=m[5]=1
        assertEquals(1f, out[0], eps)
        assertEquals(1f, out[5], eps)
        assertEquals(-1f, out[11], eps) // perspective w = -z
        assertEquals(0f, out[15], eps)
    }
}
```

- [ ] **Step 2: Run, expect FAIL** — unresolved `ViewMatrixMath`.

- [ ] **Step 3: Implement**

`ViewMatrixMath.kt`:
```kotlin
package com.arashivision.orientation.panorama

import kotlin.math.tan

/**
 * Pure (JVM) GL matrix builders for the panorama renderer. Column-major 4x4 (OpenGL layout).
 *
 * The view matrix is the rotation that maps world space into the camera's gaze frame: it is the
 * conjugate (inverse) of the gaze quaternion applied as a rotation matrix. The camera stays at
 * the origin (the sphere surrounds it), so there is no translation.
 */
object ViewMatrixMath {
    fun viewFromQuaternion(q: UnitQuaternion, out: FloatArray) {
        // inverse of gaze = conjugate; build its rotation matrix (column-major)
        val c = q.conjugate()
        val x = c.x.toFloat(); val y = c.y.toFloat(); val z = c.z.toFloat(); val w = c.w.toFloat()
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        // column 0
        out[0] = 1f - 2f * (yy + zz); out[1] = 2f * (xy + wz);      out[2] = 2f * (xz - wy);      out[3] = 0f
        // column 1
        out[4] = 2f * (xy - wz);      out[5] = 1f - 2f * (xx + zz); out[6] = 2f * (yz + wx);      out[7] = 0f
        // column 2
        out[8] = 2f * (xz + wy);      out[9] = 2f * (yz - wx);      out[10] = 1f - 2f * (xx + yy); out[11] = 0f
        // column 3
        out[12] = 0f; out[13] = 0f; out[14] = 0f; out[15] = 1f
    }

    fun perspective(fovYDeg: Float, aspect: Float, near: Float, far: Float, out: FloatArray) {
        val f = 1f / tan(Math.toRadians(fovYDeg.toDouble()).toFloat() / 2f)
        for (i in 0..15) out[i] = 0f
        out[0] = f / aspect
        out[5] = f
        out[10] = (far + near) / (near - far)
        out[11] = -1f
        out[14] = (2f * far * near) / (near - far)
    }
}
```

- [ ] **Step 4: Run, expect PASS** (3 tests).
- [ ] **Step 5: Commit** — `feat(lib): ViewMatrixMath view+perspective matrices for GL renderer + tests`

---

## Task 3: `PanoramaRenderer` (GL ES 2) — :app

**Files:**
- Create: `app/src/main/java/com/arashivision/sdk/demo/ui/player/gl/PanoramaRenderer.kt`

GL code — not unit-tested; verified on device in Task 6.

- [ ] **Step 1: Implement the renderer**

`PanoramaRenderer.kt`:
```kotlin
package com.arashivision.sdk.demo.ui.player.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.arashivision.orientation.panorama.SphereMesh
import com.arashivision.orientation.panorama.SphereMeshData
import com.arashivision.orientation.panorama.UnitQuaternion
import com.arashivision.orientation.panorama.ViewMatrixMath
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws an inward-facing equirectangular sphere textured by an external OES texture (the video
 * SurfaceTexture). The MVP matrix is rebuilt each frame from the gaze quaternion. No reflection,
 * no media3 internals.
 *
 * @param onTextureReady called on the GL thread with the OES texture id once created, so the
 *   owning view can build its SurfaceTexture.
 */
class PanoramaRenderer(
    private val onTextureReady: (Int) -> Unit
) : GLSurfaceView.Renderer {

    @Volatile private var orientation: UnitQuaternion = UnitQuaternion.IDENTITY
    @Volatile var stMatrix: FloatArray = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    /** Set by the view: called on the GL thread before drawing to pull the latest frame. */
    var onDrawFrameCallback: (() -> Unit)? = null

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uMvp = 0
    private var uStMatrix = 0
    private var uTexture = 0
    private var textureId = 0

    private lateinit var mesh: SphereMeshData
    private lateinit var positionBuf: FloatBuffer
    private lateinit var texCoordBuf: FloatBuffer
    private lateinit var indexBuf: ShortBuffer

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)

    fun setOrientation(q: UnitQuaternion) { orientation = q }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        program = buildProgram(VERTEX_SRC, FRAGMENT_SRC)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uStMatrix = GLES20.glGetUniformLocation(program, "uStMatrix")
        uTexture = GLES20.glGetUniformLocation(program, "uTexture")

        mesh = SphereMesh.generate()
        positionBuf = mesh.positions.toFloatBuffer()
        texCoordBuf = mesh.texCoords.toFloatBuffer()
        indexBuf = mesh.indices.toShortBuffer()

        onTextureReady(textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = if (height == 0) 1f else width.toFloat() / height
        ViewMatrixMath.perspective(fovYDeg = 90f, aspect = aspect, near = 0.1f, far = 10f, out = projection)
    }

    override fun onDrawFrame(gl: GL10?) {
        onDrawFrameCallback?.invoke()   // view: updateTexImage() + stMatrix
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        ViewMatrixMath.viewFromQuaternion(orientation, view)
        Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uStMatrix, 1, false, stMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uTexture, 0)

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, positionBuf)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuf)

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indices.size, GLES20.GL_UNSIGNED_SHORT, indexBuf)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
    }

    private fun buildProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "program link failed: ${GLES20.glGetProgramInfoLog(prog)}" }
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private fun FloatArray.toFloatBuffer(): FloatBuffer =
        ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also {
            it.put(this); it.position(0)
        }

    private fun ShortArray.toShortBuffer(): ShortBuffer =
        ByteBuffer.allocateDirect(size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().also {
            it.put(this); it.position(0)
        }

    companion object {
        private const val VERTEX_SRC = """
            uniform mat4 uMvp;
            uniform mat4 uStMatrix;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvp * aPosition;
                vTexCoord = (uStMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT_SRC = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
```

- [ ] **Step 2: Compile** — `:app:compileDebugKotlin` → BUILD SUCCESSFUL.
- [ ] **Step 3: Commit** — `feat(app): PanoramaRenderer (GL ES 2 equirect sphere, OES video texture)`

---

## Task 4: `PanoramaGLSurfaceView` — :app

**Files:**
- Create: `app/src/main/java/com/arashivision/sdk/demo/ui/player/gl/PanoramaGLSurfaceView.kt`

- [ ] **Step 1: Implement the view**

`PanoramaGLSurfaceView.kt`:
```kotlin
package com.arashivision.sdk.demo.ui.player.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.Surface
import com.arashivision.orientation.panorama.UnitQuaternion

/**
 * Own panorama view: a GLSurfaceView that renders an equirectangular sphere via [PanoramaRenderer]
 * and exposes [videoSurface] for ExoPlayer plus [setOrientation] for the gyro. Replaces media3
 * SphericalGLSurfaceView; no reflection.
 */
class PanoramaGLSurfaceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer: PanoramaRenderer
    private var surfaceTexture: SurfaceTexture? = null

    /** The Surface ExoPlayer should render the video into. Null until the GL texture is ready. */
    @Volatile var videoSurface: Surface? = null
        private set

    /** Called once when videoSurface becomes available (so the Activity can attach ExoPlayer). */
    var onVideoSurfaceReady: ((Surface) -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        renderer = PanoramaRenderer(onTextureReady = { texId -> createSurfaceTexture(texId) })
        renderer.onDrawFrameCallback = {
            surfaceTexture?.let { st ->
                st.updateTexImage()
                st.getTransformMatrix(renderer.stMatrix)
            }
        }
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    private fun createSurfaceTexture(textureId: Int) {
        // runs on GL thread (from onSurfaceCreated)
        val st = SurfaceTexture(textureId)
        st.setOnFrameAvailableListener { requestRender() }
        surfaceTexture = st
        val surface = Surface(st)
        videoSurface = surface
        post { onVideoSurfaceReady?.invoke(surface) }
    }

    fun setOrientation(q: UnitQuaternion) {
        renderer.setOrientation(q)
        requestRender()
    }

    fun release() {
        videoSurface?.release()
        videoSurface = null
        surfaceTexture?.release()
        surfaceTexture = null
    }
}
```

- [ ] **Step 2: Compile** — `:app:compileDebugKotlin` → BUILD SUCCESSFUL.
- [ ] **Step 3: Commit** — `feat(app): PanoramaGLSurfaceView (SurfaceTexture→ExoPlayer, setOrientation)`

---

## Task 5: Integrate into the offline player; remove media3 sink

**Files:**
- Modify: `app/src/main/res/layout/activity_local_spherical_player.xml`
- Modify: `app/src/main/java/com/arashivision/sdk/demo/ui/player/LocalSphericalPlayerActivity.kt`
- Delete: `app/src/main/java/com/arashivision/sdk/demo/ui/player/Media3SphericalOrientationSink.kt`

- [ ] **Step 1: Swap the view in the layout**

In `activity_local_spherical_player.xml`, replace the
`androidx.media3.exoplayer.video.spherical.SphericalGLSurfaceView` element (keep `android:id`
`@id/spherical_view` and the same width/height/constraints) with:
```xml
<com.arashivision.sdk.demo.ui.player.gl.PanoramaGLSurfaceView
    android:id="@+id/sphericalView"
    ... (same layout attributes as before) />
```
(Keep the binding field name `sphericalView`.)

- [ ] **Step 2: Wire ExoPlayer to the new surface**

In `LocalSphericalPlayerActivity.onStart()`, replace
`exo.setVideoSurfaceView(binding.sphericalView)` with attaching the surface once ready:
```kotlin
binding.sphericalView.onVideoSurfaceReady = { surface ->
    player?.setVideoSurface(surface)
}
```
(If `player` is created after the surface, also set it in the player-creation block:
`binding.sphericalView.videoSurface?.let { exo.setVideoSurface(it) }`.)

- [ ] **Step 3: Drive orientation from the gyro, delete the sink**

In `LocalSphericalPlayerActivity`:
- Remove the `playerSink` field and the `Media3SphericalOrientationSink` import.
- In `tryApplyOrientation`, after computing the smoothed gaze (yaw/pitch via
  `orientationCoordinator.coordinate(...)`), build the quaternion and feed the view:
```kotlin
val gaze = EquirectangularProjection.fromYawPitch(
    yawRad = Math.toRadians(gazeYawDeg.toDouble()),
    pitchRad = Math.toRadians(gazePitchDeg.coerceIn(-MAX_PITCH_DEG, MAX_PITCH_DEG).toDouble())
)
currentGazeDirection = gaze
binding.sphericalView.setOrientation(gaze.orientation)
```
- Remove `binding.sphericalView.setDefaultStereoMode(...)` and `setUseSensorRotation(false)`
  (media3-only). Keep `onResume`/`onPause` calling `binding.sphericalView.onResume()/onPause()`
  (GLSurfaceView has these). Add `binding.sphericalView.release()` in `onStop()`.

- [ ] **Step 4: Delete the media3 sink**

```bash
git rm app/src/main/java/com/arashivision/sdk/demo/ui/player/Media3SphericalOrientationSink.kt
```
Also remove the now-unused `OrientationApplier` interface IF nothing else implements it
(`grep -rl OrientationApplier app/src` — if only the deleted sink referenced it, delete it too;
otherwise leave it).

- [ ] **Step 5: Compile** — `:app:compileDebugKotlin` → BUILD SUCCESSFUL. Fix any leftover refs.
- [ ] **Step 6: Commit** — `refactor(player): render panorama via own PanoramaGLSurfaceView, drop media3 reflection sink`

---

## Task 6: On-device verification (gates Variant C)

- [ ] **Step 1: Build + install**
```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/farid/android-sdk ./gradlew :app:assembleDebug --no-daemon
/home/farid/android-sdk/platform-tools/adb install -r app/build/outputs/apk/debug/insta_sdk_demo_debug_1.8.1_build_06.apk
```

- [ ] **Step 2: Manual check (user)** — offline player → load 360 video. Confirm:
  1. Sphere renders the video (not black, not a flat rectangle).
  2. Texture orientation correct (not upside-down / mirrored). If flipped, fix UV/stMatrix in
     `PanoramaGLSurfaceView.onDrawFrameCallback` or the V coordinate in `SphereMesh` (one place).
  3. Calibrate + rotate phone → view rotates the SAME direction as probe A.
  4. Arrow stays in sync with the sphere.
  5. VR split-screen still mirrors (PixelCopy from the new GLSurfaceView).
  6. No log spam, no reflection, no crash.

- [ ] **Step 3:** If direction/axis is wrong, correct it ONCE in `ViewMatrixMath.viewFromQuaternion`
  (e.g. compose with a fixed axis remap) and pin it with a test; do not scatter negations.

---

## Self-Review notes

- **Spec coverage:** SphereMesh (§3) → Task 1; ViewMatrixMath (§3) → Task 2; PanoramaRenderer
  (§4) → Task 3; PanoramaGLSurfaceView (§4) → Task 4; integration + sink removal (§5) → Task 5;
  device verification (§8) → Task 6. Single-source orientation (§6) → Task 5 Step 3. N1/N2
  removal → Task 5 Step 4 (sink deleted = reflection + logging gone).
- **Type consistency:** `SphereMeshData(positions, texCoords, indices)` used in Task 1 & 3;
  `ViewMatrixMath.viewFromQuaternion(q, out)` / `perspective(...)` consistent Task 2 & 3;
  `UnitQuaternion` (existing `:lib`, conjugate/IDENTITY/fromYawPitch) used in Tasks 2,3,4,5;
  `PanoramaGLSurfaceView.videoSurface/setOrientation/onVideoSurfaceReady/release` consistent
  Task 4 & 5.
- **Coordinate caveat (device):** `SphereMesh` uses +Z-up longitude-around-Z; the exact axis
  mapping vs the gyro gaze is pinned on device (Task 6 Step 3) — the only part not provable on
  the JVM. Everything else (counts, ranges, matrix orthonormality, perspective values) is tested.
- **Kover:** new `:lib` files fall under the existing `:lib` Kover report automatically.
```
