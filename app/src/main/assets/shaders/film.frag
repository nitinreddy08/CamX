// FilmCam fragment shader: the whole film look in ONE pass (one texture read per tap,
// no intermediate buffers), which is what keeps the preview at 30+ fps.
//
// NOTE: no "#version"/sampler line here. ShaderSource.kt prepends a small header:
//   camera (preview/video):  #version 300 es
//                            #extension GL_OES_EGL_image_external_essl3 : require
//                            #define SAMPLER samplerExternalOES
//   photo / debug screen:    #version 300 es
//                            #define SAMPLER sampler2D
// so preview, video and photos all run exactly this code.
//
// Pipeline per pixel:  soften (blur original) -> grade -> grain -> vignette -> blend by intensity
// (Spec order is grade -> soften. Grading is per-pixel, so blurring first and grading once is
//  visually the same but 9x cheaper than grading all 9 blur taps.)
//
// Colours are display-encoded (sRGB-ish) 0..1 values. We grade directly in that space: it's
// how photo editors' sliders behave too, and the look doesn't need linear light.

precision highp float;
precision highp int;

in vec2 vUv;    // input texture coordinate (after rotation/crop transform)
in vec2 vPos;   // output position 0..1
out vec4 fragColor;

uniform SAMPLER uTex;        // camera frame or photo

uniform vec2  uResolution;   // OUTPUT size in pixels
uniform vec2  uBlurStep;     // softness tap offset, in input-texture UV units (see FilmShader.kt)
uniform float uTime;         // seconds; drives animated grain. 0 for photos = static grain.
uniform float uSwapRB;       // 1.0 = write BGR (photo readback trick, see PhotoFilterRenderer)

uniform float uIntensity;    // 0 = original, 1 = full look
uniform float uContrast;     // < 1 = flatter
uniform float uBlackLift;    // output level of pure black
uniform float uWhiteRoll;    // output level of pure white
uniform float uSaturation;   // global saturation
uniform float uGreenDesat;   // extra desaturation for greens
uniform float uGreenToOlive; // push greens toward olive (adds red)
uniform vec3  uShadowTint;   // added in shadows   (cool)
uniform vec3  uMidTint;      // added in midtones  (mauve)
uniform vec3  uHighlightTint;// added in highlights(warm)
uniform float uGrainAmount;  // grain strength
uniform float uGrainSize;    // grain cell size in px, measured on a 1080p-tall frame
uniform float uSoftness;     // 0..1 blend toward a slightly blurred image
uniform float uVignette;     // 0..1 corner darkening

// Rec.709 luma weights: how bright each channel looks to the eye.
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

// ---------------------------------------------------------------------------------------------
// 1. Colour grade
// ---------------------------------------------------------------------------------------------
vec3 grade(vec3 c) {
    // 1. Low contrast: scale distance from a mid-grey pivot. 0.45 (slightly below 0.5) keeps
    //    midtones from getting brighter when contrast is reduced.
    c = (c - 0.45) * uContrast + 0.45;

    // 2. Matte print: remap 0..1 to blackLift..whiteRoll. Pure black becomes a soft grey and
    //    pure white never clips -> the faded "film print" feel.
    c = mix(vec3(uBlackLift), vec3(uWhiteRoll), clamp(c, 0.0, 1.0));

    // 3. Global desaturation: blend toward the grey of the same brightness.
    float l = dot(c, LUMA);
    c = mix(vec3(l), c, uSaturation);

    // 4. Greens -> dusty olive. "greenness" is how much G exceeds both R and B (0 for skin,
    //    sky, greys). Those pixels get extra desaturation and a little red (green+red = olive).
    float greenness = smoothstep(0.0, 0.15, c.g - max(c.r, c.b));
    c = mix(c, mix(vec3(l), c, 0.6), greenness * uGreenDesat);
    c.r += greenness * uGreenToOlive;

    // 5. Split toning: three brightness bands, each nudged toward its own tint.
    //    The weights overlap smoothly so there are no visible bands.
    float lum = dot(c, LUMA);
    float wShadow = 1.0 - smoothstep(0.0, 0.5, lum);
    float wHigh   = smoothstep(0.5, 1.0, lum);
    float wMid    = clamp(1.0 - wShadow - wHigh, 0.0, 1.0);
    c += uShadowTint * wShadow + uMidTint * wMid + uHighlightTint * wHigh;

    return clamp(c, 0.0, 1.0);
}

