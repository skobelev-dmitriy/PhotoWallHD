"""Effect math (see docs/EFFECT-SPEC.md) and an animated preview renderer.

The formulas here are mirrored in the Android app (LiveMath.kt); tests/test_effects.py pins the numbers.
"""
from __future__ import annotations

import io
from typing import Iterable

import numpy as np
from PIL import Image
from scipy.ndimage import map_coordinates

TAU = 2.0 * np.pi

DEFAULT_PARAMS = {
    "ripple": {"amplitude": 0.0016, "wavelength": 0.12, "speed": 0.35, "perspective": 0.7},
    "sway": {"amplitude": 0.004, "speed": 0.25, "spatial": 3.0},
    "clouds": {"amplitude": 0.002, "speed": 0.05},
    "breathe": {"amplitude": 0.002, "speed": 0.2},
    "flow": {"amplitude": 0.006, "wavelength": 0.15, "speed": 0.5},
}


def displacement(effect: str, p: dict, bbox, u, v, t: float, aspect: float):
    """Displacement (dx, dy) in fractions of the image width for arrays u, v (before mask/strength)."""
    u0, v0, u1, v1 = bbox
    A = p.get("amplitude", 0.0)
    f = p.get("speed", 0.0)
    span = max(v1 - v0, 1e-6)

    if effect == "ripple":
        lam = max(p.get("wavelength", 0.12), 1e-4)
        P = p.get("perspective", 0.7)
        g = np.clip((v - v0) / span, 0.0, 1.0)
        w = (1.0 - P) + P * g
        s = v / lam + 0.37 * u * aspect / lam
        dx = A * w * (np.sin(TAU * (s - f * t)) + 0.5 * np.sin(TAU * (1.6 * s + 0.31 * u * aspect / lam + 2.0 * f * t))) / 1.5
        dy = 0.35 * A * w * np.cos(TAU * (1.3 * s - f * t + 0.25))
        return dx, dy

    if effect == "sway":
        K = p.get("spatial", 3.0)
        h = np.clip((v1 - v) / span, 0.0, 1.0) ** 1.5
        ph = TAU * (f * t + K * u)
        dx = A * h * (0.7 * np.sin(ph) + 0.3 * np.sin(2.0 * ph + 1.1))
        dy = 0.15 * A * h * np.sin(ph + 1.7)
        return dx, dy

    if effect == "clouds":
        dx = A * (np.sin(TAU * (0.7 * u + 0.3 * v - f * t)) + 0.5 * np.sin(TAU * (1.9 * u - 1.1 * v - 2.0 * f * t) + 1.0)) / 1.5
        dy = 0.4 * A * np.sin(TAU * (0.5 * u + 0.9 * v - f * t) + 2.0)
        return dx, dy

    if effect == "breathe":
        s = A * np.sin(TAU * f * t)
        k = s / (1.0 + s)
        ax = (u0 + u1) / 2.0
        ay = v1
        return (u - ax) * k * 0.5, (v - ay) * k / aspect

    if effect == "flow":
        lam = max(p.get("wavelength", 0.15), 1e-4)
        dx = A * np.sin(TAU * (v / lam - f * t) + TAU * 0.3 * u * aspect / lam)
        return dx, np.zeros_like(dx)

    return np.zeros_like(u), np.zeros_like(u)


def total_displacement(layers: Iterable[dict], masks: dict, u, v, t: float, aspect: float, gain: float = 1.0):
    """Sum of all enabled layers, each weighted by its mask (arrays of the same shape as u, v)."""
    dx = np.zeros_like(u)
    dy = np.zeros_like(u)
    for layer in layers:
        if not layer.get("enabled", True):
            continue
        m = masks.get(layer["id"])
        if m is None:
            continue
        ldx, ldy = displacement(layer["effect"], layer["params"], layer["bbox"], u, v, t, aspect)
        k = layer.get("strength", 1.0) * gain
        dx += k * m * ldx
        dy += k * m * ldy
    return dx, dy


def render_preview_gif(image: Image.Image, layers: list[dict], masks_full: dict, width: int = 640,
                       frames: int = 24, fps: int = 12, gain: float = 3.0) -> bytes:
    """A seamless-loop GIF of the recipe applied to the photo.

    `masks_full` maps layer id -> float32 array (0..1) at any resolution. Speeds are snapped to whole cycles per loop
    so the GIF loops without a jump; `gain` exaggerates the motion so it is visible in a small preview.
    """
    w = width
    h = max(1, round(image.height * width / image.width))
    base = np.asarray(image.convert("RGB").resize((w, h), Image.LANCZOS), dtype=np.float32)
    aspect = w / h
    loop_s = frames / fps

    masks = {}
    for lid, m in masks_full.items():
        im = Image.fromarray((np.clip(m, 0, 1) * 255).astype(np.uint8)).resize((w, h), Image.BILINEAR)
        masks[lid] = np.asarray(im, dtype=np.float32) / 255.0

    snapped = []
    for layer in layers:
        p = dict(layer["params"])
        if "speed" in p:
            p["speed"] = max(1, round(p["speed"] * loop_s)) / loop_s
        snapped.append({**layer, "params": p})

    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    u = xx / (w - 1)
    v = yy / (h - 1)
    out = []
    for i in range(frames):
        t = i / fps
        dx, dy = total_displacement(snapped, masks, u, v, t, aspect, gain)
        src_x = (u - dx) * (w - 1)
        src_y = (v - dy * aspect) * (h - 1)
        frame = np.stack(
            [map_coordinates(base[..., c], [src_y, src_x], order=1, mode="nearest") for c in range(3)], axis=-1)
        out.append(Image.fromarray(np.clip(frame, 0, 255).astype(np.uint8)))
    buf = io.BytesIO()
    out[0].save(buf, format="GIF", save_all=True, append_images=out[1:], duration=int(1000 / fps), loop=0, optimize=False)
    return buf.getvalue()
