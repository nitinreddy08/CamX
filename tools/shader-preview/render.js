// Renders an image through the app's real film.vert / film.frag using headless Chromium
// WebGL2 (which speaks the same GLSL ES 3.00 as the phone). Lets you check shader changes
// and see the look on a computer.
//
// Usage: node tools/shader-preview/render.js <input.jpg> <output.png> [shortSide] [paramsJson]
//   shortSide: scale so the image's short side is this many px (default: original size)
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const [,, inPath, outPath, shortSideArg, paramsArg] = process.argv;
const root = path.resolve(__dirname, '../../app/src/main/assets/shaders');
const vert = '#version 300 es\n' + fs.readFileSync(path.join(root, 'film.vert'), 'utf8');
const frag = '#version 300 es\n#define SAMPLER sampler2D\n' + fs.readFileSync(path.join(root, 'film.frag'), 'utf8');
const defaults = {
  intensity: 1.0, contrast: 0.85, blackLift: 0.06, whiteRoll: 0.95, saturation: 0.78,
  greenDesat: 0.45, greenToOlive: 0.03, shadowTint: [-0.010, 0.000, 0.035],
  midTint: [0.015, -0.010, 0.010], highlightTint: [0.050, 0.020, -0.030],
  grainAmount: 0.09, grainSize: 1.6, softness: 0.30, vignette: 0.15,
};
const params = Object.assign({}, defaults, paramsArg ? JSON.parse(paramsArg) : {});
const imgB64 = fs.readFileSync(inPath).toString('base64');

(async () => {
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
  const page = await browser.newPage();
  page.on('console', m => console.log('[page]', m.text()));
  const result = await page.evaluate(async ({ vert, frag, imgB64, params, shortSide }) => {
    const img = new Image();
    img.src = 'data:image/jpeg;base64,' + imgB64;
    await img.decode();
    let w = img.width, h = img.height;
    if (shortSide) { const s = shortSide / Math.min(w, h); w = Math.round(w * s); h = Math.round(h * s); }
    const canvas = document.createElement('canvas');
    canvas.width = w; canvas.height = h;
    const gl = canvas.getContext('webgl2', { preserveDrawingBuffer: true });
    if (!gl) return { error: 'no webgl2' };
    const sh = (type, src) => { const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s)); return s; };
    let prog;
    try {
      prog = gl.createProgram();
      gl.attachShader(prog, sh(gl.VERTEX_SHADER, vert));
      gl.attachShader(prog, sh(gl.FRAGMENT_SHADER, frag));
      gl.linkProgram(prog);
      if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(prog));
    } catch (e) { return { error: String(e) }; }
    gl.useProgram(prog);
    // Input texture: resample to output size first (like the app's 1080p preview gets a
    // 1080p camera buffer), so softness/blur step is computed from the actual input size.
    const src = document.createElement('canvas'); src.width = w; src.height = h;
    src.getContext('2d').drawImage(img, 0, 0, w, h);
    const tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, src);
    for (const [k, v] of [[gl.TEXTURE_MIN_FILTER, gl.LINEAR], [gl.TEXTURE_MAG_FILTER, gl.LINEAR], [gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE], [gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE]]) gl.texParameteri(gl.TEXTURE_2D, k, v);
    const buf = (data, name) => { const b = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, b);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(data), gl.STATIC_DRAW);
      const loc = gl.getAttribLocation(prog, name); gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0); };
    buf([-1,-1, 1,-1, -1,1, 1,1], 'aPosition');
    buf([0,0, 1,0, 0,1, 1,1], 'aTexCoord');
    const u = n => gl.getUniformLocation(prog, n);
    // Canvas row 0 is the top, GL row 0 the bottom: flip V so the preview PNG is upright.
    gl.uniformMatrix4fv(u('uTexMatrix'), false, [1,0,0,0, 0,-1,0,0, 0,0,1,0, 0,1,0,1]);
    gl.uniform1i(u('uTex'), 0);
    gl.uniform2f(u('uResolution'), w, h);
    const r = 0.75 * Math.min(w, h) / 1080;
    gl.uniform2f(u('uBlurStep'), r / w, r / h);
    gl.uniform1f(u('uTime'), 0); gl.uniform1f(u('uSwapRB'), 0);
    const f = { uIntensity: 'intensity', uContrast: 'contrast', uBlackLift: 'blackLift', uWhiteRoll: 'whiteRoll',
      uSaturation: 'saturation', uGreenDesat: 'greenDesat', uGreenToOlive: 'greenToOlive', uGrainAmount: 'grainAmount',
      uGrainSize: 'grainSize', uSoftness: 'softness', uVignette: 'vignette' };
    for (const [k, v] of Object.entries(f)) gl.uniform1f(u(k), params[v]);
    gl.uniform3fv(u('uShadowTint'), params.shadowTint);
    gl.uniform3fv(u('uMidTint'), params.midTint);
    gl.uniform3fv(u('uHighlightTint'), params.highlightTint);
    gl.viewport(0, 0, w, h);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    const err = gl.getError();
    return { png: canvas.toDataURL('image/png'), w, h, err };
  }, { vert, frag, imgB64, params, shortSide: shortSideArg ? parseInt(shortSideArg) : 0 });
  await browser.close();
  if (result.error) { console.error('SHADER ERROR:', result.error); process.exit(1); }
  fs.writeFileSync(outPath, Buffer.from(result.png.split(',')[1], 'base64'));
  console.log(`wrote ${outPath} ${result.w}x${result.h} glError=${result.err}`);
})();
