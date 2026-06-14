package com.panorama.android.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.panorama.core.calibration.AxisConvention
import com.panorama.core.calibration.ViewCalibration
import com.panorama.core.math.GazeState
import com.panorama.core.orientation.GazePredictor
import com.panorama.core.projection.ProjectionModel
import com.panorama.core.vr.StereoEyeLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** GL ES 2 renderer that draws the panorama sphere from inside, textured by the video decoder's
 *  [SurfaceTexture] (OES external image). Each frame it reads the latest [GazeState] from
 *  [gazeRef], extrapolates it forward by [leadTimeMs] (motion-to-photon lead), turns it into a
 *  view matrix via [ViewCalibration] (Site A — the only place axis signs live), and draws.
 *
 *  Sign-free by construction: this file applies no yaw/pitch/V-flip signs of its own. View signs
 *  come from [AxisConvention] through [ViewCalibration]; the texture V-flip arrives at runtime in
 *  the SurfaceTexture transform matrix (uStMatrix), never baked into the shader or the mesh.
 *
 *  No-alloc hot path: [onDrawFrame] allocates nothing. The mesh VBOs, the per-eye / per-frame
 *  FloatArray(16) matrix scratch buffers and the stMatrix buffer are all created once in
 *  [onSurfaceCreated] / [onSurfaceChanged].
 *
 *  @param projectionModel builds the sign-free sphere mesh (Task 1.6).
 *  @param gazeRef lock-free orientation snapshot written by the sensor thread (Task 2.2).
 *  @param axisConvention Site-A signs handed to [ViewCalibration].
 *  @param onSurfaceTextureReady called on the GL thread once the OES SurfaceTexture exists, so the
 *         owner can create a [android.view.Surface] for the player. */
