"""Filling the parts of a layer that are hidden behind nearer layers (they show when the camera moves)."""
from __future__ import annotations

import os

import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter, uniform_filter


def _pull_push(data: np.ndarray, wgt: np.ndarray) -> np.ndarray:
    """Push-pull fill: average known pixels at coarser and coarser scales, then paint the unknown from the coarse result."""
    h, w = wgt.shape
    if h <= 2 or w <= 2 or wgt.min() > 0:
        avg = data / np.maximum(wgt, 1e-6)[..., None]
        total = max(float(wgt.sum()), 1e-6)
        mean = data.sum(axis=(0, 1)) / total
        return np.where((wgt > 0)[..., None], avg, mean)
    ph, pw = h % 2, w % 2
    d = np.pad(data, ((0, ph), (0, pw), (0, 0)))
    wg = np.pad(wgt, ((0, ph), (0, pw)))
    d2 = d.reshape(d.shape[0] // 2, 2, d.shape[1] // 2, 2, 3).sum(axis=(1, 3))
    w2 = wg.reshape(wg.shape[0] // 2, 2, wg.shape[1] // 2, 2).sum(axis=(1, 3))
    coarse = _pull_push(d2, w2)
    up = np.repeat(np.repeat(coarse, 2, axis=0), 2, axis=1)[:h, :w]
    up = uniform_filter(up, size=(3, 3, 1))
    avg = data / np.maximum(wgt, 1e-6)[..., None]
    return np.where((wgt > 0)[..., None], avg, up)


class PushPullInpainter:
    """Fast, dependency-free: smooth, blurry fill. Fine for the small gaps that a gentle parallax reveals."""

    name = "pushpull"

    def fill(self, image: np.ndarray, known: np.ndarray) -> np.ndarray:
        """image: float32 (H, W, 3) 0..255; known: bool (H, W). Returns a full image: known pixels unchanged."""
        if known.all():
            return image
        if not known.any():
            return np.broadcast_to(image.mean(axis=(0, 1)), image.shape).copy()
        data = image * known[..., None]
        filled = _pull_push(data.astype(np.float32), known.astype(np.float32))
        # soften the filled part so it does not look blocky; keep the real pixels exactly
        soft = gaussian_filter(filled, sigma=(2.0, 2.0, 0))
        out = np.where(known[..., None], image, soft)
        return out.astype(np.float32)


class LamaInpainter:
    """Optional neural inpainting (LaMa) via the `simple-lama-inpainting` package. Not run in the development environment."""

    name = "lama"

    def __init__(self):
        from simple_lama_inpainting import SimpleLama

        self.model = SimpleLama()
        self.fallback = PushPullInpainter()

    def fill(self, image: np.ndarray, known: np.ndarray) -> np.ndarray:
        if known.all():
            return image
        mask = Image.fromarray(((~known) * 255).astype(np.uint8))
        out = self.model(Image.fromarray(np.clip(image, 0, 255).astype(np.uint8)), mask)
        arr = np.asarray(out.convert("RGB"), dtype=np.float32)
        if arr.shape != image.shape:  # the model may pad to a multiple of 8
            arr = np.asarray(Image.fromarray(arr.astype(np.uint8)).resize((image.shape[1], image.shape[0])), dtype=np.float32)
        return np.where(known[..., None], image, arr)


def get_inpainter(name: str | None = None):
    name = (name or os.environ.get("INPAINT_BACKEND", "pushpull")).lower()
    if name == "lama":
        try:
            return LamaInpainter()
        except Exception:
            pass
    return PushPullInpainter()
