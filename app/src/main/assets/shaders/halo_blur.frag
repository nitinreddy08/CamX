// Halation, passes 2 and 3: separable Gaussian blur (run once horizontally, once vertically)
// of the small bright-parts buffer. Blurring in two 1-D passes costs 9+9 reads per pixel
// instead of 81 for the same 2-D result. Radius ~1% of the frame's short side, like the glow
// ring on real film. ShaderSource.kt prepends "#version 300 es".

precision highp float;

in vec2 vUv;
in vec2 vPos;
out vec4 fragColor;

uniform sampler2D uSrc;
uniform vec2 uDir;   // one tap step in UV units, along x or y

// Gaussian weights exp(-i^2 / 8) for i = 0..4, normalised (sum over -4..4 = 1).
const float W[5] = float[5](0.2042, 0.1802, 0.1238, 0.0663, 0.0276);

void main() {
    vec3 c = texture(uSrc, vUv).rgb;
    vec3 acc = c * c * W[0];
    for (int i = 1; i < 5; i++) {
        vec3 a = texture(uSrc, vUv + uDir * float(i)).rgb;
        vec3 b = texture(uSrc, vUv - uDir * float(i)).rgb;
        acc += (a * a + b * b) * W[i];   // decode sqrt -> linear before averaging
    }
    fragColor = vec4(sqrt(acc), 1.0);
}
