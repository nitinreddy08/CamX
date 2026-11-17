package com.nitin.filmcam.filter

import android.content.Context

/**
 * Loads the shader text from assets/shaders and adds the small header that differs between the
 * camera path and the photo path. Everything else is the exact same source, so the preview,
 * the recorded video and the saved photo can never drift apart.
 */
object ShaderSource {
    enum class Input { CAMERA_EXTERNAL, BITMAP_2D }

    fun vertex(context: Context): String =
        "#version 300 es\n" + read(context, "shaders/film.vert")

    fun fragment(context: Context, input: Input): String =
        fragmentHeader(input) + read(context, "shaders/film.frag")

    /**
     * Camera frames live in a special "external" texture (samplerExternalOES) because they are
     * YUV buffers straight from the camera hardware; the GPU converts to RGB while sampling.
     * Bitmaps are ordinary RGBA textures (sampler2D).
     */
    fun fragmentHeader(input: Input): String = when (input) {
        Input.CAMERA_EXTERNAL ->
            "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\n#define SAMPLER samplerExternalOES\n"
        Input.BITMAP_2D ->
            "#version 300 es\n#define SAMPLER sampler2D\n"
    }

    private fun read(context: Context, path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }
}
