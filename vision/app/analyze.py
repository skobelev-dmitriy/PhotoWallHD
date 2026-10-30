"""Turns a segmentation map into animation layers: masks, borders, recommended effects and parameters."""
from __future__ import annotations

import io
import base64
from typing import Any

import numpy as np
from PIL import Image, ImageDraw, ImageOps
from scipy.ndimage import binary_closing, binary_opening, gaussian_filter, label as cc_label

from .classes import CLASS_KEYWORDS, CLASS_RU, COLORS, DEFAULT_EFFECT, MAX_PER_CLASS, MIN_AREA, class_of
from .effects import DEFAULT_PARAMS

WORK_SIDE = 512  # the segmentation and masks are computed with the long side scaled to this


def load_image(data: bytes) -> Image.Image:
    img = Image.open(io.BytesIO(data))
    img = ImageOps.exif_transpose(img)
    return img.convert("RGB")


def _clamp(x, lo, hi):
    return max(lo, min(hi, x))


def suggest(cls: str, label: str, bbox, area: float, lum_std: float) -> dict[str, Any]:
    """Recommended effect and parameters for one region, from its class and geometry (see docs/EFFECT-SPEC.md)."""
    u0, v0, u1, v1 = bbox
    hfrac = v1 - v0
    wfrac = u1 - u0
    effect = DEFAULT_EFFECT[cls]
    p = dict(DEFAULT_PARAMS[effect])
    enabled = True
    note = ""
    note_en = ""

    if cls == "water":
        p["wavelength"] = round(_clamp(0.08 + 0.08 * hfrac, 0.08, 0.16), 4)
        p["amplitude"] = round(0.0012 + 0.0016 * min(1.0, area * 2.0), 5)
        p["perspective"] = 0.7 if hfrac > 0.15 else 0.3
        note = "Вода: лёгкая рябь, вдали слабее, ближе к зрителю сильнее."
        note_en = "Water: gentle ripples, weaker in the distance and stronger near the viewer."
    elif cls == "foliage":
        p["amplitude"] = round(0.003 + 0.004 * min(1.0, hfrac * 2.0), 5)
        p["spatial"] = round(3.0 + 4.0 * wfrac, 2)
        note = "Листва: покачивание на ветру; основание стоит, верхушки качаются."
        note_en = "Foliage: swaying in the wind; the base stays, the tops move."
    elif cls == "sky":
        if lum_std < 0.05:
            enabled = False
            note = "Небо без заметных облаков: анимировать нечего."
            note_en = "Sky without visible clouds: nothing to animate."
        else:
            note = "Небо с облаками: очень медленное «дыхание» облаков."
            note_en = "Cloudy sky: a very slow drift of the clouds."
    elif cls == "person":
        note = "Человек: едва заметное «дыхание». Волосы и платье по ветру в этой версии не поддерживаются."
        note_en = "Person: a barely visible breathing motion. Hair and dress in the wind are not supported yet."
    elif cls == "cloth":
        note = "Ткань/флаг: волна по ветру."
        note_en = "Cloth/flag: a wave in the wind."
    elif cls == "animal":
        note = "Животное: едва заметное «дыхание»."
        note_en = "Animal: a barely visible breathing motion."
    return {"effect": effect, "params": p, "enabled": enabled, "note": note, "note_en": note_en}


