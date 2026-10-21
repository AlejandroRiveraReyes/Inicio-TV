"""Genera los fondos de pantalla de Inicio TV (1920x1080, WebP).

Uso:  python3 tools/wallpapers.py <carpeta-salida>
Requiere numpy y Pillow. Cada fondo es determinista (semilla fija).
"""
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

W, H = 1920, 1080
YY, XX = np.mgrid[0:H, 0:W].astype(np.float32)
U, V = XX / W, YY / H


def hexc(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], np.float32) / 255


def value_noise(rng, scale, octaves=5):
    """Ruido fractal suave sumando octavas de ruido aleatorio reescalado."""
    out = np.zeros((H, W), np.float32)
    amp, total = 1.0, 0.0
    for o in range(octaves):
        gw, gh = max(2, int(scale * 2 ** o * W / H)), max(2, int(scale * 2 ** o))
        small = Image.fromarray((rng.random((gh, gw)) * 255).astype(np.uint8))
        out += amp * np.asarray(small.resize((W, H), Image.BICUBIC), np.float32) / 255
        total += amp
        amp *= 0.5
    return out / total


def gauss(cx, cy, rx, ry):
    return np.exp(-(((U - cx) / rx) ** 2 + ((V - cy) / ry) ** 2))


def stars(rng, img, n, max_r=1.6, glow=True):
    layer = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(layer)
    for _ in range(n):
        x, y = rng.random() * W, rng.random() * H
        r = rng.random() ** 3 * max_r + 0.4
        b = int(120 + rng.random() * 135)
        d.ellipse([x - r, y - r, x + r, y + r], fill=(b, b, min(255, b + 20)))
    arr = np.asarray(layer, np.float32) / 255
    if glow:
        arr = arr + np.asarray(layer.filter(ImageFilter.GaussianBlur(3)), np.float32) / 255 * 0.8
    return img + arr


def finish(img, rng, path, grain=0.012):
    img = img + (rng.random((H, W, 1)).astype(np.float32) - 0.5) * grain  # grano: evita bandas
    out = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))
    out.save(path, "WEBP", quality=82, method=6)
    print(path, Path(path).stat().st_size // 1024, "KB")


def glow_lines(lines, width, blur, color_fn):
    """Dibuja polilíneas con halo: trazo nítido + dos desenfoques sumados."""
    layer = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(layer)
    for i, pts in enumerate(lines):
        d.line(pts, fill=color_fn(i), width=width, joint="curve")
    sharp = np.asarray(layer, np.float32) / 255
    soft = np.asarray(layer.filter(ImageFilter.GaussianBlur(blur)), np.float32) / 255
    wide = np.asarray(layer.filter(ImageFilter.GaussianBlur(blur * 4)), np.float32) / 255
    return sharp * 0.9 + soft * 1.4 + wide * 1.2


# --- 1. Aurora -----------------------------------------------------------------------------
def aurora(out):
    rng = np.random.default_rng(11)
    img = np.zeros((H, W, 3), np.float32)
    img += hexc("#03060F") * (1 - V[..., None]) + hexc("#070B1A") * V[..., None]
    img = stars(rng, img, 900, 1.3)
    streaks = value_noise(rng, 3, 3)
    fine = np.asarray(Image.fromarray((rng.random((1, 480)) * 255).astype(np.uint8))
                      .resize((W, H), Image.BICUBIC), np.float32) / 255
    ribbons = [
        (0.42, 0.10, 1.6, 0.0, "#19F0B0", "#2C7BFF", 0.075),
        (0.55, 0.07, 2.3, 1.7, "#7A5CFF", "#FF4FD8", 0.06),
        (0.33, 0.05, 3.1, 3.1, "#2BE3FF", "#19F0B0", 0.04),
    ]
    for base, amp, freq, ph, c1, c2, width in ribbons:
        curve = base + amp * np.sin(U * freq * math.pi + ph) + 0.03 * np.sin(U * 9 + ph * 2)
        dist = V - curve
        # cortina: cae hacia arriba desde la curva, con rayos verticales
        body = np.where(dist < 0, np.exp(dist / (width * 3.5)), np.exp(-(dist / width) ** 2 * 1.5))
        body *= (0.55 + 0.45 * streaks) * (0.82 + 0.18 * fine)
        col = hexc(c1) * (1 - U[..., None]) + hexc(c2) * U[..., None]
        img += body[..., None] * col * 0.85
    img += gauss(0.5, 1.15, 0.9, 0.35)[..., None] * hexc("#0B2A3A") * 0.6
    img = np.asarray(Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))
                     .filter(ImageFilter.GaussianBlur(3)), np.float32) / 255
    finish(img, rng, out)


