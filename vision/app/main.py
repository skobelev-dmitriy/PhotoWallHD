"""Vision service: send a photo, get back regions (masks), what each is, and recommended motion effects."""
from __future__ import annotations

import hashlib
import logging
import os
import time
from collections import OrderedDict

from fastapi import FastAPI, File, HTTPException, Query, Request, UploadFile
from fastapi.responses import HTMLResponse, Response

from .analyze import analyze, load_image, overlay_png, recipe_with_masks
from .backends import get_backend
from .effects import render_preview_gif

log = logging.getLogger("vision")
app = FastAPI(title="PhotoWall vision", version="1.0.0")

_backend = None
_cache: "OrderedDict[str, dict]" = OrderedDict()  # id -> {image, work, recipe, masks}; small LRU for previews
_CACHE_SIZE = 16
_gif_cache: "OrderedDict[tuple, bytes]" = OrderedDict()


def backend():
    global _backend
    if _backend is None:
        _backend = get_backend()
        log.info("backend: %s", _backend.name)
    return _backend


@app.get("/health")
def health():
    b = backend()
    return {"ok": True, "backend": b.name, "model": getattr(b, "model_id", None)}


@app.post("/analyze")
async def analyze_photo(request: Request, file: UploadFile | None = File(None), preview: bool = Query(True)):
    """Body: the image bytes (any Content-Type except multipart) or a multipart form with a `file` field."""
    data = await file.read() if file is not None else await request.body()
    if not data:
        raise HTTPException(400, "empty body")
    if len(data) > 60 * 1024 * 1024:
        raise HTTPException(413, "image too large")
    try:
        image = load_image(data)
    except Exception:
        raise HTTPException(415, "not an image")

    t0 = time.time()
    recipe, masks, work = analyze(image, backend())
    key = hashlib.sha1(data).hexdigest()[:12]
    _cache[key] = {"image": work, "recipe": recipe, "masks": masks}
    _cache.move_to_end(key)
    while len(_cache) > _CACHE_SIZE:
        _cache.popitem(last=False)
    out = recipe_with_masks(recipe, masks)
    out["id"] = key
    out["seconds"] = round(time.time() - t0, 2)
    if preview:
        out["preview_url"] = f"/preview/{key}.gif"
    out["overlay_url"] = f"/overlay/{key}.png"
    return out


def _entry(key: str) -> dict:
    e = _cache.get(key)
    if e is None:
        raise HTTPException(404, "unknown or expired id: analyze the photo again")
    return e


@app.get("/preview/{key}.gif")
def preview(key: str, gain: float = Query(3.0, ge=0.5, le=10.0), width: int = Query(640, ge=160, le=1280)):
    e = _entry(key)
    ck = (key, round(gain, 2), width)
    gif = _gif_cache.get(ck)
    if gif is None:
        gif = render_preview_gif(e["image"], e["recipe"]["layers"], e["masks"], width=width, gain=gain)
        _gif_cache[ck] = gif
        while len(_gif_cache) > 24:
            _gif_cache.popitem(last=False)
    return Response(gif, media_type="image/gif")


@app.get("/overlay/{key}.png")
def overlay(key: str):
    e = _entry(key)
    return Response(overlay_png(e["image"], e["recipe"], e["masks"]), media_type="image/png")


@app.get("/", response_class=HTMLResponse)
def index():
    return """<!doctype html><meta charset=utf-8><title>PhotoWall vision</title>
<body style="font:16px system-ui;background:#12151d;color:#e8ebf3;max-width:900px;margin:20px auto;padding:0 16px">
<h1>PhotoWall vision</h1><p>Выберите фото: сервис найдёт области (вода, листва, небо, человек …), покажет границы и анимированный предпросмотр.</p>
<input type=file id=f accept="image/*"> <button id=go>Анализировать</button> <span id=st></span>
<div id=out></div>
<script>
const $=s=>document.querySelector(s);
$('#go').onclick=async()=>{const f=$('#f').files[0]; if(!f) return; $('#st').textContent='…анализирую'; 
 const r=await fetch('/analyze',{method:'POST',body:f}); if(!r.ok){$('#st').textContent='ошибка '+r.status;return}
 const j=await r.json(); $('#st').textContent=j.backend+' · '+j.seconds+' c';
 $('#out').innerHTML='<h3>Что увидела модель</h3><img style="max-width:100%" src="'+j.overlay_url+'">'+
 '<h3>Предпросмотр движения (×3)</h3><img style="max-width:100%" src="'+j.preview_url+'">'+
 '<h3>Рекомендации</h3>'+j.layers.map(l=>'<p><b>'+l.id+'</b> ('+l.class_ru+', '+Math.round(l.area*100)+'% кадра): '+l.effect+' '+JSON.stringify(l.params)+'<br><i>'+l.note+'</i></p>').join('');};
</script>"""
