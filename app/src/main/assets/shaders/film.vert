// FilmCam vertex shader.
// NOTE: no "#version" line here: Kotlin (ShaderSource.kt) prepends "#version 300 es" so the
// same file works everywhere.
//
// We draw ONE rectangle that covers the whole output (two triangles). For every corner the
// vertex shader passes two coordinates on to the fragment shader:
//   vUv  - where to read in the INPUT texture. Camera frames arrive rotated/cropped/mirrored
//          relative to how they should be shown; CameraX + SurfaceTexture tell us how via a 4x4
//          "texture matrix" (uTexMatrix). For photos we use it to apply the EXIF rotation.
//   vPos - position in the OUTPUT image, 0..1. Grain, vignette and halation are defined in
//          output space so they stay upright and the same size however the sensor is rotated.

in vec4 aPosition;   // corner in clip space (-1..1)
in vec4 aTexCoord;   // matching corner in 0..1 space

uniform mat4 uTexMatrix;

// Film motion (video/preview only; zero for photos). A real projector never holds the film
// perfectly still: the frame drifts by a tiny amount ("gate weave"). We shift where we read
// the image by uWeave and zoom in by uInset so the moved edge never shows.
uniform vec2  uWeave;   // offset in output 0..1 units
uniform float uInset;   // fraction cropped from each side

out vec2 vUv;
out vec2 vPos;

void main() {
    gl_Position = aPosition;
    vec2 uv = aTexCoord.xy * (1.0 - 2.0 * uInset) + uInset + uWeave;
    vUv = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
    vPos = aTexCoord.xy;
}
