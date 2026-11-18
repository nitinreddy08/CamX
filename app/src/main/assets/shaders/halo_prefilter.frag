// Halation, pass 1 of 3: shrink the frame to a small buffer (short side ~270 px) and keep only
// the bright parts, in linear light. Output is in OUTPUT orientation (same vertex shader and
// texture matrix as the main pass), so the main pass can read it at vPos.
//
// ShaderSource.kt prepends "#version 300 es" and the SAMPLER define (camera or photo).

precision highp float;

in vec2 vUv;
in vec2 vPos;
out vec4 fragColor;

uniform SAMPLER uTex;
uniform vec2 uTapStep;     // spacing of the 4x4 sample grid, in input UV units

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

vec3 toLinear(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
}

void main() {
    // 16 bilinear reads spread over one output texel's footprint = a smooth box downsample
    // (photos also have mipmaps, so very large inputs are pre-averaged by the GPU).
    vec3 acc = vec3(0.0);
    for (int y = 0; y < 4; y++) {
        for (int x = 0; x < 4; x++) {
            vec2 o = (vec2(float(x), float(y)) - 1.5) * uTapStep;
            acc += toLinear(texture(uTex, vUv + o).rgb);
        }
    }
    acc /= 16.0;

    // Only light near clipping causes visible halation. Soft threshold on the brightest channel
    // so bright coloured lights (a red lamp) count too.
    float peak = max(acc.r, max(acc.g, acc.b));
    vec3 bright = acc * smoothstep(0.70, 1.0, peak);

    fragColor = vec4(sqrt(bright), 1.0); // sqrt-encode: 8-bit buffers keep dim glow smooth
}
