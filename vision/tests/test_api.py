import base64
import io

from fastapi.testclient import TestClient
from PIL import Image

from app.main import app
from synth import landscape

client = TestClient(app)


def jpeg_bytes():
    img, _ = landscape()
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=92)
    return buf.getvalue()


def test_health():
    r = client.get("/health")
    assert r.status_code == 200 and r.json()["backend"] == "classical"


def test_analyze_raw_body_returns_layers_masks_and_preview():
    r = client.post("/analyze", content=jpeg_bytes(), headers={"Content-Type": "image/jpeg"})
    assert r.status_code == 200, r.text
    j = r.json()
    assert j["version"] == 1 and j["image"] == {"width": 640, "height": 427}
    assert {l["class"] for l in j["layers"]} >= {"sky", "water", "foliage"}
    mask = Image.open(io.BytesIO(base64.b64decode(j["layers"][0]["mask_png_b64"])))
    assert mask.mode == "L"
    gif = client.get(j["preview_url"])
    assert gif.status_code == 200 and gif.headers["content-type"] == "image/gif" and gif.content[:3] == b"GIF"
    png = client.get(j["overlay_url"])
    assert png.status_code == 200 and png.content[:4] == b"\x89PNG"


def test_analyze_multipart_and_errors():
    r = client.post("/analyze", files={"file": ("a.jpg", jpeg_bytes(), "image/jpeg")})
    assert r.status_code == 200
    assert client.post("/analyze", content=b"").status_code == 400
    assert client.post("/analyze", content=b"not an image").status_code == 415
    assert client.get("/preview/doesnotexist.gif").status_code == 404