class PanoramaRenderer(
    private val projectionModel: ProjectionModel,
    gazeRef: AtomicReference<GazeState>,
    private val axisConvention: AxisConvention = AxisConvention(),
    private val onSurfaceTextureReady: (SurfaceTexture) -> Unit,
) : GLSurfaceView.Renderer {

    /** Lock-free orientation snapshot read each GL frame. The reference itself is volatile so the UI
     *  thread can swap in the sensor engine's own AtomicReference (see [PanoramaGlView.bindGazeRef])
     *  while the GL thread keeps reading: a single reference write is atomic, no torn state. */
    @Volatile
    var gazeRef: AtomicReference<GazeState> = gazeRef

    /** Set true by the SurfaceTexture's OnFrameAvailableListener (any thread); consumed and cleared
     *  on the GL thread in [onDrawFrame]. Volatile is enough: single flag, no compound update. */
    @Volatile
    var pendingFrame: Boolean = false

    /** Split-screen VR toggle. Read on the GL thread; written from the UI thread. */
    @Volatile
    var vrEnabled: Boolean = false

    /** Single source of truth for the equirect texture vertical flip. When true (the default for
     *  this device/decoder) onDrawFrame composes a V-flip onto the decoder's stMatrix. Flip this to
     *  false if a device delivers an already-flipped transform and the image appears upside down. */
    @Volatile
    var flipV: Boolean = true

    /** Per-eye yaw straddle for the stereo pair (degrees). */
    @Volatile
    var ipdYawDeg: Float = DEFAULT_IPD_YAW_DEG

    /** Motion-to-photon prediction horizon handed to [GazePredictor]. */
    @Volatile
    var leadTimeMs: Float = DEFAULT_LEAD_TIME_MS

    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uMvpLoc = 0
    private var uStMatrixLoc = 0
    private var uTextureLoc = 0

    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null

    private var positionVbo = 0
    private var texCoordVbo = 0
    private var indexVbo = 0
    private var indexCount = 0

    private var viewportWidth = 0
    private var viewportHeight = 0

    // Preallocated GL-thread scratch — never allocated in onDrawFrame.
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val stMatrix = FloatArray(16)
    private val stMatrixSrc = FloatArray(16)

    /** Diagnostic counter: number of decoded frames pulled into the OES texture. Logged sparsely so
     *  logcat shows frames are actually flowing (or not) without flooding. */
    private var framesPulled = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram(Shaders.VERTEX, Shaders.FRAGMENT)
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uMvpLoc = GLES20.glGetUniformLocation(program, "uMvp")
        uStMatrixLoc = GLES20.glGetUniformLocation(program, "uStMatrix")
        uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")

        textureId = createOesTexture()
        val st = SurfaceTexture(textureId)
        surfaceTexture = st

        uploadMesh()

        // Identity until the first updateTexImage delivers the decoder's real transform.
        Matrix.setIdentityM(stMatrix, 0)

        onSurfaceTextureReady(st)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
        val aspect = if (height == 0) 1f else width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, FOV_Y_DEG, aspect, NEAR, FAR)
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return

        // Pull the newest decoded frame into the OES texture, with its transform.
        if (pendingFrame) {
            pendingFrame = false
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
            // V-flip lives HERE and only here (project invariant). The decoder's stMatrix does not
            // reliably carry the equirect vertical flip on every device/codec, so we compose an
            // explicit V-flip on top of it. [flipV] is the single quick-toggle if a device needs the
            // other parity. Applied as stMatrix' = flip(v) * stMatrix, via a scratch buffer so the
            // hot path stays allocation-free (multiplyMM forbids aliasing src/dst).
            if (flipV) {
                System.arraycopy(stMatrix, 0, stMatrixSrc, 0, 16)
                Matrix.multiplyMM(stMatrix, 0, V_FLIP_MATRIX, 0, stMatrixSrc, 0)
            }
            framesPulled++
            if (framesPulled % FRAME_LOG_INTERVAL == 1L) {
                val g = gazeRef.get()
                Log.i(TAG, "frame #$framesPulled flipV=$flipV vr=$vrEnabled " +
                    "gaze yaw=${g.yawDeg} pitch=${g.pitchDeg} velDeg/s=${g.angularVelocityDegPerSec}")
            }
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glUniformMatrix4fv(uStMatrixLoc, 1, false, stMatrix, 0)

        bindMeshAttributes()

        val predicted = GazePredictor.predict(gazeRef.get(), leadTimeMs)

        if (vrEnabled) {
            val (leftGaze, rightGaze) = StereoEyeLayout.stereoGaze(predicted, ipdYawDeg)
            val half = viewportWidth / 2
            // Left eye -> left half, right eye -> right half: two passes, one draw each.
            drawEye(leftGaze, 0, 0, half, viewportHeight)
            drawEye(rightGaze, half, 0, viewportWidth - half, viewportHeight)
        } else {
            drawEye(predicted, 0, 0, viewportWidth, viewportHeight)
        }

        disableMeshAttributes()
    }

    /** One draw pass into a viewport rectangle for a single gaze. No allocation. */
    private fun drawEye(gaze: GazeState, x: Int, y: Int, w: Int, h: Int) {
        GLES20.glViewport(x, y, w, h)
        ViewCalibration.viewMatrix(gaze, axisConvention, viewMatrix)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, 0)
    }

    private fun bindMeshAttributes() {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, positionVbo)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 0, 0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, texCoordVbo)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, 0)

        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexVbo)
    }

    private fun disableMeshAttributes() {
        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun uploadMesh() {
        val mesh = projectionModel.buildMesh(MESH_STACKS, MESH_SLICES)
        indexCount = mesh.indices.size

        val buffers = IntArray(3)
        GLES20.glGenBuffers(3, buffers, 0)
        positionVbo = buffers[0]
        texCoordVbo = buffers[1]
        indexVbo = buffers[2]

        val positionBuffer = mesh.positions.toFloatBuffer()
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, positionVbo)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            mesh.positions.size * BYTES_PER_FLOAT,
            positionBuffer,
            GLES20.GL_STATIC_DRAW,
        )

        val texCoordBuffer = mesh.texCoords.toFloatBuffer()
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, texCoordVbo)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            mesh.texCoords.size * BYTES_PER_FLOAT,
            texCoordBuffer,
            GLES20.GL_STATIC_DRAW,
        )

        val indexBuffer = mesh.indices.toShortBuffer()
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexVbo)
        GLES20.glBufferData(
            GLES20.GL_ELEMENT_ARRAY_BUFFER,
            mesh.indices.size * BYTES_PER_SHORT,
            indexBuffer,
            GLES20.GL_STATIC_DRAW,
        )

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun createOesTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        return id
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw RuntimeException("Could not link program: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Could not compile shader $type: $log")
        }
        return shader
    }

    private fun FloatArray.toFloatBuffer(): FloatBuffer =
        ByteBuffer.allocateDirect(size * BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .also { it.put(this).position(0) }

    private fun ShortArray.toShortBuffer(): ShortBuffer =
        ByteBuffer.allocateDirect(size * BYTES_PER_SHORT)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .also { it.put(this).position(0) }

    companion object {
        private const val TAG = "PanoramaRenderer"
        private const val FRAME_LOG_INTERVAL = 60L
        private const val MESH_STACKS = 64
        private const val MESH_SLICES = 128
        private const val FOV_Y_DEG = 90f
        private const val NEAR = 0.1f
        private const val FAR = 10f
        private const val DEFAULT_LEAD_TIME_MS = 30f
        private const val DEFAULT_IPD_YAW_DEG = 5f
        private const val BYTES_PER_FLOAT = 4
        private const val BYTES_PER_SHORT = 2

        /** Column-major V-flip in texture space: maps v -> 1 - v (scale -1 about v, translate +1).
         *  Composed onto the decoder transform when [flipV] is set. */
        private val V_FLIP_MATRIX = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f,
        )
    }
}
