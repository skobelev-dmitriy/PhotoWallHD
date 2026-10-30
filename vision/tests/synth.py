"""A synthetic landscape with known ground truth: sky with clouds, hills, a lake, a forest and a meadow."""
import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter


def landscape(w=640, h=427, seed=3):
    rng = np.random.default_rng(seed)
    img = np.zeros((h, w, 3), dtype=np.float32)
    truth = np.zeros((h, w), dtype=np.int32)  # 0 other, 1 sky, 2 water, 3 vegetation

    horizon = int(h * 0.45)
    # sky: blue gradient with soft white clouds
    g = np.linspace(0, 1, horizon)[:, None]
    sky = np.stack([np.full((horizon, w), 0.25) + 0.3 * g, np.full((horizon, w), 0.5) + 0.25 * g, np.full((horizon, w), 0.85) + 0.1 * g], axis=-1)
    clouds = gaussian_filter(rng.random((horizon, w)), 12)
    clouds = np.clip((clouds - clouds.mean()) * 9, 0, 1)[..., None]
    img[:horizon] = sky * (1 - 0.6 * clouds) + 0.95 * 0.6 * clouds
    truth[:horizon] = 1
    # far hills (brown-grey) just above the horizon, take them out of the sky
    hills = int(h * 0.07)
    img[horizon - hills:horizon] = (0.45, 0.4, 0.38)
    truth[horizon - hills:horizon] = 0
    # lake on the left-bottom (blue-green, smooth), forest on the right (green, textured), meadow (yellow-green) below
    lake_x = int(w * 0.5)
    img[horizon:, :lake_x] = (0.12, 0.38, 0.55)
    img[horizon:, :lake_x] += (gaussian_filter(rng.random((h - horizon, lake_x)), 3)[..., None] - 0.5) * 0.04
    truth[horizon:int(h * 0.85), :lake_x] = 2
    forest = np.zeros((h - horizon, w - lake_x, 3), dtype=np.float32)
    noise = rng.random((h - horizon, w - lake_x))
    forest[..., 0] = 0.10 + 0.15 * noise
    forest[..., 1] = 0.35 + 0.3 * noise
    forest[..., 2] = 0.08 + 0.1 * noise
    img[horizon:, lake_x:] = forest
    truth[horizon:, lake_x:] = 3
    # brown shore strip under the lake
    img[int(h * 0.85):, :lake_x] = (0.4, 0.3, 0.2)
    truth[int(h * 0.85):, :lake_x] = 0
    return Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8)), truth


def iou(a, b):
    inter = np.logical_and(a, b).sum()
    union = np.logical_or(a, b).sum()
    return inter / union if union else 1.0