# --- 2. Horizonte (synthwave) --------------------------------------------------------------
def horizonte(out):
    rng = np.random.default_rng(22)
    hz = 0.62
    sky = np.clip(V / hz, 0, 1)[..., None]
    img = hexc("#05020F") * (1 - sky) + hexc("#3A0A4A") * sky ** 1.6
    img = img + gauss(0.5, hz, 0.7, 0.12)[..., None] * hexc("#FF2E88") * 0.55
    img = stars(rng, img * (V < hz)[..., None] + img * (V >= hz)[..., None], 350, 1.1)
    # sol con franjas
    r = np.sqrt(((U - 0.5) * W / H) ** 2 + (V - (hz - 0.13)) ** 2)
    sun = (r < 0.2).astype(np.float32)
    t = np.clip((V - (hz - 0.33)) / 0.33, 0, 1)
    stripes = 1 - ((V > hz - 0.13) & (np.sin((V - hz) * 260) > (1 - (V - (hz - 0.13)) / 0.13 * 1.6))).astype(np.float32)
    suncol = hexc("#FFD36B") * (1 - t[..., None]) + hexc("#FF2E88") * t[..., None]
    halo = np.exp(-np.maximum(r - 0.2, 0) / 0.06)
    img = img + (sun * stripes)[..., None] * suncol + (halo * (1 - sun))[..., None] * hexc("#FF3D9A") * 0.35
    img = img * (V < hz)[..., None] + hexc("#07020F") * (V >= hz)[..., None]
    # rejilla en perspectiva
    lines = []
    for i in range(-24, 25):
        x0 = W / 2 + i * 22
        x1 = W / 2 + i * 260
        lines.append([(x0, hz * H), (x1, H + 40)])
    z = 1.0
    while z < 60:
        y = hz * H + (H - hz * H) / z
        lines.append([(0, y), (W, y)])
        z *= 1.28
    g = glow_lines(lines, 2, 4, lambda i: (40, 220, 255))
    fade = np.clip((V - hz) / (1 - hz), 0, 1)[..., None] ** 0.7
    img = img + g * fade * (V >= hz)[..., None] * 0.9
    img = img + gauss(0.5, hz, 1.2, 0.02)[..., None] * hexc("#FF5AB0") * 0.9
    finish(img, rng, out)


# --- 3. Nebulosa ---------------------------------------------------------------------------
def nebulosa(out):
    rng = np.random.default_rng(33)
    n1 = value_noise(rng, 2.5, 6)
    n2 = value_noise(rng, 4, 5)
    mask = gauss(0.58, 0.45, 0.55, 0.45) * 1.3
    d = np.clip((n1 - 0.38) * 2.2, 0, 1) * mask
    e = np.clip((n2 - 0.45) * 2.5, 0, 1) * mask
    img = np.zeros((H, W, 3), np.float32) + hexc("#02030A")
    img += d[..., None] ** 1.4 * hexc("#3B3BFF") * 0.9
    img += (d * e)[..., None] * hexc("#FF3FA4") * 1.3
    img += e[..., None] ** 2 * hexc("#28E0FF") * 0.6
    img += gauss(0.6, 0.45, 0.08, 0.08)[..., None] * hexc("#FFE6F5") * 0.5
    img = stars(rng, img, 1400, 1.8)
    finish(img, rng, out)