def extract_layers(seg: np.ndarray, id2label: dict[int, str], gray: np.ndarray, backend_confidence: float):
    """Returns (layers, masks) where masks maps layer id -> float32 (H, W) in 0..1 with a soft edge."""
    h, w = seg.shape
    id_class = {i: class_of(name) for i, name in id2label.items()}
    layers: list[dict] = []
    masks: dict[str, np.ndarray] = {}
    sigma = max(1.5, 0.006 * max(h, w))

    for cls in CLASS_KEYWORDS:
        ids = [i for i, c in id_class.items() if c == cls]
        if not ids:
            continue
        binary = np.isin(seg, ids)
        if not binary.any():
            continue
        binary = binary_closing(binary_opening(binary, iterations=1), iterations=2)
        comp, n = cc_label(binary)
        if n == 0:
            continue
        areas = np.bincount(comp.ravel(), minlength=n + 1)[1:] / float(h * w)
        order = np.argsort(-areas)
        kept = 0
        for idx in order:
            area = float(areas[idx])
            if area < MIN_AREA[cls] or kept >= MAX_PER_CLASS[cls]:
                break
            m = comp == (idx + 1)
            ys, xs = np.where(m)
            bbox = [round(float(xs.min()) / (w - 1), 4), round(float(ys.min()) / (h - 1), 4),
                    round(float(xs.max()) / (w - 1), 4), round(float(ys.max()) / (h - 1), 4)]
            # the label that dominates inside the region (for display)
            vals, counts = np.unique(seg[m], return_counts=True)
            label = id2label[int(vals[np.argmax(counts)])].split(",")[0].strip()
            lum_std = float(gray[m].std())
            s = suggest(cls, label, bbox, area, lum_std)
            kept += 1
            lid = f"{cls}-{kept}"
            soft = np.clip(gaussian_filter(m.astype(np.float32), sigma), 0.0, 1.0)
            masks[lid] = soft
            layers.append({
                "id": lid, "class": cls, "class_ru": CLASS_RU[cls], "label": label, "effect": s["effect"],
                "confidence": round(backend_confidence * min(1.0, 0.7 + 3.0 * area), 2),
                "enabled": s["enabled"], "strength": 1.0, "bbox": bbox, "area": round(area, 4),
                "params": s["params"], "note": s["note"], "note_en": s["note_en"], "mask": lid + ".png",
            })
    return layers, masks


def analyze(image: Image.Image, backend) -> tuple[dict, dict[str, np.ndarray], Image.Image]:
    """Runs the backend and builds the recipe. Returns (recipe, masks at work size, the work-size image)."""
    W, H = image.size
    scale = WORK_SIDE / max(W, H)
    work = image if scale >= 1 else image.resize((max(1, round(W * scale)), max(1, round(H * scale))), Image.LANCZOS)
    seg, id2label = backend.segment(work)
    gray = np.asarray(work.convert("L"), dtype=np.float32) / 255.0
    layers, masks = extract_layers(seg, id2label, gray, backend.confidence)
    recipe = {
        "version": 1,
        "image": {"width": W, "height": H},
        "backend": backend.name,
        "layers": layers,
    }
    return recipe, masks, work


def mask_png(mask: np.ndarray) -> bytes:
    buf = io.BytesIO()
    Image.fromarray((np.clip(mask, 0, 1) * 255).astype(np.uint8), mode="L").save(buf, format="PNG", optimize=True)
    return buf.getvalue()


def recipe_with_masks(recipe: dict, masks: dict[str, np.ndarray]) -> dict:
    """The recipe as returned by the API: each layer carries its mask PNG as base64."""
    layers = []
    for layer in recipe["layers"]:
        layers.append({**layer, "mask_png_b64": base64.b64encode(mask_png(masks[layer["id"]])).decode("ascii")})
    return {**recipe, "layers": layers}


def overlay_png(work: Image.Image, recipe: dict, masks: dict[str, np.ndarray]) -> bytes:
    """The photo with every region tinted, its border drawn and its name written: what the model 'sees'."""
    base = np.asarray(work.convert("RGB"), dtype=np.float32)
    out = base.copy()
    for layer in recipe["layers"]:
        m = masks[layer["id"]][..., None]
        color = np.array(COLORS[layer["class"]], dtype=np.float32)
        out = out * (1 - 0.4 * m) + color * (0.4 * m)
    img = Image.fromarray(np.clip(out, 0, 255).astype(np.uint8))
    draw = ImageDraw.Draw(img)
    for layer in recipe["layers"]:
        m = masks[layer["id"]] > 0.5
        edge = m ^ binary_opening(m, iterations=2)  # thin border just inside the region
        color = COLORS[layer["class"]]
        ys, xs = np.where(edge)
        for x, y in zip(xs[::1], ys[::1]):
            draw.point((int(x), int(y)), fill=color)
        u0, v0, u1, v1 = layer["bbox"]
        tx, ty = int(u0 * (work.width - 1)) + 4, int(v0 * (work.height - 1)) + 4
        text = f'{layer["id"]}  {layer["effect"]}' + ("" if layer["enabled"] else "  (off)")
        draw.rectangle((tx - 2, ty - 1, tx + 6 * len(text) + 2, ty + 11), fill=(0, 0, 0))
        draw.text((tx, ty), text, fill=color)
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()
