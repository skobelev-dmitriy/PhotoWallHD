"""Segmentation backends. Every backend returns (label_map[H, W] of int ids, {id: label name})."""
from __future__ import annotations

import logging
import os

import numpy as np
from PIL import Image
from scipy.ndimage import binary_closing, label as cc_label, median_filter, uniform_filter

log = logging.getLogger("vision")


class ClassicalBackend:
    """No model, no downloads: colour / texture / position heuristics for sky, water and vegetation.

    Crude on purpose: it exists so the whole pipeline (masks, recommendations, preview, projector) works anywhere,
    and as a fallback when the neural model cannot be loaded. Dark storm skies, night photos or unusual colours fool it.
    """

    name = "classical"
    confidence = 0.45
    id2label = {0: "background", 1: "sky", 2: "water", 3: "vegetation"}

    def segment(self, img: Image.Image):
        rgb = img.convert("RGB")
        a = np.asarray(rgb, dtype=np.float32) / 255.0
        hsv = np.asarray(rgb.convert("HSV"), dtype=np.float32)
        hue = hsv[..., 0] * 360.0 / 255.0
        sat = hsv[..., 1] / 255.0
        val = hsv[..., 2] / 255.0
        gray = a.mean(axis=-1)
        h, w = gray.shape

        mean = uniform_filter(gray, 7)
        tex = np.sqrt(np.maximum(uniform_filter(gray * gray, 7) - mean * mean, 0.0))

        blue = (hue > 180) & (hue < 260) & (sat > 0.12) & (val > 0.35)
        overcast = (sat < 0.14) & (val > 0.7)
        sky_c = (blue | overcast) & ((tex < 0.035) | (blue & (tex < 0.06)))
        sky_c = median_filter(sky_c.astype(np.uint8), size=5) > 0
        # sky = big sky-coloured blobs that sit in the upper part of the picture (dark clouds at the very top do not
        # break this, unlike a "connected to the top edge" rule)
        blobs, n = cc_label(binary_closing(sky_c, iterations=2))
        sky = np.zeros_like(sky_c)
        for i in range(1, n + 1):
            m = blobs == i
            ys = np.where(m.any(axis=1))[0]
            if m.sum() > 0.02 * h * w and ys.min() < 0.5 * h and m[: h // 2].sum() > 0.5 * m.sum():
                sky |= m

        # horizon per column: lowest sky row; water can only be below it, and never in the top 40% of the picture
        has_sky = sky.any(axis=0)
        last_sky = np.where(has_sky, h - 1 - np.argmax(sky[::-1], axis=0), int(0.4 * h))
        rows = np.arange(h)[:, None]
        below = (rows > (last_sky[None, :] + int(0.01 * h))) & (rows > int(0.4 * h))

        water_hue = (hue > 150) & (hue < 265) & (sat > 0.10) & (val > 0.15)
        water = median_filter((water_hue & (tex < 0.05) & below).astype(np.uint8), size=7) > 0

        green = (hue > 45) & (hue < 170) & (sat > 0.2) & (val > 0.12)
        veg = median_filter((green & ~sky & ~water).astype(np.uint8), size=5) > 0

        out = np.zeros((h, w), dtype=np.int32)
        out[veg] = 3
        out[water] = 2
        out[sky] = 1
        return out, self.id2label


class SegformerBackend:
    """SegFormer trained on ADE20K (150 scene classes: sea, water, tree, palm, grass, sky, person, flag ...)."""

    name = "segformer"
    confidence = 0.8

    def __init__(self, model_id: str):
        import torch  # noqa: F401  (imported here so the classical backend needs neither torch nor transformers)
        from transformers import AutoImageProcessor, SegformerForSemanticSegmentation

        self.model_id = model_id
        self.processor = AutoImageProcessor.from_pretrained(model_id)
        self.model = SegformerForSemanticSegmentation.from_pretrained(model_id).eval()
        self.id2label = {int(k): v for k, v in self.model.config.id2label.items()}

    def segment(self, img: Image.Image):
        import torch

        inputs = self.processor(images=img.convert("RGB"), return_tensors="pt")
        with torch.no_grad():
            logits = self.model(**inputs).logits
        up = torch.nn.functional.interpolate(logits, size=(img.height, img.width), mode="bilinear", align_corners=False)
        return up.argmax(dim=1)[0].cpu().numpy().astype(np.int32), self.id2label


def get_backend(name: str | None = None, model_id: str | None = None):
    """Loads the requested backend; falls back to the classical one if the model cannot be loaded."""
    name = (name or os.environ.get("BACKEND", "classical")).lower()
    if name == "segformer":
        model_id = model_id or os.environ.get("MODEL_ID", "nvidia/segformer-b2-finetuned-ade-512-512")
        try:
            return SegformerBackend(model_id)
        except Exception as e:  # missing torch/transformers, no network for the weights, ...
            log.warning("SegFormer backend unavailable (%s); using the classical backend", e)
    return ClassicalBackend()
