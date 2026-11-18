package com.nitin.filmcam.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Small OpenGL helpers: compile/link shaders, make textures, check errors. */
object GlUtil {
    private const val TAG = "GlUtil"

    fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        // Shader objects are no longer needed once linked into the program.
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw IllegalStateException("Shader link failed: $log")
        }
        return program
    }

    private fun compile(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            Log.e(TAG, "Shader compile failed:\n$log")
            throw IllegalStateException("Shader compile failed: $log")
        }
        return shader
    }

    /** Texture that a SurfaceTexture (camera frames) writes into. */
    fun createExternalTexture(): Int {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        setLinearClamp(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        return tex[0]
    }

    fun create2DTexture(): Int {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        setLinearClamp(GLES30.GL_TEXTURE_2D)
        return tex[0]
    }

    /** Linear filtering = smooth sub-pixel reads (needed for the softness taps). */
    private fun setLinearClamp(target: Int) {
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    /** Empty RGBA8 texture usable as a render target. */
    fun createRenderTexture(width: Int, height: Int): Int {
        val tex = create2DTexture()
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        return tex
    }

    /** 1x1 texture of one colour (used as "no halation" = black). */
    fun createSolidTexture(r: Int, g: Int, b: Int): Int {
        val tex = create2DTexture()
        val px = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        px.put(r.toByte()).put(g.toByte()).put(b.toByte()).put(255.toByte()).position(0)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1, 1, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px)
        return tex
    }

    // Full-screen quad as a triangle strip: bottom-left, bottom-right, top-left, top-right.
    private val QUAD_POS: FloatBuffer = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val QUAD_UV: FloatBuffer = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    /** Draws the full-viewport rectangle with [program]'s aPosition / aTexCoord attributes. */
    fun drawQuad(program: Int) {
        val aPosition = GLES30.glGetAttribLocation(program, "aPosition")
        val aTexCoord = GLES30.glGetAttribLocation(program, "aTexCoord")
        GLES30.glEnableVertexAttribArray(aPosition)
        GLES30.glVertexAttribPointer(aPosition, 2, GLES30.GL_FLOAT, false, 0, QUAD_POS)
        GLES30.glEnableVertexAttribArray(aTexCoord)
        GLES30.glVertexAttribPointer(aTexCoord, 2, GLES30.GL_FLOAT, false, 0, QUAD_UV)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(aPosition)
        GLES30.glDisableVertexAttribArray(aTexCoord)
    }

    private fun floatBuffer(vararg v: Float): FloatBuffer =
        ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(v); position(0)
        }

    fun deleteTexture(id: Int) {
        GLES30.glDeleteTextures(1, intArrayOf(id), 0)
    }

    fun checkError(op: String) {
        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR) Log.w(TAG, "$op: glError 0x${Integer.toHexString(err)}")
    }
}
