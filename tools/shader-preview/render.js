// Renders images through the app's real shaders (film.vert, film.frag, halo_prefilter.frag,
// halo_blur.frag) using headless Chromium WebGL2, which speaks the same GLSL ES 3.00 as the
// phone. Mirrors the app's pipeline: halation passes at short side 270 px, then the main pass.
//
// Usage:
//   node tools/shader-preview/render.js <input.jpg> <output.png> [shortSide] [paramsJson|@file.json]
//   node tools/shader-preview/render.js --jobs jobs.json     (array of {input, output, shortSide, params, shaderDir})
//
// params use the same names as FilmParams.kt (missing keys = Field defaults). Requires the
// playwright package (npm i playwright) and its Chromium.
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const DEFAULTS = {
  intensity: 1.0, contrast: 0.85, blackLift: 0.06, whiteRoll: 0.95, saturation: 0.78,
  greenDesat: 0.45, greenToOlive: 0.03,
  shadowTint: { r: -0.010, g: 0.000, b: 0.035 }, midTint: { r: 0.015, g: -0.010, b: 0.010 },
  highlightTint: { r: 0.050, g: 0.020, b: -0.030 },
  grainAmount: 0.09, grainSize: 1.6, softness: 0.30, vignette: 0.15,
  exposure: 0, warmth: 0, filmCurve: 0, dyeMix: 0, pastel: 0, colorDensity: 0,
  hues: Array.from({ length: 8 }, () => ({ saturation: 1, brightness: 0, shift: 0 })),
  halation: 0, grainDensity: 0, grainColor: 0, filmMotion: 0,
};
const HALO_SHORT = 270;

function loadParams(arg) {
  if (!arg) return {};
  if (typeof arg === 'object') return arg;
  if (arg.startsWith('@')) return JSON.parse(fs.readFileSync(arg.slice(1), 'utf8'));
  return JSON.parse(arg);
}

function shaders(dir) {
  const r = f => fs.readFileSync(path.join(dir, f), 'utf8');
  const has = f => fs.existsSync(path.join(dir, f));
  return {
    vert: '#version 300 es\n' + r('film.vert'),
    frag: '#version 300 es\n#define SAMPLER sampler2D\n' + r('film.frag'),
    pre: has('halo_prefilter.frag') ? '#version 300 es\n#define SAMPLER sampler2D\n' + r('halo_prefilter.frag') : null,
    blur: has('halo_blur.frag') ? '#version 300 es\n' + r('halo_blur.frag') : null,
  };
}