// ---------------------------------------------------------------------------------------------
// 2. Softness: a 3x3 "tent" blur (weights 1-2-1) mixed back over the sharp image.
//    uBlurStep is ~0.75 px on a 1080p frame and scales with resolution, so a 12-50 MP photo is
//    softened by the same *relative* amount as the preview.
// ---------------------------------------------------------------------------------------------
vec3 soften(vec3 center) {
    if (uSoftness <= 0.001) return center;
    vec2 s = uBlurStep;
    vec3 cross = texture(uTex, vUv + vec2(s.x, 0.0)).rgb + texture(uTex, vUv - vec2(s.x, 0.0)).rgb
               + texture(uTex, vUv + vec2(0.0, s.y)).rgb + texture(uTex, vUv - vec2(0.0, s.y)).rgb;
    vec3 diag  = texture(uTex, vUv + s).rgb + texture(uTex, vUv - s).rgb
               + texture(uTex, vUv + vec2(s.x, -s.y)).rgb + texture(uTex, vUv + vec2(-s.x, s.y)).rgb;
    vec3 blurred = (center * 4.0 + cross * 2.0 + diag) / 16.0;
    return mix(center, blurred, uSoftness);
}

// ---------------------------------------------------------------------------------------------
// 3. Film grain
// ---------------------------------------------------------------------------------------------
// Integer hash ("pcg3d", Jarzynski & Olano 2020). Integer maths gives the same random numbers
// on every GPU, unlike the classic fract(sin(x)*43758.5) trick, which falls apart (visible
// patterns) on mobile GPUs with big inputs. Returns three independent 32-bit values.
uvec3 pcg3d(uvec3 v) {
    v = v * 1664525u + 1013904223u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    v ^= v >> 16u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    return v;
}

// One random value per grain cell, roughly Gaussian: average of 3 uniform randoms (central
// limit theorem), centred on 0. Range -0.5..0.5, standard deviation ~0.17.
float cellRandom(ivec2 cell, uint seed) {
    uvec3 h = pcg3d(uvec3(uvec2(cell), seed));
    vec3 u = vec3(h >> 8u) * (1.0 / 16777216.0); // top 24 bits -> 0..1
    return (u.x + u.y + u.z) / 3.0 - 0.5;
}

// Value noise: random values on a grid, smoothly interpolated in between. The smooth
// interpolation is what gives grain soft edges instead of hard square "TV static" pixels.
float valueNoise(vec2 p, uint seed) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 w = f * f * (3.0 - 2.0 * f);          // smoothstep weights
    ivec2 c = ivec2(i);
    float a = cellRandom(c,               seed);
    float b = cellRandom(c + ivec2(1, 0), seed);
    float d = cellRandom(c + ivec2(0, 1), seed);
    float e = cellRandom(c + ivec2(1, 1), seed);
    return mix(mix(a, b, w.x), mix(d, e, w.x), w.y);
}

float filmGrain(float lum) {
    // Resolution independence: express the pixel position as if the frame's SHORT side were
    // 1080 px. A 1080x1920 preview and a 3000x4000 photo then get grain cells that cover the
    // same fraction of the frame. (Short side rather than height so portrait and landscape
    // frames match too.)
    float shortSide = min(uResolution.x, uResolution.y);
    vec2 px = vPos * uResolution * (1080.0 / shortSide);

    // New random pattern every video frame (60 per second of uTime); fixed for photos.
    uint seed = uint(uTime * 60.0);

    // Two octaves: fine grain at uGrainSize plus coarser clumps at 2x. Real film grain
    // clusters, and the coarse octave is what makes it read as "film" rather than noise.
    float n = 0.7 * valueNoise(px / uGrainSize, seed)
            + 0.3 * valueNoise(px / (uGrainSize * 2.0) + 17.0, seed ^ 0x9E3779B9u);
    // Interpolation shrinks the spread; x2.1 brings it back to ~ the raw cell variance so
    // uGrainAmount means the same thing as in the spec's simple formula.
    n *= 2.1;

    // Midtone weighting: 1.0 at mid-grey, falling to 0 at pure black/white, so grain is
    // strongest in midtones but never fully disappears (0.4 floor).
    float mask = 1.0 - pow(abs(lum * 2.0 - 1.0), 2.0);
    return n * uGrainAmount * (0.4 + 0.6 * mask);
}

// ---------------------------------------------------------------------------------------------
// 4. Vignette: distance from centre on an aspect-corrected (round, not oval) scale where
//    0 = centre and 1 = corner; darkening starts at 40% of the way out.
// ---------------------------------------------------------------------------------------------
float vignette() {
    vec2 aspect = vec2(uResolution.x / uResolution.y, 1.0);
    float r = length((vPos - 0.5) * aspect) / length(0.5 * aspect);
    return 1.0 - uVignette * smoothstep(0.4, 1.0, r);
}

void main() {
    vec3 original = texture(uTex, vUv).rgb;

    vec3 c = soften(original);
    c = grade(c);

    // Same grain value added to R, G and B = monochrome (luminance) grain, like real
    // black-and-white silver grain in a colour negative's structure.
    float lum = dot(c, LUMA);
    c += vec3(filmGrain(lum));

    c *= vignette();
    c = clamp(c, 0.0, 1.0);

    vec3 outColor = mix(original, c, uIntensity);
    fragColor = vec4(uSwapRB > 0.5 ? outColor.bgr : outColor, 1.0);
}
