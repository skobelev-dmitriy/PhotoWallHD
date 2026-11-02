"""Depth estimation: a relative depth map in 0..1 where 1 is the nearest, 0 the farthest."""
from __future__ import annotations

import logging
import os
import threading

import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter, uniform_filter

log = logging.getLogger("vision")


def robust_normalize(d: np.ndarray) -> np.ndarray:
    """Stretches to 0..1 using the 2nd and 98th percentiles so a few extreme pixels do not squash the range."""
    lo, hi = np.percentile(d, [2, 98])
    if hi - lo < 1e-6:
        return np.zeros_like(d, dtype=np.float32)
    return np.clip((d - lo) / (hi - lo), 0, 1).astype(np.float32)


class HeuristicDepth:
    """No model: a crude "ground plane + sky + sharpness" guess. Good only for trying the pipeline, NOT real depth.

    Lower in the picture = nearer, sky = far, sharp / contrasty = a bit nearer.
    """

    name = "heuristic"

    def estimate(self, img: Image.Image) -> np.ndarray:
        from .backends import ClassicalBackend

        small = img.convert("RGB")
        h, w = small.height, small.width
        ramp = np.linspace(0.0, 1.0, h, dtype=np.float32)[:, None] ** 1.2 * np.ones((1, w), dtype=np.float32)
        gray = np.asarray(small.convert("L"), dtype=np.float32) / 255.0
        mean = uniform_filter(gray, 9)
        tex = np.sqrt(np.maximum(uniform_filter(gray * gray, 9) - mean * mean, 0))
        sal = gaussian_filter(tex, max(2.0, 0.01 * max(h, w)))
        sal = sal / max(float(sal.max()), 1e-6)
        depth = 0.65 * ramp + 0.35 * sal
        seg, _ = ClassicalBackend().segment(small)
        depth[seg == 1] = np.minimum(depth[seg == 1], 0.08)  # sky is far
        return robust_normalize(gaussian_filter(depth, 2.0))


class DepthAnythingDepth:
    """Depth Anything V2 (Small by default) through the transformers depth-estimation pipeline.

    Not run in the development environment (the weights cannot be downloaded there). If the picture comes out inverted
    (near looks far), use the "invert depth" switch.
    """

    name = "depth_anything"

    def __init__(self, model_id: str):
        from transformers import pipeline

        self.model_id = model_id
        self.pipe = pipeline("depth-estimation", model=model_id, device=-1)
        self.lock = threading.Lock()

    def estimate(self, img: Image.Image) -> np.ndarray:
        with self.lock:
            out = self.pipe(img.convert("RGB"))
        d = np.asarray(out["depth"], dtype=np.float32)  # brighter = nearer, already the size of the input
        if d.shape != (img.height, img.width):
            d = np.asarray(Image.fromarray(d).resize((img.width, img.height), Image.BICUBIC), dtype=np.float32)
        return robust_normalize(d)


_cache: dict = {}
_lock = threading.Lock()


def get_depth_backend(name: str | None = None):
    """The requested depth backend; falls back to the heuristic one if the model cannot be loaded."""
    name = (name or os.environ.get("DEPTH_BACKEND", "heuristic")).lower()
    with _lock:
        if name in _cache:
            return _cache[name]
        backend = None
        if name == "depth_anything":
            model_id = os.environ.get("DEPTH_MODEL_ID", "depth-anything/Depth-Anything-V2-Small-hf")
            try:
                backend = DepthAnythingDepth(model_id)
            except Exception as e:  # no torch / transformers / weights
                log.warning("Depth Anything unavailable (%s); using the heuristic depth", e)
        backend = backend or HeuristicDepth()
        _cache[name] = backend
        return backend