async function renderOne(page, job) {
  const dir = job.shaderDir || path.resolve(__dirname, '../../app/src/main/assets/shaders');
  const src = shaders(dir);
  const p = Object.assign({}, DEFAULTS, loadParams(job.params));
  const imgB64 = fs.readFileSync(job.input).toString('base64');
  const result = await page.evaluate(async ({ src, imgB64, p, shortSide, HALO_SHORT }) => {
    const img = new Image();
    img.src = 'data:image/jpeg;base64,' + imgB64;
    await img.decode();
    let w = img.width, h = img.height;
    if (shortSide) { const s = shortSide / Math.min(w, h); w = Math.round(w * s); h = Math.round(h * s); }
    const canvas = document.createElement('canvas');
    canvas.width = w; canvas.height = h;
    const gl = canvas.getContext('webgl2', { preserveDrawingBuffer: true });
    if (!gl) return { error: 'no webgl2' };
    const compile = (type, s) => { const o = gl.createShader(type); gl.shaderSource(o, s); gl.compileShader(o);
      if (!gl.getShaderParameter(o, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(o)); return o; };
    const program = (fs) => { const pr = gl.createProgram(); gl.attachShader(pr, compile(gl.VERTEX_SHADER, src.vert));
      gl.attachShader(pr, compile(gl.FRAGMENT_SHADER, fs)); gl.linkProgram(pr);
      if (!gl.getProgramParameter(pr, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(pr)); return pr; };
    let film, pre, blur;
    try { film = program(src.frag); if (src.pre) { pre = program(src.pre); blur = program(src.blur); } }
    catch (e) { return { error: String(e) }; }

    // Input texture: resampled to the output size (like a 1080p camera buffer), with mipmaps
    // (the photo path builds mipmaps for the halation downsample too).
    const srcCanvas = document.createElement('canvas'); srcCanvas.width = w; srcCanvas.height = h;
    srcCanvas.getContext('2d').drawImage(img, 0, 0, w, h);
    const tex = (width, height, data, mip) => { const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
      if (data) gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, data);
      else gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, width, height, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
      if (mip) gl.generateMipmap(gl.TEXTURE_2D);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, mip ? gl.LINEAR_MIPMAP_LINEAR : gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE); return t; };
    const input = tex(w, h, srcCanvas, true);

    const quad = (pr) => {
      for (const [name, data] of [['aPosition', [-1,-1, 1,-1, -1,1, 1,1]], ['aTexCoord', [0,0, 1,0, 0,1, 1,1]]]) {
        const b = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, b);
        gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(data), gl.STATIC_DRAW);
        const loc = gl.getAttribLocation(pr, name); if (loc < 0) continue;
        gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
      }
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    };
    // Canvas row 0 is the top, GL row 0 the bottom: flip V when reading the image so output is upright.
    const FLIP = [1,0,0,0, 0,-1,0,0, 0,0,1,0, 0,1,0,1];
    const IDENT = [1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1];
    const vertUniforms = (pr, m) => { gl.uniformMatrix4fv(gl.getUniformLocation(pr, 'uTexMatrix'), false, m);
      gl.uniform2f(gl.getUniformLocation(pr, 'uWeave'), 0, 0); gl.uniform1f(gl.getUniformLocation(pr, 'uInset'), 0); };

    // ---- halation passes (mirrors HalationPass.kt) ----
    // 1x1 black texture = "no halation" (what the app binds when halation is 0).
    let halo = tex(1, 1, null, false);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array([0, 0, 0, 255]));
    if (pre && p.halation > 0) {
      const s = HALO_SHORT / Math.min(w, h);
      const hw = Math.max(1, Math.round(w * s)), hh = Math.max(1, Math.round(h * s));
      const ta = tex(hw, hh, null, false), tb = tex(hw, hh, null, false);
      const fb = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
      gl.viewport(0, 0, hw, hh);
      // prefilter -> ta
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, ta, 0);
      gl.useProgram(pre); vertUniforms(pre, FLIP);
      gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, input);
      gl.uniform1i(gl.getUniformLocation(pre, 'uTex'), 0);
      const f = (w / hw) / 4;
      gl.uniform2f(gl.getUniformLocation(pre, 'uTapStep'), f / w, f / h);
      quad(pre);
      // blur H: ta -> tb ; blur V: tb -> ta
      gl.useProgram(blur); vertUniforms(blur, IDENT);
      gl.uniform1i(gl.getUniformLocation(blur, 'uSrc'), 0);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tb, 0);
      gl.bindTexture(gl.TEXTURE_2D, ta); gl.uniform2f(gl.getUniformLocation(blur, 'uDir'), 1.25 / hw, 0); quad(blur);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, ta, 0);
      gl.bindTexture(gl.TEXTURE_2D, tb); gl.uniform2f(gl.getUniformLocation(blur, 'uDir'), 0, 1.25 / hh); quad(blur);
      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
      halo = ta;
    }

    // ---- main pass (mirrors FilmShader.draw) ----
    gl.useProgram(film); vertUniforms(film, FLIP);
    const u = n => gl.getUniformLocation(film, n);
    gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, input); gl.uniform1i(u('uTex'), 0);
    gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, halo); gl.uniform1i(u('uHalo'), 1);
    gl.activeTexture(gl.TEXTURE0);
    gl.uniform2f(u('uResolution'), w, h);
    const r = 0.75 * Math.min(w, h) / 1080;
    gl.uniform2f(u('uBlurStep'), r / w, r / h);
    gl.uniform1f(u('uTime'), 0); gl.uniform1f(u('uSwapRB'), 0); gl.uniform1f(u('uBypass'), p.bypass ? 1 : 0);
    const f1 = ['intensity','contrast','blackLift','whiteRoll','saturation','greenDesat','greenToOlive','grainAmount',
      'grainSize','softness','vignette','exposure','warmth','halation','filmCurve','dyeMix','pastel','colorDensity',
      'grainDensity','grainColor'];
    for (const k of f1) gl.uniform1f(u('u' + k[0].toUpperCase() + k.slice(1)), p[k]);
    gl.uniform1f(u('uFlicker'), 0);
    const t3 = t => [t.r, t.g, t.b];
    gl.uniform3fv(u('uShadowTint'), t3(p.shadowTint));
    gl.uniform3fv(u('uMidTint'), t3(p.midTint));
    gl.uniform3fv(u('uHighlightTint'), t3(p.highlightTint));
    const linear = p.exposure !== 0 || p.warmth !== 0 || p.halation > 0;
    gl.uniform1f(u('uLinearStage'), linear ? 1 : 0);
    const hueActive = p.hues.some(b => b.saturation !== 1 || b.brightness !== 0 || b.shift !== 0);
    gl.uniform1f(u('uHueBands'), hueActive ? 1 : 0);
    gl.uniform3fv(u('uHueAdj[0]'), p.hues.flatMap(b => [b.saturation, b.brightness, b.shift]));
    gl.viewport(0, 0, w, h);
    quad(film);
    return { png: canvas.toDataURL('image/png'), w, h, err: gl.getError() };
  }, { src, imgB64, p, shortSide: job.shortSide || 0, HALO_SHORT });
  if (result.error) throw new Error('SHADER ERROR: ' + result.error);
  fs.writeFileSync(job.output, Buffer.from(result.png.split(',')[1], 'base64'));
  console.log(`wrote ${job.output} ${result.w}x${result.h} glError=${result.err}`);
}

(async () => {
  const args = process.argv.slice(2);
  const jobs = args[0] === '--jobs'
    ? JSON.parse(fs.readFileSync(args[1], 'utf8'))
    : [{ input: args[0], output: args[1], shortSide: args[2] ? parseInt(args[2]) : 0, params: args[3] }];
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
  const page = await browser.newPage();
  try {
    for (const job of jobs) await renderOne(page, job);
  } catch (e) {
    console.error(String(e)); process.exitCode = 1;
  } finally {
    await browser.close();
  }
})();
