import numpy as np
from PIL import Image

from app.effects import DEFAULT_PARAMS, displacement, render_preview_gif, total_displacement

U = np.array([0.1, 0.5, 0.9])
V = np.array([0.2, 0.6, 0.95])
BBOX = [0.0, 0.1, 1.0, 1.0]


def test_values_match_the_pinned_reference():
    """reference.json (written by gen_reference.py) is also checked by the Android implementation."""
    import json
    from pathlib import Path

    cases = json.loads((Path(__file__).parent / "reference.json").read_text())
    assert len(cases) >= 15
    for c in cases:
        u = np.array([x for x, _ in c["points"]])
        v = np.array([y for _, y in c["points"]])
        dx, dy = displacement(c["effect"], c["params"], c["bbox"], u, v, c["t"], c["aspect"])
        assert np.allclose(dx, c["dx"], atol=1e-12) and np.allclose(dy, c["dy"], atol=1e-12), c["effect"]


def test_ripple_stays_within_amplitude():
    dx, dy = displacement("ripple", DEFAULT_PARAMS["ripple"], BBOX, U, V, 0.7, 1.5)
    assert dx.shape == dy.shape == U.shape
    assert np.all(np.abs(dx) <= DEFAULT_PARAMS["ripple"]["amplitude"] * 1.0001)


def test_ripple_is_weaker_far_away():
    p = dict(DEFAULT_PARAMS["ripple"], perspective=1.0)
    v = np.array([0.15, 0.95])
    amp = []
    for t in np.linspace(0, 3, 400):
        dx, _ = displacement("ripple", p, BBOX, np.array([0.5, 0.5]), v, t, 1.5)
        amp.append(np.abs(dx))
    amp = np.max(amp, axis=0)
    assert amp[0] < 0.4 * amp[1]


def test_sway_base_is_fixed_and_top_moves():
    p = DEFAULT_PARAMS["sway"]
    dx_base, _ = displacement("sway", p, BBOX, np.array([0.5]), np.array([1.0]), 0.3, 1.5)
    dx_top, _ = displacement("sway", p, BBOX, np.array([0.5]), np.array([0.1]), 0.3, 1.5)
    assert abs(dx_base[0]) < 1e-12
    assert abs(dx_top[0]) > 1e-4


def test_breathe_is_zero_at_anchor_and_bounded():
    p = DEFAULT_PARAMS["breathe"]
    dx, dy = displacement("breathe", p, [0.3, 0.2, 0.7, 0.9], np.array([0.5]), np.array([0.9]), 1.1, 1.5)
    assert abs(dx[0]) < 1e-12 and abs(dy[0]) < 1e-12  # the anchor (bottom centre) does not move
    dx, dy = displacement("breathe", p, [0.3, 0.2, 0.7, 0.9], np.array([0.5]), np.array([0.2]), 1.25, 1.5)
    assert 0 < abs(dy[0]) < 0.01


def test_effects_are_periodic_in_time():
    for name, p in DEFAULT_PARAMS.items():
        period = 1.0 / p["speed"]
        a = displacement(name, p, BBOX, U, V, 0.37, 1.5)
        b = displacement(name, p, BBOX, U, V, 0.37 + period, 1.5)
        assert np.allclose(a[0], b[0], atol=1e-9) and np.allclose(a[1], b[1], atol=1e-9), name


def test_total_displacement_respects_mask_enabled_and_strength():
    layer = {"id": "a", "effect": "sway", "params": DEFAULT_PARAMS["sway"], "bbox": BBOX, "enabled": True, "strength": 2.0}
    u = np.full((2, 2), 0.5)
    v = np.full((2, 2), 0.2)
    mask = {"a": np.array([[1.0, 0.0], [0.5, 0.0]])}
    dx, _ = total_displacement([layer], mask, u, v, 0.3, 1.5)
    base, _ = displacement("sway", DEFAULT_PARAMS["sway"], BBOX, np.array(0.5), np.array(0.2), 0.3, 1.5)
    assert np.isclose(dx[0, 0], 2.0 * base) and np.isclose(dx[1, 0], base) and dx[0, 1] == 0
    layer["enabled"] = False
    assert not total_displacement([layer], mask, u, v, 0.3, 1.5)[0].any()


def test_preview_gif_loops_and_moves():
    rng = np.random.default_rng(0)
    img = Image.fromarray((rng.random((90, 120, 3)) * 255).astype(np.uint8))
    layer = {"id": "w", "effect": "ripple", "params": DEFAULT_PARAMS["ripple"], "bbox": [0, 0, 1, 1], "enabled": True, "strength": 1.0}
    gif = render_preview_gif(img, [layer], {"w": np.ones((90, 120), np.float32)}, width=120, frames=8, fps=8, gain=8.0)
    g = Image.open(__import__("io").BytesIO(gif))
    frames = []
    for i in range(g.n_frames):
        g.seek(i)
        frames.append(np.asarray(g.convert("RGB"), dtype=np.int32))
    assert len(frames) == 8
    assert np.abs(frames[0] - frames[3]).mean() > 0.5   # it moves