# --- 4. Malla (degradado vítreo) -----------------------------------------------------------
def malla(out):
    rng = np.random.default_rng(44)
    img = np.zeros((H, W, 3), np.float32) + hexc("#05060C")
    blobs = [
        (0.12, 0.2, 0.32, 0.42, "#2A3BFF", 0.85),
        (0.72, 0.1, 0.34, 0.34, "#8A2BFF", 0.75),
        (0.98, 0.9, 0.3, 0.42, "#FF6A3D", 0.7),
        (0.3, 1.02, 0.36, 0.26, "#00C2FF", 0.55),
        (0.58, 0.55, 0.18, 0.2, "#FF3FA4", 0.3),
    ]
    warp = value_noise(rng, 1.5, 3)
    for cx, cy, rx, ry, c, k in blobs:
        img += gauss(cx + (warp - 0.5) * 0.15, cy, rx, ry)[..., None] * hexc(c) * k
    img = img / (1 + img * 0.5)  # compresión suave de luces
    finish(img, rng, out, grain=0.02)


# --- 5. Ondas de neón ----------------------------------------------------------------------
def ondas(out):
    rng = np.random.default_rng(55)
    img = np.zeros((H, W, 3), np.float32) + hexc("#04030B")
    img += gauss(0.5, 0.6, 0.8, 0.5)[..., None] * hexc("#140A2E") * 0.8
    lines = []
    n = 46
    xs = np.linspace(-40, W + 40, 240)
    for i in range(n):
        t = i / (n - 1)
        ys = (0.58 * H + 140 * np.sin(xs / W * 2.6 * math.pi + t * 1.9)
              + 70 * np.sin(xs / W * 5.3 * math.pi - t * 2.4)
              + (t - 0.5) * 260 * np.sin(xs / W * math.pi))
        lines.append(list(zip(xs.tolist(), ys.tolist())))

    def col(i):
        t = i / (n - 1)
        a, b = hexc("#18E6FF"), hexc("#B23BFF")
        c = (a * (1 - t) + b * t) * 255 * (0.35 + 0.65 * math.sin(t * math.pi))
        return tuple(int(v) for v in c)

    img += glow_lines(lines, 1, 3, col) * 0.75
    finish(img, rng, out)


# --- 6. Órbitas ----------------------------------------------------------------------------
def orbitas(out):
    rng = np.random.default_rng(66)
    img = np.zeros((H, W, 3), np.float32) + hexc("#03040A")
    img = stars(rng, img, 500, 1.0, glow=False)
    cx, cy = W * 0.72, H * 0.62
    lines, dots = [], []
    for k in range(1, 13):
        r = 70 * k ** 1.18
        tilt = 0.42
        pts = [(cx + r * math.cos(a), cy + r * tilt * math.sin(a))
               for a in np.linspace(0, 2 * math.pi, 400)]
        lines.append(pts)
        a = rng.random() * 2 * math.pi
        dots.append((cx + r * math.cos(a), cy + r * tilt * math.sin(a), 3 + rng.random() * 5))
    img += glow_lines(lines, 1, 2, lambda i: tuple(int(v) for v in
                      (hexc("#4FA8FF") * (1 - i / 12) + hexc("#9B6BFF") * i / 12) * 255 * (0.9 - i * 0.05))) * 0.5
    layer = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(layer)
    for x, y, r in dots:
        d.ellipse([x - r, y - r, x + r, y + r], fill=(220, 240, 255))
    dl = np.asarray(layer, np.float32) / 255
    img += dl + np.asarray(layer.filter(ImageFilter.GaussianBlur(10)), np.float32) / 255 * 1.5
    # planeta central
    r = np.sqrt((XX - cx) ** 2 + (YY - cy) ** 2)
    planet = np.clip((60 - r) / 2, 0, 1)
    shade = np.clip(0.25 + ((XX - cx) * -0.6 + (YY - cy) * -0.8) / 60, 0, 1)
    img = img * (1 - planet[..., None]) + planet[..., None] * (hexc("#FFB36B") * shade[..., None] + hexc("#2A0F3A") * (1 - shade[..., None]))
    img += np.exp(-np.maximum(r - 60, 0) / 40)[..., None] * (r >= 60)[..., None] * hexc("#FF8A4F") * 0.35
    finish(img, rng, out)


WALLPAPERS = {
    "aurora": aurora,
    "horizonte": horizonte,
    "nebulosa": nebulosa,
    "malla": malla,
    "ondas": ondas,
    "orbitas": orbitas,
}

if __name__ == "__main__":
    dest = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    dest.mkdir(parents=True, exist_ok=True)
    for name, fn in WALLPAPERS.items():
        fn(str(dest / f"wp_{name}.webp"))
