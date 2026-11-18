// FilmCam fragment shader (engine v2): the whole film look in ONE pass.
//
// NOTE: no "#version"/sampler line here. ShaderSource.kt prepends a small header:
//   camera (preview/video):  #version 300 es
//                            #extension GL_OES_EGL_image_external_essl3 : require
//                            #define SAMPLER samplerExternalOES
//   photo / debug screen:    #version 300 es
//                            #define SAMPLER sampler2D
// so preview, video and photos all run exactly this code.
//
// Pipeline per pixel
//   1. soften            blur the camera image a touch (film resolves less than a phone sensor)
//   2. linear-light stage  undo the screen encoding so we work with real light amounts:
//                          exposure, white balance (warmth), halation glow
//   3. tone curve        how film turns light into density: soft toe, straight middle, shoulder
//   4. colour            dye cross-talk, pastel highlights, per-colour controls, colour density,
//                        split toning
//   5. grain             density-dependent, resolution-independent, animated in video
//   6. vignette + blend  lens fall-off; Intensity blends the colour/tone look (not the grain)
//
// Every v2 control has a NEUTRAL value (0, or 1 for band saturation) that switches it off, and
// the "Field" preset uses only neutral values, so Field renders exactly like engine v1.

precision highp float;
precision highp int;

in vec2 vUv;    // input texture coordinate (after rotation/crop transform)
in vec2 vPos;   // output position 0..1
out vec4 fragColor;

uniform SAMPLER   uTex;      // camera frame or photo
uniform sampler2D uHalo;     // blurred highlights for halation (HalationPass), sqrt-encoded

uniform vec2  uResolution;   // OUTPUT size in pixels
uniform vec2  uBlurStep;     // softness tap offset, in input-texture UV units (see FilmShader.kt)
uniform float uTime;         // seconds; drives animated grain. 0 for photos = static grain.
uniform float uSwapRB;       // 1.0 = write BGR (photo readback trick, see PhotoFilterRenderer)
uniform float uBypass;       // 1.0 = show the untouched camera image (hold-to-compare)

// --- engine v1 controls ---
uniform float uIntensity;    // 0 = original colours, 1 = full look (grain is separate)
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

// --- engine v2 controls (neutral = off) ---
uniform float uLinearStage;  // 1.0 when any linear-light control is active (set by Kotlin)
uniform float uExposure;     // stops (+1 = twice the light)
uniform float uWarmth;       // -1 cool .. +1 warm (colour temperature)
uniform float uFlicker;      // film motion: tiny exposure wobble per frame (video only)
uniform float uHalation;     // red-orange glow around bright light
uniform float uFilmCurve;    // 0 = v1 straight contrast, 1 = film toe/shoulder curve
uniform float uDyeMix;       // dye cross-talk (rich-but-muted film colour)
uniform float uPastel;       // highlights (and deep shadows) lose saturation, like film
uniform float uColorDensity; // strong colours get deeper/darker instead of brighter
uniform float uHueBands;     // 1.0 when any per-colour control is not neutral
uniform vec3  uHueAdj[8];    // per colour band: (saturation x, brightness -1..1, hue shift -1..1)
uniform float uGrainDensity; // 0 = v1 midtone mask, 1 = film density model
uniform float uGrainColor;   // 0 = monochrome grain, 1 = noticeable per-dye-layer colour grain

// Rec.709 luma weights: how bright each channel looks to the eye.
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

// ---------------------------------------------------------------------------------------------
// sRGB <-> linear light. A phone image stores values "gamma encoded" (roughly light^(1/2.2)) so
// that 8 bits look smooth to the eye. Physical things (exposure, colour temperature, light
// bouncing inside the film) add and multiply LIGHT, so we decode first and re-encode after.
// ---------------------------------------------------------------------------------------------
vec3 toLinear(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
}
vec3 toDisplay(vec3 l) {
    l = max(l, 0.0);
    return mix(l * 12.92, 1.055 * pow(l, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, l));
}

