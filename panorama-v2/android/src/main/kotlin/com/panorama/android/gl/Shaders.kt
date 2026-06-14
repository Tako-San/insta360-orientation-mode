package com.panorama.android.gl

/** GL ES 2 shader sources for the panorama renderer.
 *
 *  The texture is an OES external image fed by a [android.graphics.SurfaceTexture] (the video
 *  decoder output), so the fragment shader requires the GL_OES_EGL_image_external extension and a
 *  samplerExternalOES. The vertical flip and any crop the decoder reports arrive at runtime through
 *  uStMatrix (SurfaceTexture.getTransformMatrix), so the shader and the mesh stay sign-free:
 *  no V-flip is baked in here. */
internal object Shaders {

    /** uMvp = perspective * view (column-major, from ViewCalibration + the GL projection).
     *  uStMatrix is the SurfaceTexture transform applied to the raw (u,v) before sampling. */
    const val VERTEX = """
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

    const val FRAGMENT = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        uniform samplerExternalOES uTexture;
        varying vec2 vTexCoord;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
    """
}
