"""Writes tests/reference.json: effect values at fixed points. The Android LiveMath.kt test must reproduce them."""
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.effects import DEFAULT_PARAMS, displacement  # noqa: E402

BBOX = [0.1, 0.3, 0.9, 0.95]
POINTS = [(0.1, 0.35), (0.5, 0.5), (0.9, 0.9), (0.3, 0.95), (0.7, 0.31)]
cases = []
for effect, p in DEFAULT_PARAMS.items():
    for t in (0.0, 0.7, 2.3):
        for aspect in (1.5,):
            u = np.array([x for x, _ in POINTS])
            v = np.array([y for _, y in POINTS])
            dx, dy = displacement(effect, p, BBOX, u, v, t, aspect)
            cases.append({"effect": effect, "params": p, "bbox": BBOX, "t": t, "aspect": aspect,
                          "points": POINTS, "dx": [float(x) for x in dx], "dy": [float(x) for x in dy]})
out = Path(__file__).with_name("reference.json")
out.write_text(json.dumps(cases, indent=1))
print("wrote", out, len(cases), "cases")
