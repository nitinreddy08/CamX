# FilmCam

A personal Android camera app with a live **film look**: muted, matte, golden-hour 16/35 mm feel with heavy organic grain. The filter is applied on the GPU to the live preview, baked into recorded videos (with sound), and applied to full-resolution photos. Everything is saved to the phone gallery.

- Kotlin · Jetpack Compose (Material 3, **light theme only**) · CameraX 1.6.2 · OpenGL ES 3.0
- minSdk 29 (Android 10), target/compile SDK 37
- Package `com.nitin.filmcam`. The app name is in `app/src/main/res/values/strings.xml`.
- Separate app. It does not touch or depend on the stock camera.

---

## 1. Get the APK

### Option A: download from CI (no setup)
Every push builds a debug APK on GitHub Actions.

1. On GitHub, open **Actions → "Android build"** and pick the latest green run.
2. At the bottom of the run page, under **Artifacts**, download **FilmCam-debug-apk** (a zip).
3. Unzip it to get `app-debug.apk`.

### Option B: build it yourself
Requirements: JDK 17+ (21 recommended) and the Android SDK with **platform 37**. The easiest route is Android Studio (latest stable), which installs everything.

Without Android Studio:
1. Install the [command-line tools](https://developer.android.com/studio#command-line-tools-only), then run
   `sdkmanager "platforms;android-37" "build-tools;37.0.0" "platform-tools"`
2. Point the build at the SDK with `export ANDROID_HOME=~/Android/Sdk` (or create `local.properties` with `sdk.dir=...`).
3. Build:

```bash
./gradlew assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # JVM unit tests
```

Gradle 9.9.1 is downloaded automatically by the wrapper. AGP 9.5.1 compiles Kotlin itself ("built-in Kotlin"), so there is no `kotlin-android` plugin.

## 2. Install on the phone
- **USB:** enable *Developer options → USB debugging*, then run `adb install -r app-debug.apk`.
- **Without a computer:** copy the APK to the phone, open it in Files, and allow "Install unknown apps" for that app when Android asks.

The repo includes a fixed **debug keystore** (`app/debug.keystore`), so every CI build has the same signature. Newer APKs install over older ones and your saved looks and presets are kept.

---

## 3. Using it

| Where | What |
|---|---|
| Top bar | **Flash** (photo: off/auto/on; video: torch on/off) · **4:3 / 16:9** (photo mode; video is always 16:9) · **Settings** |
| Preview | **Tap** to focus/expose · **pinch** to zoom · **long-press** to see the unfiltered image while you hold |
| Bottom | **PHOTO · VIDEO** toggle · last-capture **thumbnail** (opens the gallery) · **shutter** · **Front/Back** |
| **Filter ▲** | Opens the slider panel above the controls (about a third of the screen, so the preview stays big): presets, Intensity, Exposure, Warmth, Grain amount, Grain size, Halation, Softness, Vignette, Reset, and **Advanced** (film curve, contrast, black lift, white roll-off, saturation, pastel highlights, dye mixing, colour density, green controls, **per-colour editor**, three RGB tints, grain character, video film motion) |
| Settings | Also save original photo · Record 4K video (only if the camera supports it) · **Shader test screen** |

- **Presets:** tap a preset to apply it, tap **+ Save** to store the current sliders, and long-press a preset to delete it. Two built-ins can't be deleted: **Field** (the original spec look) and **Golden** (the tuned film look, see §5b).
- **Persistence:** the last-used look, your presets and your settings are kept across restarts (DataStore, JSON).
- **Saved files:** `Pictures/FilmCam/IMG_yyyyMMdd_HHmmss.jpg` (JPEG quality 95) and `Movies/FilmCam/VID_yyyyMMdd_HHmmss.mp4`. With "save original" on, you also get `IMG_..._original.jpg`.

---

## 4. Manual test checklist (per milestone)

**M1: install, permissions, preview**
1. Install and open. You should see a cream screen explaining camera and microphone use.
2. Tap **Allow camera & microphone** and allow both. The live preview appears.
3. Reinstall, deny the camera, then tap again. After a second denial you see an "Open app settings" link. Allow the camera there and come back: the preview appears.

**M2: live filter**
1. The preview should look muted, low contrast and matte, with no true blacks. Greens look olive and skies pale. You should see visible grain that moves every frame.
2. Point it at a flat wall: the grain should be clearly visible, with a slight mauve cast.
3. Motion should be smooth (≥ 30 fps), without lag when you pan quickly.

**M3: photos**
1. In PHOTO mode, tap the shutter. A "Processing…" chip appears briefly, and the camera keeps working meanwhile.
2. The thumbnail updates. Tap it to open the photo in the gallery. It's in *Pictures/FilmCam*, and the look and grain match the preview.
3. Hold the phone in landscape and take a photo. It should be saved in landscape, the right way up.
4. Switch to 4:3 / 16:9 and take a photo in each. Try flash auto/on, and try the front camera.
5. Check the speed. Logcat tag `CameraViewModel` prints `Photo WxH processed in N ms`. The spec target is < 3 s for 12 MP.

**M4: video**
1. Tap **VIDEO**, then the red shutter. A red timer counts up. Tap again to stop.
2. The video in *Movies/FilmCam* has the film look and moving grain, and plays with sound.
3. Record about a minute while moving the camera: the motion should stay smooth (30 fps).
4. Deny the microphone and record: you get a "Recording without sound" message, and the video still saves.

**M5: sliders, compare, persistence**
1. Open **Filter ▲** and drag each slider. The preview changes immediately.
2. Long-press the preview: the unfiltered image shows while you hold, with an "Original" chip.
3. Change something, then fully close and reopen the app. Your look is still there.
4. **Reset to default** brings back the Field look.

**M6: presets, settings, debug**
1. Use **+ Save** to save "Test", change the sliders, then tap "Test" to apply it again. Long-press "Test" to delete it.
2. In Settings, turn on "Also save original photo" and take a photo: two files appear.
3. Settings → **Shader test screen** shows before/after for the bundled test image. **Pick a photo** runs one of your own.
4. Check the "Grain consistency" crops: 1080p and full size should show grain of about the same size.
5. If 4K is enabled, record a 4K clip and check that it stays smooth.

---

## 5. How it works (for learning)

```
Camera sensor
   │
CameraX ─┬─ Preview ──────────┐
         ├─ VideoCapture ─────┤ CameraEffect → FilmSurfaceProcessor (GL thread "FilmGL")
         │                    │   camera frame (OES texture) → film.frag → preview + encoder
         └─ ImageCapture (unfiltered full-res JPEG)
                └─ PhotoProcessor → PhotoFilterRenderer (GL thread "PhotoGL", same film.frag)
                       → JPEG 95 + EXIF → MediaStore
```

| Package | Contents |
|---|---|
| `gl/` | `EglCore` (EGL setup), `GlUtil`, `FilmShader` (**the one place every uniform is set**), `FilmSurfaceProcessor` (live), `PhotoFilterRenderer` (offscreen), `ExifTransform` |
| `filter/` | `FilmParams` (the look), `ShaderSource` (loads `assets/shaders`), `PresetCodec` (JSON), `SettingsRepository` (DataStore) |
| `camera/` | `CameraController`: binds the use cases, focus, zoom, flash, photo, recording |
| `storage/` | `FileNames`, `MediaStoreSaver`, `PhotoProcessor` |
| `ui/` | Compose screens: camera, filter panel, presets, settings, shader test, permissions, theme |

**Tweak the look without touching Kotlin:** edit `app/src/main/assets/shaders/film.frag`. It is heavily commented, and the same file is used for preview, video and photos.

**Preview the shader on a computer:** `node tools/shader-preview/render.js in.jpg out.png [shortSide]` renders an image through the real shader in headless Chromium (WebGL2 uses the same GLSL ES 3.00). It needs `npm i playwright` (or a global install). `tools/make_test_image.py` regenerates the synthetic test scene.

---

## 5b. The film engine (v2) and the Golden preset

What each control imitates, and why (all of it lives in `film.frag`, heavily commented):

| Control | What real film does | What the shader does |
|---|---|---|
| **Exposure** | Over-exposed negatives look airy and pastel | Multiplies the light (in linear light, i.e. real light amounts) before everything else, with a soft knee so it rolls into white instead of clipping |
| **Warmth** | Film has no auto white balance; golden hour stays golden | Colour-temperature shift (more red, less blue) at constant brightness |
| **Film curve** | Film responds to light along an S: soft toe, straight middle, soft shoulder | Per-channel soft-S around mid-grey; white lands exactly on paper white, black on the faded print black. Bright colours hit the shoulder first and turn pastel, like film |
| **Dye mixing** | Impure dyes bleed into each other: rich but muted colour | Small 3×3 colour matrix that keeps greys grey |
| **Pastel highlights** | Little dye near paper white, so highlights lose colour | Saturation fades toward the brightest (and darkest) tones |
| **Colour density** | Strong colours on film are *denser* (darker), not neon | Darkens pixels in proportion to how colourful they are |
| **Colours (8 bands)** | Warm hues reproduce richly; greens, blues and purples sit between the film's sensitivities and come out muted or shifted | Red, Orange/skin, Yellow, Green, Teal, Blue, Purple, Magenta: saturation, brightness and hue shift per band, smoothly blended; greys untouched |
| **Halation** | Bright light bounces off the film base back into the red layer: an orange-red glow around lamps, windows, sun | 3 small passes: isolate near-clipping light at ~1/4 resolution, blur it, add it back red-orange. Same relative size in preview and full-res photos |
| **Grain model** | Print grain shows most in shadows/midtones, least in bright highlights | Density-based weighting; grain is no longer weakened by Intensity |
| **Film motion** (video) | Projected film drifts a little and flickers | Tiny smoothed frame drift + ±3% brightness flicker; off by default, never in photos |

**Neutral values switch every new control off.** Field uses only neutral values, so it renders exactly as before (verified: 0 pixels differ between the v1 and v2 shaders on the test scene). Presets saved earlier load unchanged.

**Golden** was tuned offline on real sample photos (portrait, coffee, night sky, cat, garage) and on the test scene, using the same shader:
- **Tone:** film curve on; blacks lifted to 0.095 (matte), whites at 0.96 (bright and creamy, not grey), contrast 0.85.
- **Light:** warmth +0.16, halation 0.3, vignette 0.06.
- **Colour:** saturation 0.84, pastel 0.6, dye mixing 0.6, colour density 0.45.
- **Per colour:** red deep and slightly orange; orange/skin natural; yellow leans golden; green 0.55 shifted to olive; teal kept; blue 0.70 shifted to teal; purple and magenta faded.
- **Grain:** 0.09 at size 1.8, film density model.

Compared with Field on the samples, the brightest 1% sits at 0.93–0.95 instead of 0.79–0.85, with richer warm colours.

Tune it further on the phone, then save your version with **+ Save**.

---

## 6. Decisions and deviations from the spec (and why)

1. **Photo path: separate ImageCapture.** Photos are captured unfiltered at full resolution and then filtered offscreen with the same shader. Routing stills through the effect can cap them at stream resolution.
2. **Photo mode vs video mode bind different use cases.** Photo binds Preview + ImageCapture. Video binds Preview + VideoCapture with one effect for both, so CameraX shares a single stream and the recording is exactly the preview. Switching mode rebinds, which causes a short blink. Video is always 16:9.
3. **Order is soften → grade, not grade → soften.** Grading is per-pixel, so blurring first and grading once looks the same but is about 9× cheaper (one grade instead of nine).
4. **Grain and softness scale with the short side, not the height.** "1080p" means a short side of 1080 px. Using the short side makes portrait previews, landscape photos and 50 MP stills all match. I checked this offline: grain strength and grain size relative to the frame came out the same at 1080p and 12 MP.
5. **Grain is two-octave smoothed value noise with an integer hash (pcg3d).** This avoids the classic `sin()` hash, which shows patterns on mobile GPUs. The noise is luminance-only, approximately Gaussian (average of 3 hashes), and midtone-weighted as specified. It changes every frame in video and is static for photos.
6. **`FilmParams` tints use a `Tint(r,g,b)` data class instead of `FloatArray`.** Arrays break data-class equality, which is needed for Compose state and for detecting which preset is selected.
7. **The filter "sheet" is an inline panel, not a modal bottom sheet.** A modal sheet would cover and dim the preview you're tuning.
8. **Hold-to-compare never affects a recording in progress.**
9. **EXIF:** the rotation is applied on the GPU while filtering, and the saved file has orientation *normal*. It keeps the capture date, exposure time, f-number, ISO and focal length. Make and model are not copied.
10. **Versions are the latest stable as of 2026-11-17:** AGP 9.5.1, Gradle 9.9.1, Kotlin 2.4.21, CameraX 1.6.2, Compose BOM 2026.09.00. `.github/workflows/versions.yml` prints the latest versions if you want to bump them.
11. **This build environment couldn't reach Google's Maven/SDK host,** so every build was compiled and unit-tested on GitHub Actions. The shader was additionally compiled and rendered locally through WebGL2. Nothing has been run on a real phone yet; please use the checklist above.
12. **M7 (`.cube` LUT import) is not implemented.** It was optional and out of scope for this run.
13. **Engine v2 changes Intensity's meaning.** It now blends only the colour/tone look; grain keeps its own strength. Hold-to-compare is a true bypass: no grain, no filter.
14. **The film curve works on the phone's display values, not on scene light.** I tried a log-exposure curve first. Phone images hold ~6 stops below mid-grey but only ~2.5 above, so a log curve couldn't give lifted shadows and bright whites at the same time. The soft-S in display space does both.

## 7. Known limitations
- No OEM processing: this app uses the standard camera interface. You won't get the phone maker's own colour tuning, multi-frame HDR or night mode, so raw quality, especially in low light, is below the stock camera's.
- Some lenses (telephoto/ultra-wide) may not be exposed to third-party apps. Pinch zoom uses whatever CameraX offers.
- 4K with the live filter is heavier. Enable it only if it stays smooth on your phone.
- Very large photos (50 MP) take longer to filter and save than 12 MP ones.
- Much of the look comes from **how you shoot**: golden-hour light, backlight, close-ups and natural scenes. The filter handles colour and texture, not lighting.
