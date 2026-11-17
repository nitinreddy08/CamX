package com.nitin.filmcam.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.util.Log

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

    fun deleteTexture(id: Int) {
        GLES30.glDeleteTextures(1, intArrayOf(id), 0)
    }

    fun checkError(op: String) {
        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR) Log.w(TAG, "$op: glError 0x${Integer.toHexString(err)}")
    }
}
