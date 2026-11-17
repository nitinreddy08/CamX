package com.nitin.filmcam.gl

/**
 * Turns an EXIF orientation into a texture matrix, so the GPU rotates/flips the photo while
 * filtering instead of the CPU making a rotated copy first (which would need another
 * 50-200 MB bitmap for a big photo).
 *
 * Both spaces use (u, v) in 0..1 with v = 0 at the TOP row of the image. For each output
 * (upright) pixel we compute which input (sensor-oriented) pixel to read.
 * Pure Kotlin (no Android types) so it can be unit tested on the JVM.
 */
object ExifTransform {
    // Values of ExifInterface.ORIENTATION_* (copied so this file has no Android dependency).
    const val NORMAL = 1
    const val FLIP_HORIZONTAL = 2
    const val ROTATE_180 = 3
    const val FLIP_VERTICAL = 4
    const val TRANSPOSE = 5
    const val ROTATE_90 = 6
    const val TRANSVERSE = 7
    const val ROTATE_270 = 8

    /** True if the upright image is the input with width and height swapped. */
    fun swapsDimensions(orientation: Int) = orientation in setOf(TRANSPOSE, ROTATE_90, TRANSVERSE, ROTATE_270)

    /**
     * Column-major 4x4 matrix M with  inputUV = M * (u, v, 0, 1).
     * Each case is "inU = a*u + b*v + c,  inV = d*u + e*v + f".
     */
    fun texMatrix(orientation: Int): FloatArray {
        val (a, b, c, d, e, f) = when (orientation) {
            FLIP_HORIZONTAL -> M(-1f, 0f, 1f, 0f, 1f, 0f)  // (1-u, v)
            ROTATE_180 -> M(-1f, 0f, 1f, 0f, -1f, 1f)       // (1-u, 1-v)
            FLIP_VERTICAL -> M(1f, 0f, 0f, 0f, -1f, 1f)     // (u, 1-v)
            TRANSPOSE -> M(0f, 1f, 0f, 1f, 0f, 0f)          // (v, u)
            ROTATE_90 -> M(0f, 1f, 0f, -1f, 0f, 1f)         // (v, 1-u): 90 deg clockwise
            TRANSVERSE -> M(0f, -1f, 1f, -1f, 0f, 1f)       // (1-v, 1-u)
            ROTATE_270 -> M(0f, -1f, 1f, 1f, 0f, 0f)        // (1-v, u): 90 deg counter-clockwise
            else -> M(1f, 0f, 0f, 0f, 1f, 0f)               // (u, v)
        }
        return floatArrayOf(
            a, d, 0f, 0f,   // column 0: coefficients of u
            b, e, 0f, 0f,   // column 1: coefficients of v
            0f, 0f, 1f, 0f, // column 2
            c, f, 0f, 1f,   // column 3: constant offset
        )
    }

    /** Applies [m] to (u, v); handy for tests. */
    fun apply(m: FloatArray, u: Float, v: Float): Pair<Float, Float> =
        Pair(m[0] * u + m[4] * v + m[12], m[1] * u + m[5] * v + m[13])

    private data class M(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float)
}
