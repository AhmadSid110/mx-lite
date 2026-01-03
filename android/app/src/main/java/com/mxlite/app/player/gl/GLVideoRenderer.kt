
package com.mxlite.app.player.gl

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import com.mxlite.app.player.VideoFrame

class GLVideoRenderer : GLSurfaceView.Renderer {

    private var program = 0
    private var texY = 0
    private var texU = 0
    private var texV = 0

    @Volatile
    private var frame: VideoFrame? = null

    fun renderFrame(f: VideoFrame) {
        frame = f
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)

        val textures = IntArray(3)
        GLES20.glGenTextures(3, textures, 0)
        texY = textures[0]
        texU = textures[1]
        texV = textures[2]

        setupTexture(texY)
        setupTexture(texU)
        setupTexture(texV)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val f = frame ?: return
        uploadYUV(f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun uploadYUV(f: VideoFrame) {
        val ySize = f.width * f.height
        val uvSize = ySize / 4

        val buf = ByteBuffer.wrap(f.data)

        buf.position(0)
        uploadPlane(texY, f.width, f.height, buf)

        buf.position(ySize)
        uploadPlane(texU, f.width / 2, f.height / 2, buf)

        buf.position(ySize + uvSize)
        uploadPlane(texV, f.width / 2, f.height / 2, buf)
    }

    private fun uploadPlane(tex: Int, w: Int, h: Int, buf: ByteBuffer) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_LUMINANCE,
            w,
            h,
            0,
            GLES20.GL_LUMINANCE,
            GLES20.GL_UNSIGNED_BYTE,
            buf
        )
    }

    private fun setupTexture(tex: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    }

    private fun createProgram(vs: String, fs: String): Int {
        val v = loadShader(GLES20.GL_VERTEX_SHADER, vs)
        val f = loadShader(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        GLES20.glUseProgram(p)
        return p
    }

    private fun loadShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    companion object {

        private const val VERTEX_SHADER = """
            attribute vec4 aPos;
            attribute vec2 aTex;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = aTex;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D yTex;
            uniform sampler2D uTex;
            uniform sampler2D vTexSampler;

            void main() {
                float y = texture2D(yTex, vTex).r;
                float u = texture2D(uTex, vTex).r - 0.5;
                float v = texture2D(vTexSampler, vTex).r - 0.5;

                float r = y + 1.402 * v;
                float g = y - 0.344 * u - 0.714 * v;
                float b = y + 1.772 * u;

                gl_FragColor = vec4(r, g, b, 1.0);
            }
        """
    }
}
