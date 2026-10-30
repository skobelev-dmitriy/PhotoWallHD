"""Which segmentation labels can be animated, and with which effect."""

# Labels from scene-parsing models (ADE20K) look like "swimming pool, swimming bath, natatorium": a comma-separated
# list of synonyms. A label belongs to a class if any whole synonym is in the set.
CLASS_KEYWORDS = {
    "water": {"sea", "water", "river", "lake", "waterfall", "falls", "swimming pool", "fountain", "pond", "canal", "ocean"},
    "foliage": {"tree", "palm", "palm tree", "plant", "grass", "flower", "bush", "shrub", "vegetation", "foliage"},
    "sky": {"sky"},
    "person": {"person", "individual", "someone", "somebody", "mortal", "soul"},
    "cloth": {"flag", "curtain", "drape", "drapery", "awning", "sunshade", "banner", "streamer", "blind", "tent", "canopy"},
    "animal": {"animal", "animate being", "beast", "brute", "creature", "fauna"},
}

DEFAULT_EFFECT = {
    "water": "ripple",
    "foliage": "sway",
    "sky": "clouds",
    "person": "breathe",
    "cloth": "flow",
    "animal": "breathe",
}

# smallest region worth animating (fraction of the image) and how many regions of a class we keep
MIN_AREA = {"water": 0.015, "foliage": 0.01, "sky": 0.03, "person": 0.01, "cloth": 0.004, "animal": 0.01}
MAX_PER_CLASS = {"water": 3, "foliage": 4, "sky": 1, "person": 3, "cloth": 3, "animal": 3}

# overlay colours (RGB) for the debug picture
COLORS = {
    "water": (40, 130, 255),
    "foliage": (50, 200, 80),
    "sky": (150, 120, 255),
    "person": (255, 120, 80),
    "cloth": (255, 210, 60),
    "animal": (255, 120, 200),
}

CLASS_RU = {"water": "вода", "foliage": "листва/растения", "sky": "небо", "person": "человек", "cloth": "ткань/флаг", "animal": "животное"}


def class_of(label: str) -> str | None:
    synonyms = {s.strip().lower() for s in label.split(",")}
    for cls, words in CLASS_KEYWORDS.items():
        if synonyms & words:
            return cls
    return None
