"""Generates the synthetic, unbranded test scene used by the in-app shader debug screen.

Contents (top to bottom): a pale sky gradient with a low warm sun, foliage-like green blobs,
a flat mauve-grey wall (to judge grain + midtone tint), skin-tone and colour patches,
and a 0-100% grey ramp with pure black and pure white ends (to judge lifted blacks /
rolled-off whites). Output: app/src/main/assets/test/test_scene.jpg (3000x4000, 12 MP).

Run: python3 tools/make_test_image.py
"""
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

W, H = 3000, 4000
rng = np.random.default_rng(7)
img = np.zeros((H, W, 3), np.float32)

# Sky: deep blue at the top to warm pale near the horizon
y = np.linspace(0, 1, int(H * 0.38))[:, None]
top = np.array([0.20, 0.42, 0.78]); horizon = np.array([0.98, 0.80, 0.62])
img[: len(y)] = (top * (1 - y) + horizon * y)[:, None, :]

# Sun glow
yy, xx = np.mgrid[0:H, 0:W]
d = np.sqrt((xx - W * 0.7) ** 2 + (yy - H * 0.33) ** 2)
glow = np.clip(1 - d / 900, 0, 1) ** 2
img += glow[..., None] * np.array([1.0, 0.75, 0.45]) * 0.6
img[d < 140] = [1.0, 0.97, 0.9]

# Foliage band
fol_top, fol_bot = int(H * 0.36), int(H * 0.52)
base = Image.new("RGB", (W, fol_bot - fol_top))
dr = ImageDraw.Draw(base)
for _ in range(900):
    cx, cy = rng.integers(0, W), rng.integers(0, fol_bot - fol_top)
    r = rng.integers(20, 110)
    g = rng.uniform(0.25, 0.75)
    col = (int(255 * g * rng.uniform(0.3, 0.6)), int(255 * g), int(255 * g * rng.uniform(0.15, 0.4)))
    dr.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col)
base = base.filter(ImageFilter.GaussianBlur(3))
img[fol_top:fol_bot] = np.asarray(base, np.float32) / 255

# Flat wall
wall_top, wall_bot = fol_bot, int(H * 0.72)
img[wall_top:wall_bot] = [0.62, 0.60, 0.58]
grad = np.linspace(1.05, 0.85, W)[None, :, None]
img[wall_top:wall_bot] *= grad

# Colour + skin patches
patches = [
    (0.92, 0.76, 0.65), (0.80, 0.60, 0.48), (0.62, 0.43, 0.32), (0.42, 0.28, 0.20),  # skin tones
    (0.85, 0.35, 0.15), (0.95, 0.75, 0.15), (0.20, 0.55, 0.25), (0.25, 0.65, 0.75),   # orange, yellow, green, teal
    (0.20, 0.30, 0.70), (0.65, 0.25, 0.55), (0.75, 0.15, 0.15), (0.50, 0.50, 0.50),   # blue, magenta, red, grey
]
p_top, p_bot = int(H * 0.73), int(H * 0.86)
pw = W // 6
for i, c in enumerate(patches):
    row, col = divmod(i, 6)
    y0 = p_top + row * (p_bot - p_top) // 2
    y1 = p_top + (row + 1) * (p_bot - p_top) // 2
    img[y0 + 10 : y1 - 10, col * pw + 10 : (col + 1) * pw - 10] = c
img[p_top:p_bot][(img[p_top:p_bot].sum(-1) == 0)] = 0.15

# Grey ramp: black -> white, plus pure black / white blocks
r_top = int(H * 0.87)
ramp = np.linspace(0, 1, W)[None, :, None]
img[r_top : int(H * 0.94)] = ramp
img[int(H * 0.94) :, : W // 2] = 0.0
img[int(H * 0.94) :, W // 2 :] = 1.0

img = np.clip(img, 0, 1)
out = Image.fromarray((img * 255 + 0.5).astype(np.uint8))
out.save("app/src/main/assets/test/test_scene.jpg", quality=92)
print("wrote", out.size)
