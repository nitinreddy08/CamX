// FilmCam vertex shader.
// NOTE: no "#version" line here: Kotlin (ShaderSource.kt) prepends "#version 300 es" so the
// same file works everywhere.
//
// We draw ONE rectangle that covers the whole output (two triangles). For every corner the
// vertex shader passes two coordinates on to the fragment shader:
//   vUv  - where to read in the INPUT texture. Camera frames arrive rotated/cropped/mirrored
//          relative to how they should be shown; CameraX + SurfaceTexture tell us how via a 4x4
//          "texture matrix" (uTexMatrix). For photos we use it to apply the EXIF rotation.
//   vPos - position in the OUTPUT image, 0..1. Grain and vignette are defined in output space
//          so they stay upright and the same size no matter how the sensor is rotated.

in vec4 aPosition;   // corner in clip space (-1..1)
in vec4 aTexCoord;   // matching corner in 0..1 space

uniform mat4 uTexMatrix;

out vec2 vUv;
out vec2 vPos;

void main() {
    gl_Position = aPosition;
    vUv = (uTexMatrix * aTexCoord).xy;
    vPos = aTexCoord.xy;
}
