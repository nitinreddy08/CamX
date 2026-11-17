# FilmCam — Implementation Notes (planning only, nothing built yet)

Source: `film-camera-app-spec.md`. These are my working notes for the overnight build.

## 0. Environment facts (checked 2026-11-17)
- Repo `CamX` is empty; working branch `ccr-713bc4b4-6prwj8`.
- Available: JDK 21, Gradle 8.14.3. **No Android SDK.**
- **`dl.google.com` is blocked by the network policy (403).** That host serves the Android SDK
  (platforms, build-tools) **and** Google's Maven repo (AGP, AndroidX, CameraX, Compose —
  `maven.google.com` just redirects to it). Without it: nothing compiles here.
- Reachable: `plugins.gradle.org`, Maven Central (rate-limited), GitHub.
- Fix options: (a) allow `dl.google.com` in the environment's network settings → I install
  cmdline-tools + platform + build-tools and run `./gradlew assembleDebug testDebugUnitTest`
  locally after every milestone; (b) GitHub Actions workflow builds the APK on push and I read
  the job logs to fix compile errors (slower loop, but runners have the SDK); (c) write blind.

## 1. Build order (one commit per milestone, pushed to the branch)
| # | What | Key files |
|---|------|-----------|
| M1 | Gradle KTS + version catalog, Compose M3 light theme, permission screen (CAMERA + RECORD_AUDIO), plain CameraX Preview into a `PreviewView`/`CameraXViewfinder` | `MainActivity`, `ui/theme`, `ui/PermissionScreen`, `camera/CameraController` |
| M2 | GL thread + EGL helpers, `film.vert`/`film.frag` in assets, `FilmSurfaceProcessor` implementing `SurfaceProcessor`, wrapped in a `CameraEffect` (PREVIEW \| VIDEO_CAPTURE) | `gl/EglCore`, `gl/ShaderProgram`, `gl/FilmSurfaceProcessor`, `filter/FilmParams`, `filter/ShaderSource` |
| M3 | ImageCapture (max-quality) → decode → EXIF rotate → `PhotoFilterRenderer` (offscreen EGL, sampler2D variant of same shader, static grain) → JPEG q95 → MediaStore `Pictures/FilmCam` | `gl/PhotoFilterRenderer`, `storage/MediaStoreSaver`, `storage/FileNames` |
| M4 | `Recorder` + `VideoCapture` (FHD 30 fps default, audio), timer, save to `Movies/FilmCam` | `camera/RecordingController` |
| M5 | Filter bottom sheet (5 sliders + reset), Advanced collapsed panel, hold-to-compare, DataStore persistence of last params | `ui/FilterSheet`, `filter/ParamsRepository` |
| M6 | Presets (JSON in DataStore, default "Field"), settings (4:3/16:9, 4K if supported, save-original toggle, debug screen entry), flash, flip, pinch zoom, tap-to-focus, thumbnail → gallery, shader debug screen + grain consistency check, unit tests, README | `ui/SettingsScreen`, `ui/DebugShaderScreen`, `test/...` |
| M7 | (optional) `.cube` 33³ LUT import via SAF, uploaded as `GL_TEXTURE_3D`, replaces grade steps 1–5 | `filter/CubeLutParser`, shader `#define`/uniform toggle |

## 2. Technical decisions / deviations to report
- **One shader source, two samplers.** `film.frag` written once; Kotlin prepends either
  `samplerExternalOES` (camera) or `sampler2D` (photo) via a `#define` so preview/video/photo
  can never drift. All uniforms set in one `FilmUniforms.apply(program, params, resolution, time)`.
- **Photo path = separate ImageCapture** (spec default), not IMAGE_CAPTURE in the effect,
  because effect-routed stills can be capped at stream resolution. Will say so in README.
- **Per-output rendering.** With PREVIEW+VIDEO_CAPTURE on one effect CameraX uses stream
  sharing: one input `SurfaceTexture`, several `SurfaceOutput`s. Each output gets its own
  `updateTransformMatrix` (rotation/crop/mirroring) and its own `uResolution`, so grain size is
  correct in both the preview and the encoded video.
- **Hold-to-compare only affects the preview output**, never the recording (intensity override
  per output).
- **Softness must also be resolution-independent**, otherwise a 0.75 px blur is visible at
  1080p but invisible on a 50 MP photo → photo wouldn't match preview. Blur offset scaled by
  `uResolution.y / 1080`; for big photos use a few more taps so it stays smooth.
- **Grain:** 2 octaves of smoothed value noise (cell = `uGrainSize` and 2×), each ≈Gaussian
  (avg of 3 hashes − 0.5), luminance only, midtone mask from spec. `uTime` wrapped (mod ~1000 s)
  to avoid float precision loss in long recordings. Photo uses fixed seed.
- **Vignette** computed on aspect-corrected distance so it's round, not oval.
- **`FilmParams` tints:** spec uses `FloatArray`, which breaks `data class` equality (Compose
  state + DataStore diffing). I'll use a tiny `@Serializable data class Tint(r,g,b)` instead.
  Serialization: kotlinx.serialization JSON.
- **Big photos (50 MP):** ARGB bitmap ≈ 200 MB. Plan: check `GL_MAX_TEXTURE_SIZE`; render in
  tiles if needed; read back per tile; `largeHeap` as safety. Target < 3 s for 12 MP.
- **EXIF:** apply rotation before filtering, save with orientation = normal, copy date/time
  tags with `ExifInterface`.
- **Flash:** photo → ImageCapture flash mode (off/auto/on); video → torch.
- **Lifecycle:** `ProcessCameraProvider.bindToLifecycle`; processor owns a `HandlerThread` for
  GL; release EGL/textures/SurfaceTextures on `release()`.
- **Versions:** take latest stable CameraX / Compose BOM / AGP / Kotlin at build time (can't
  look them up right now because of the blocked host). No deprecated CameraX APIs.
- **Test image for debug screen:** generate a synthetic, unbranded image (gradients, grey
  ramp, colour + skin-tone patches, a flat area to judge grain) unless a photo is provided.
- **No brand names** anywhere (code, strings, icons). Simple vector app icon.

## 3. Verification I can do without a phone
- Compile + lint + JVM unit tests (FilmParams JSON round-trip, presets, filenames).
- Shader: compile GLSL offline if possible; also port the grade math into a JVM test that
  renders the synthetic test image to a PNG so the look can be checked visually before
  touching the phone.
- Cannot verify on-device: fps, camera binding on OnePlus, MediaStore saves, audio. Those go
  into the per-milestone manual test checklist in README.

## 4. Decisions from Nitin (2026-11-17)
- **Build env:** Nitin allows `dl.google.com` in the environment network settings → install
  SDK locally, compile + unit-test after every milestone. First thing on "start": re-check the
  host is reachable; if still blocked, fall back to CI-only compile loop and say so.
- **Scope:** M1–M6 in one run. M7 (LUT) skipped.
- **APK delivery:** GitHub Actions workflow (`.github/workflows/android.yml`) builds
  `app-debug.apk` on every push and uploads it as a run artifact. Add it in M1 so every
  milestone produces a downloadable APK. Check run status/logs via GitHub MCP tools.
- **Wrap-up:** push to `ccr-713bc4b4-6prwj8` after each milestone. **No PR.**
- **Assumptions (not asked, override if wrong):** synthetic test image; hold-to-compare
  affects preview only; `Tint` data class instead of `FloatArray`; separate ImageCapture path.
- Don't stop to ask anything during the run — note doubts in README and pick the safe option.