// ---------------------------------------------------------------------------------------------
// 1. Softness: a 3x3 "tent" blur (weights 1-2-1) mixed back over the sharp image.
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
// 2. Linear-light stage
// ---------------------------------------------------------------------------------------------
// Warmth: film is balanced for daylight and has no auto white balance; the phone neutralises
// every scene. This re-tints the light like a colour-temperature shift (more red, less blue),
// normalised so overall brightness stays the same.
vec3 warmGain() {
    vec3 g = vec3(1.0 + 0.10 * uWarmth, 1.0 + 0.015 * uWarmth, 1.0 - 0.13 * uWarmth);
    return g / dot(g, LUMA);
}

// Halation: bright light passes through the emulsion, reflects off the back of the film base
// and comes back through the RED-sensitive layer (the one nearest the base), exposing it again
// in a ring around the light. Result: an orange-red glow around windows, lamps and sun edges.
// HalationPass already isolated + blurred the bright parts at low resolution; we add them back
// here as extra light, mostly red, with a hint of neutral bloom.
vec3 halation() {
    vec3 h = texture(uHalo, vPos).rgb;
    h *= h; // stored sqrt-encoded for 8-bit precision
    return uHalation * (h * vec3(1.0, 0.30, 0.08) * 1.8 + h * 0.20);
}

// ---------------------------------------------------------------------------------------------
// 3. Tone
// ---------------------------------------------------------------------------------------------
// v1 tone: straight contrast around a pivot, then remap 0..1 -> blackLift..whiteRoll (matte).
vec3 legacyTone(vec3 c) {
    c = (c - 0.45) * uContrast + 0.45;
    return mix(vec3(uBlackLift), vec3(uWhiteRoll), clamp(c, 0.0, 1.0));
}

// Film characteristic ("H&D") curve. Film's response to light is an S: a soft TOE where the
// darkest tones gently merge (instead of a hard black), a straight middle, and a soft SHOULDER
// where highlights bend over and approach paper white instead of clipping. We build that S on
// each channel separately (like three dye layers), around a mid-grey pivot:
//   below the pivot  y = p * (d/p)^a           (toe)
//   above the pivot  y = 1 - (1-p)*((1-d)/(1-p))^a   (shoulder)
// a > 1 flattens both ends (soft toe + shoulder) and sets the midtone slope. Then the print:
// remap 0..1 to blackLift..whiteRoll (faded black, paper white). Because the strongest channel
// of a bright colour reaches the flat shoulder first, highlights turn pastel naturally, and deep
// shadows lose colour the same way, as on film.
vec3 filmicCurve(vec3 d) {
    const float P = 0.45;                              // mid-grey pivot (display value)
    float a = max(0.5, 1.35 * uContrast);              // contrast 0.85 -> a = 1.15
    d = clamp(d, 0.0, 1.0);
    vec3 toe = P * pow(d / P, vec3(a));
    vec3 shoulder = 1.0 - (1.0 - P) * pow((1.0 - d) / (1.0 - P), vec3(a));
    vec3 y = mix(toe, shoulder, step(P, d));
    return mix(vec3(uBlackLift), vec3(uWhiteRoll), y);
}

// Highlight knee for the linear stage: values above 0.8 are compressed smoothly toward 1.0 so
// raising Exposure (or a halation glow) rolls into white like over-exposed film instead of
// clipping in a hard line. Values below 0.8 are untouched.
vec3 softClip(vec3 l) {
    const float K = 0.8;
    vec3 over = max(l - K, 0.0);
    return min(l, vec3(K)) + over / (1.0 + over / (1.0 - K));
}

// ---------------------------------------------------------------------------------------------
// 4. Colour
// ---------------------------------------------------------------------------------------------
// Dye cross-talk: real dyes are impure, so each layer also absorbs a little of its neighbours'
// light. Rows sum to 1, so greys stay grey; colours become richer-but-softer and hues shift
// slightly toward their neighbours (that typical film "colour cohesion").
const mat3 DYE = mat3(
    0.90, 0.05, 0.03,   // column 0: where red input goes   (R', G', B')
    0.07, 0.88, 0.09,   // column 1: green input
    0.03, 0.07, 0.88    // column 2: blue input
);

vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + 1e-10)), d / (q.x + 1e-10), q.x);
}
vec3 hsv2rgb(vec3 c) {
    vec3 p = abs(fract(c.xxx + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}

// Per-colour controls, like a colourist's "HSL" panel. 8 bands centred on these hues (degrees):
// Red 0, Orange/skin 30, Yellow 60, Green 120, Teal 180, Blue 225, Purple 270, Magenta 315.
// A pixel's hue falls between two band centres; its adjustment is a smooth blend of the two,
// so there are no hard edges. Greys (no colour) are never touched.
const float BAND_HUE[8] = float[8](0.0, 30.0, 60.0, 120.0, 180.0, 225.0, 270.0, 315.0);

vec3 hueBands(vec3 c) {
    vec3 hsv = rgb2hsv(c);
    float h = hsv.x * 360.0;
    int i0 = 7;
    int i1 = 0;
    float t = 0.0;
    for (int i = 0; i < 8; i++) {
        float a = BAND_HUE[i];
        float b = (i == 7) ? 360.0 : BAND_HUE[i + 1];
        if (h >= a && h < b) {
            i0 = i;
            i1 = (i + 1) % 8;
            t = (h - a) / (b - a);
        }
    }
    vec3 adj = mix(uHueAdj[i0], uHueAdj[i1], smoothstep(0.0, 1.0, t));
    float w = smoothstep(0.02, 0.12, hsv.y * hsv.z);              // how colourful the pixel is
    hsv.x = fract(hsv.x + adj.z * (30.0 / 360.0) * w);            // hue shift, max +/-30 deg
    hsv.y = clamp(hsv.y * mix(1.0, adj.x, w), 0.0, 1.0);          // saturation
    hsv.z *= 1.0 + 0.5 * adj.y * w;                               // brightness +/-50%
    return hsv2rgb(hsv);
}

vec3 grade(vec3 c) {
    // Tone
    if (uFilmCurve > 0.0) c = mix(legacyTone(c), filmicCurve(c), uFilmCurve);
    else c = legacyTone(c);

    if (uDyeMix > 0.0) c = mix(c, DYE * c, uDyeMix);

    // Global saturation, optionally rolling off in highlights and deep shadows ("pastel"):
    // film dyes thin out near paper white and merge near print black.
    float l = dot(c, LUMA);
    float sat = uSaturation;
    if (uPastel > 0.0) {
        float roll = smoothstep(0.55, 0.95, l) + 0.5 * (1.0 - smoothstep(0.0, 0.2, l));
        sat *= 1.0 - 0.75 * uPastel * clamp(roll, 0.0, 1.0);
    }
    c = mix(vec3(l), c, sat);

    // v1 greens -> dusty olive. "greenness" is how much G exceeds both R and B.
    float greenness = smoothstep(0.0, 0.15, c.g - max(c.r, c.b));
    c = mix(c, mix(vec3(l), c, 0.6), greenness * uGreenDesat);
    c.r += greenness * uGreenToOlive;

    if (uHueBands > 0.5) c = hueBands(clamp(c, 0.0, 1.0));

    // Colour density: on film a strongly coloured thing is MORE dye = darker and deeper; on a
    // phone it gets brighter and neon. Darken in proportion to how colourful the pixel is.
    if (uColorDensity > 0.0) {
        float chroma = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
        c *= 1.0 - 0.45 * uColorDensity * chroma;
    }

    // Split toning: three brightness bands, each nudged toward its own tint.
    float lum = dot(c, LUMA);
    float wShadow = 1.0 - smoothstep(0.0, 0.5, lum);
    float wHigh   = smoothstep(0.5, 1.0, lum);
    float wMid    = clamp(1.0 - wShadow - wHigh, 0.0, 1.0);
    c += uShadowTint * wShadow + uMidTint * wMid + uHighlightTint * wHigh;

    return clamp(c, 0.0, 1.0);
}

// ---------------------------------------------------------------------------------------------
// 5. Film grain
// ---------------------------------------------------------------------------------------------
// Integer hash ("pcg3d", Jarzynski & Olano 2020): same random numbers on every GPU, no patterns.
uvec3 pcg3d(uvec3 v) {
    v = v * 1664525u + 1013904223u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    v ^= v >> 16u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    return v;
}

// One roughly Gaussian random value per grain cell (average of 3 uniforms), range -0.5..0.5.
float cellRandom(ivec2 cell, uint seed) {
    uvec3 h = pcg3d(uvec3(uvec2(cell), seed));
    vec3 u = vec3(h >> 8u) * (1.0 / 16777216.0);
    return (u.x + u.y + u.z) / 3.0 - 0.5;
}

// Value noise: random values on a grid, smoothly interpolated -> soft-edged grain.
float valueNoise(vec2 p, uint seed) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 w = f * f * (3.0 - 2.0 * f);
    ivec2 c = ivec2(i);
    float a = cellRandom(c,               seed);
    float b = cellRandom(c + ivec2(1, 0), seed);
    float d = cellRandom(c + ivec2(0, 1), seed);
    float e = cellRandom(c + ivec2(1, 1), seed);
    return mix(mix(a, b, w.x), mix(d, e, w.x), w.y);
}

vec3 filmGrain(float lum) {
    // Resolution independence: positions as if the frame's SHORT side were 1080 px.
    float shortSide = min(uResolution.x, uResolution.y);
    vec2 px = vPos * uResolution * (1080.0 / shortSide);
    uint seed = uint(uTime * 60.0);   // new pattern every video frame; fixed for photos

    // Two octaves: fine grain plus coarser clumps (real grain clusters).
    vec2 p = px / uGrainSize;
    float n = 0.7 * valueNoise(p, seed)
            + 0.3 * valueNoise(px / (uGrainSize * 2.0) + 17.0, seed ^ 0x9E3779B9u);
    n *= 2.1;

    // Where grain shows.
    //  v1: midtone mask (strongest at mid-grey).
    //  film density model: in a print, grain is most visible in shadows and midtones, fades
    //  in bright highlights (little dye there) and in the very deepest black (dye saturates).
    float v1 = 0.4 + 0.6 * (1.0 - pow(abs(lum * 2.0 - 1.0), 2.0));
    float dens = smoothstep(0.0, 0.16, lum) * (1.0 - 0.7 * smoothstep(0.45, 0.97, lum));
    float weight = mix(v1, 0.3 + 0.8 * dens, uGrainDensity);

    vec3 g = vec3(n);
    // Optional colour grain: each dye layer has its own grain. Keep luminance from the mono
    // grain and add only the colour difference, scaled by uGrainColor.
    if (uGrainColor > 0.0) {
        vec3 nc = vec3(valueNoise(p + 31.0, seed ^ 0x85EBCA6Bu),
                       valueNoise(p + 57.0, seed ^ 0xC2B2AE35u),
                       valueNoise(p + 91.0, seed ^ 0x27D4EB2Fu)) * 2.1;
        g += uGrainColor * 0.8 * (nc - vec3(dot(nc, LUMA)));
    }
    return g * uGrainAmount * weight;
}

// ---------------------------------------------------------------------------------------------
// 6. Vignette: lenses deliver less light to the corners (light arrives at an angle; the
// "cos^4 law"). Smooth radial fall-off on an aspect-corrected (round) scale, 0 centre..1 corner.
// ---------------------------------------------------------------------------------------------
float vignette() {
    vec2 aspect = vec2(uResolution.x / uResolution.y, 1.0);
    float r = length((vPos - 0.5) * aspect) / length(0.5 * aspect);
    return 1.0 - uVignette * smoothstep(0.4, 1.0, r);
}

void main() {
    vec3 original = texture(uTex, vUv).rgb;
    if (uBypass > 0.5) {
        fragColor = vec4(uSwapRB > 0.5 ? original.bgr : original, 1.0);
        return;
    }

    vec3 c = soften(original);

    if (uLinearStage > 0.5) {
        vec3 lin = toLinear(c);
        lin *= exp2(uExposure) * (1.0 + uFlicker);
        lin *= warmGain();
        if (uHalation > 0.0) lin += halation();
        c = toDisplay(softClip(lin));
    }

    c = grade(c);

    // Grain is measured on the graded image and added AFTER the Intensity blend, so lowering
    // Intensity softens the colour look but keeps the grain you chose.
    vec3 grain = filmGrain(dot(c, LUMA));
    vec3 look = mix(original, c, uIntensity);
    vec3 outColor = clamp((look + grain) * mix(1.0, vignette(), uIntensity), 0.0, 1.0);

    fragColor = vec4(uSwapRB > 0.5 ? outColor.bgr : outColor, 1.0);
}
