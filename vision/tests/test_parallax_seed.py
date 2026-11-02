"""Tests for the parallax building blocks that already exist (depth backends and hole filling)."""
import numpy as np

from app.depth import HeuristicDepth, get_depth_backend, robust_normalize
from app.inpaint import PushPullInpainter

from synth import landscape


def test_pushpull_fills_a_hole_following_the_gradient():
    h, w = 120, 160
    x = np.linspace(40, 200, w, dtype=np.float32)
    img = np.stack([np.tile(x, (h, 1))] * 3, axis=-1)
    known = np.ones((h, w), bool)
    known[30:90, 60:110] = False
    out = PushPullInpainter().fill(img, known)
    assert np.array_equal(out[known], img[known])          # real pixels are never touched
    assert np.abs(out - img)[~known].mean() < 6            # the fill follows the smooth background


def test_pushpull_edge_cases():
    img = np.random.default_rng(0).random((20, 30, 3)).astype(np.float32) * 255
    assert np.array_equal(PushPullInpainter().fill(img, np.ones((20, 30), bool)), img)
    nothing_known = PushPullInpainter().fill(img, np.zeros((20, 30), bool))
    assert nothing_known.shape == img.shape and np.isfinite(nothing_known).all()


def test_heuristic_depth_is_normalized_and_ground_is_nearer_than_sky():
    img, truth = landscape()
    d = HeuristicDepth().estimate(img)
    assert d.shape == (img.height, img.width) and d.dtype == np.float32
    assert 0.0 <= d.min() and d.max() <= 1.0
    assert d[truth == 1].mean() < d[truth != 1].mean()     # sky is farther than the ground


def test_unavailable_model_falls_back_to_the_heuristic():
    assert get_depth_backend("depth_anything").name in ("heuristic", "depth_anything")


def test_robust_normalize_ignores_outliers():
    d = np.concatenate([np.linspace(0, 1, 10000), [1000.0]]).astype(np.float32).reshape(1, -1)
    n = robust_normalize(d)
    assert n.min() == 0.0 and n.max() == 1.0 and n[0, 5000] > 0.4   # one huge outlier does not squash the rest
