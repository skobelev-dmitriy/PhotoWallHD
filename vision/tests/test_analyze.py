import numpy as np

from app.analyze import analyze, extract_layers
from app.backends import ClassicalBackend
from app.classes import class_of

from synth import iou, landscape


def test_class_of_handles_ade20k_style_labels():
    assert class_of("sea") == "water"
    assert class_of("swimming pool, swimming bath, natatorium") == "water"
    assert class_of("waterfall, falls") == "water"
    assert class_of("palm, palm tree") == "foliage"
    assert class_of("plant, flora, plant life") == "foliage"
    assert class_of("person, individual, someone, somebody, mortal, soul") == "person"
    assert class_of("curtain, drape, drapery, mantle, pall") == "cloth"
    assert class_of("sky") == "sky"
    assert class_of("pool table, billiard table, snooker table") is None
    assert class_of("wall") is None


def test_classical_backend_finds_sky_lake_and_forest():
    img, truth = landscape()
    seg, labels = ClassicalBackend().segment(img)
    assert iou(seg == 1, truth == 1) > 0.8
    assert iou(seg == 2, truth == 2) > 0.7
    assert iou(seg == 3, truth == 3) > 0.8


def test_recipe_for_the_landscape():
    img, _ = landscape()
    recipe, masks, work = analyze(img, ClassicalBackend())
    by_class = {l["class"]: l for l in recipe["layers"]}
    assert by_class["water"]["effect"] == "ripple"
    assert by_class["foliage"]["effect"] == "sway"
    assert by_class["sky"]["effect"] == "clouds" and by_class["sky"]["enabled"]  # the clouds are visible
    for layer in recipe["layers"]:
        m = masks[layer["id"]]
        assert m.dtype == np.float32 and 0.0 <= m.min() and m.max() <= 1.0
        u0, v0, u1, v1 = layer["bbox"]
        assert 0 <= u0 < u1 <= 1 and 0 <= v0 < v1 <= 1


def test_extract_layers_from_an_ade20k_like_label_map():
    # what a neural model returns: a label map and an id -> name table
    id2label = {0: "wall", 1: "sky", 2: "sea", 3: "palm, palm tree", 4: "person, individual, someone, somebody, mortal, soul", 5: "tree"}
    seg = np.zeros((200, 300), dtype=np.int32)
    seg[:80] = 1
    seg[80:150, :] = 2
    seg[120:200, 0:60] = 3
    seg[100:190, 120:150] = 4
    seg[150:200, 200:300] = 5
    gray = np.random.default_rng(1).random(seg.shape).astype(np.float32)  # textured: clouds count as visible
    layers, masks = extract_layers(seg, id2label, gray, 0.8)
    classes = sorted(l["class"] for l in layers)
    assert classes == ["foliage", "foliage", "person", "sky", "water"]
    water = next(l for l in layers if l["class"] == "water")
    assert water["bbox"][1] > 0.35 and water["note"]
    person = next(l for l in layers if l["class"] == "person")
    assert person["effect"] == "breathe"


def test_plain_blue_sky_is_not_animated():
    id2label = {0: "wall", 1: "sky"}
    seg = np.zeros((100, 100), dtype=np.int32)
    seg[:60] = 1
    flat = np.full(seg.shape, 0.6, dtype=np.float32)
    layers, _ = extract_layers(seg, id2label, flat, 0.8)
    assert layers[0]["class"] == "sky" and layers[0]["enabled"] is False
