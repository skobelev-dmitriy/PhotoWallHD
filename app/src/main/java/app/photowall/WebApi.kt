package app.photowall

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder

/** Routes for the browser UI that runs on your phone or laptop. */
class WebApi(private val ctx: Context) {
    private val prefs = Prefs(ctx)
    private val store = PhotoStore.get(ctx)
    private val playlists = Playlists.get(ctx)
    private val live = LiveStore(ctx)
    private val pages = HashMap<String, ByteArray>()

    fun handle(req: Request): Response {
        val r = route(req)
        // The sound-sender page may be opened from a file or another origin, so the API allows cross-origin calls.
        return if (req.path.startsWith("/api/")) r.withHeaders(CORS) else r
    }

    private fun route(req: Request): Response {
        val path = req.path
        if (req.method == "OPTIONS") return Response(204, bytes = ByteArray(0))
        if (req.method == "GET" && (path == "/" || path == "/index.html")) return page("index.html")
        if (req.method == "GET" && (path == "/sender" || path == "/sender.html")) return page("sender.html")
        if (path == "/api/ping") return Response.json(JSONObject().put("ok", true).put("pin", prefs.pin.isNotEmpty()))

        val pin = prefs.pin
        if (pin.isNotEmpty() && (req.headers["x-pin"] ?: req.query["pin"]) != pin) return Response.error(401, "pin required")

        return when {
            req.method == "GET" && path == "/api/state" -> state()
            req.method == "GET" && path == "/api/now" -> now()
            req.method == "GET" && path == "/api/frame" -> frame()
            req.method == "GET" && path.startsWith("/api/thumb/") -> thumb(path.removePrefix("/api/thumb/"), req.query["s"])
            req.method == "GET" && path.startsWith("/photo/") -> original(path.removePrefix("/photo/"))
            req.method == "POST" && path == "/api/upload" -> upload(req)
            req.method == "POST" && path == "/api/delete" -> delete(req)
            req.method == "POST" && path == "/api/live/analyze" -> liveAnalyze(req)
            req.method == "GET" && path == "/api/live/recipe" -> liveRecipe(req.query["name"] ?: "")
            req.method == "POST" && path == "/api/live/update" -> liveUpdate(req)
            req.method == "POST" && path == "/api/live/delete" -> liveDelete(req)
            req.method == "GET" && path == "/api/live/preview" -> liveFile(req.query["name"] ?: "", "preview.gif", "image/gif")
            req.method == "GET" && path == "/api/live/overlay" -> liveFile(req.query["name"] ?: "", "overlay.png", "image/png")
            req.method == "POST" && path == "/api/folders" -> folders(req)
            req.method == "POST" && path == "/api/move" -> move(req)
            req.method == "POST" && path == "/api/playlists" -> playlistsApi(req)
            req.method == "POST" && path == "/api/settings" -> settings(req)
            req.method == "POST" && path == "/api/control" -> control(req)
            req.method == "POST" && path == "/api/audio" -> audio(req)
            else -> Response.error(404, "not found")
        }
    }

    private fun page(name: String): Response {
        val html = synchronized(pages) { pages.getOrPut(name) { ctx.assets.open("web/$name").use { it.readBytes() } } }
        return Response(200, "text/html; charset=utf-8", bytes = html, headers = mapOf("Cache-Control" to "no-cache"))
    }

    private fun state(): Response {
        val photos = store.list()
        val arr = JSONArray()
        var dimBudget = 80 // read at most this many unknown image sizes per request; the page asks again every few seconds
        for (p in photos) {
            val o = JSONObject().put("name", p.name).put("folder", p.folder).put("size", p.size).put("modified", p.modified).put("live", store.hasLive(p))
            store.dims(p, dimBudget > 0)?.let { o.put("w", it[0]).put("h", it[1]) } ?: run { dimBudget-- }
            arr.put(o)
        }
        val folderArr = JSONArray()
        val counts = photos.groupingBy { it.folder }.eachCount()
        for (f in store.folders()) {
            val total = counts.entries.sumOf { (k, v) -> if (k == f || k.startsWith("$f/")) v else 0 }
            folderArr.put(JSONObject().put("name", f).put("count", counts[f] ?: 0).put("total", total))
        }
        val plArr = JSONArray()
        for (p in playlists.all()) plArr.put(playlists.toJson(p).put("count", playlists.resolve(photos, p).size))
        val obj = JSONObject()
            .put("photos", arr)
            .put("folders", folderArr)
            .put("rootCount", counts[""] ?: 0)
            .put("playlists", plArr)
            .put("effectivePlaylist", playlists.effectiveKey(prefs))
            .put("settings", prefs.toJson())
            .put("url", Net.url(ServerService.port))
            .put("active", Remote.active)
            .put("mode", Remote.mode)
            .put("totalBytes", photos.sumOf { it.size })
            .put("freeBytes", store.dir.usableSpace)
        return Response.json(obj)
    }

    /** What the projector is showing: the current photo (slideshow) or the current effect (disco). */
    private fun now(): Response {
        val o = JSONObject().put("mode", Remote.mode)
        val show = Remote.view
        val disco = Remote.disco
        if (disco != null) {
            o.put("effect", disco.effectId).put("effectTitle", disco.effectTitle).put("auto", disco.isAuto)
                .put("source", Remote.feed?.activeSource ?: "")
        } else if (show != null) {
            o.put("photo", show.shownName ?: JSONObject.NULL).put("paused", show.isPaused).put("night", show.isNight)
        }
        return Response.json(o)
    }

    /** A small JPEG of the disco screen for the web preview (poll it at a low rate). */
    private fun frame(): Response {
        val jpeg = Remote.snapshot() ?: return Response.error(404, "disco is not on screen")
        return Response(200, "image/jpeg", bytes = jpeg, headers = mapOf("Cache-Control" to "no-store"))
    }

    private fun thumb(name: String, size: String?): Response {
        val photo = store.find(name) ?: return Response.error(404, "no such photo")
        val side = when (size) {
            "1920" -> 1920
            "960" -> 960
            else -> 480
        }
        val f = store.thumb(photo, side) ?: return Response.error(415, "cannot decode")
        return Response(200, "image/jpeg", file = f, headers = mapOf("Cache-Control" to "public, max-age=86400"))
    }

    private fun original(name: String): Response {
        val photo = store.find(name) ?: return Response.error(404, "no such photo")
        val type = when (photo.name.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heic"
            else -> "image/jpeg"
        }
        return Response(200, type, file = photo.file, headers = mapOf("Cache-Control" to "public, max-age=3600"))
    }

    private fun upload(req: Request): Response {
        val raw = req.headers["x-filename"]?.let {
            try {
                URLDecoder.decode(it, "UTF-8")
            } catch (e: IllegalArgumentException) {
                it
            }
        } ?: "photo.jpg"
        val folder = req.headers["x-folder"]?.let {
            try {
                URLDecoder.decode(it, "UTF-8")
            } catch (e: IllegalArgumentException) {
                it
            }
        } ?: ""
        if (req.contentLength <= 0) return Response.error(400, "empty body")
        if (req.contentLength > MAX_UPLOAD) return Response.error(413, "file too large")
        if (store.dir.usableSpace < req.contentLength + 50L * 1024 * 1024) return Response.error(507, "not enough space")
        return try {
            val p = store.save(raw, req.body, req.contentLength, folder)
            Response.json(JSONObject().put("name", p.name).put("folder", p.folder).put("size", p.size))
        } catch (e: IllegalArgumentException) {
            Response.error(415, "unsupported image")
        } catch (e: IOException) {
            Response.error(500, e.message ?: "io error")
        }
    }

    private fun delete(req: Request): Response {
        val names = readJson(req).optJSONArray("names") ?: return Response.error(400, "names required")
        var deleted = 0
        for (i in 0 until names.length()) if (store.delete(names.getString(i))) deleted++
        return Response.json(JSONObject().put("deleted", deleted))
    }

    // ---- "living photos": analysis by the vision service, recipe editing ----

    private fun liveJson(p: Photo): JSONObject {
        val r = live.load(p) ?: return JSONObject().put("exists", false)
        val layers = JSONArray()
        for (l in r.layers) {
            layers.put(
                JSONObject().put("id", l.id).put("class", l.cls).put("class_ru", l.classRu).put("effect", l.effect)
                    .put("enabled", l.enabled).put("strength", l.strength).put("confidence", l.confidence).put("area", l.area).put("note", l.note)
            )
        }
        return JSONObject().put("exists", true).put("backend", r.backend).put("layers", layers)
            .put("hasPreview", live.file(p, "preview.gif") != null).put("hasOverlay", live.file(p, "overlay.png") != null)
    }

    private fun liveAnalyze(req: Request): Response {
        val photo = store.find(readJson(req).optString("name")) ?: return Response.error(404, "no such photo")
        val base = prefs.visionUrl
        if (base.isEmpty()) return Response.error(400, "the vision service address is not set (setting visionUrl)")
        val bmp = BitmapLoader.thumbnail(photo.file, 1024) ?: return Response.error(415, "cannot decode the photo")
        val jpeg = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        bmp.recycle()
        val resp = try {
            VisionClient.analyze(base, jpeg)
        } catch (e: Exception) {
            return Response.error(502, "vision service: ${e.message ?: e.javaClass.simpleName}")
        }
        val preview = resp.optString("preview_url").takeIf { it.isNotEmpty() }?.let { VisionClient.fetch(base, it) }
        val overlay = resp.optString("overlay_url").takeIf { it.isNotEmpty() }?.let { VisionClient.fetch(base, it) }
        live.save(photo, resp, preview, overlay)
        return Response.json(liveJson(photo))
    }

    private fun liveRecipe(name: String): Response {
        val photo = store.find(name) ?: return Response.error(404, "no such photo")
        return Response.json(liveJson(photo))
    }

    private fun liveUpdate(req: Request): Response {
        val j = readJson(req)
        val photo = store.find(j.optString("name")) ?: return Response.error(404, "no such photo")
        val patch = j.optJSONArray("layers") ?: return Response.error(400, "layers required")
        if (!live.update(photo, patch)) return Response.error(404, "the photo has no animation yet")
        return Response.json(liveJson(photo))
    }

    private fun liveDelete(req: Request): Response {
        val photo = store.find(readJson(req).optString("name")) ?: return Response.error(404, "no such photo")
        live.delete(photo)
        return Response.json(JSONObject().put("ok", true))
    }

    private fun liveFile(name: String, file: String, type: String): Response {
        val photo = store.find(name) ?: return Response.error(404, "no such photo")
        val f = live.file(photo, file) ?: return Response.error(404, "not available")
        return Response(200, type, file = f, headers = mapOf("Cache-Control" to "no-store"))
    }

    private fun folders(req: Request): Response {
        val j = readJson(req)
        val name = j.optString("name")
        val result = JSONObject().put("ok", true)
        try {
            when (j.optString("action")) {
                "create" -> result.put("path", store.createFolder(name))
                "rename" -> {
                    val to = store.renameFolder(name, j.optString("to"))
                    playlists.renameFolder(name, to)
                    result.put("path", to)
                }
                "move" -> {
                    val to = store.moveFolder(name, j.optString("to"))
                    playlists.renameFolder(name, to)
                    result.put("path", to)
                }
                "delete" -> {
                    store.deleteFolder(name, j.optBoolean("deletePhotos", false))
                    playlists.removeFolder(name)
                }
                else -> return Response.error(400, "unknown action")
            }
        } catch (e: IllegalArgumentException) {
            return Response.error(400, e.message ?: "bad request")
        }
        return Response.json(result)
    }

    private fun move(req: Request): Response {
        val j = readJson(req)
        val arr = j.optJSONArray("names") ?: return Response.error(400, "names required")
        val names = (0 until arr.length()).map { arr.getString(it) }
        val moved = store.move(names, j.optString("folder", ""))
        playlists.renamePhotos(moved)
        return Response.json(JSONObject().put("moved", moved.size))
    }

    private fun playlistsApi(req: Request): Response {
        val j = readJson(req)
        when (j.optString("action")) {
            "save" -> {
                val body = j.optJSONObject("playlist") ?: return Response.error(400, "playlist required")
                val known = store.folders().toSet()
                val p = playlists.fromJson(body)
                return Response.json(playlists.toJson(playlists.save(p.copy(folders = p.folders.filter { it.isEmpty() || it in known }))))
            }
            "delete" -> playlists.delete(j.optString("id"))
            "play" -> {
                val id = j.optString("id", "")
                if (id.isNotEmpty() && playlists.find(id) == null) return Response.error(404, "no such playlist")
                prefs.activePlaylist = id
                prefs.playlistMode = "manual"
                Remote.send("next")
            }
            else -> return Response.error(400, "unknown action")
        }
        return Response.json(JSONObject().put("ok", true))
    }

    private fun settings(req: Request): Response {
        prefs.update(readJson(req))
        return Response.json(prefs.toJson())
    }

    private fun control(req: Request): Response {
        when (val action = readJson(req).optString("action")) {
            "start" -> ServerService.launchSlideshow(ctx)
            "disco" -> ServerService.launchDisco(ctx)
            "next", "prev", "pause", "resume", "toggle", "dnext", "dprev", "stop" -> Remote.send(action)
            else -> return Response.error(400, "unknown action")
        }
        return Response.json(JSONObject().put("ok", true))
    }

    /** A frame of analysed sound from a browser (see sender.html); feeds the disco screen if it is open. */
    private fun audio(req: Request): Response {
        val feed = Remote.feed
        if (req.contentLength in 1..64 * 1024) {
            val j = readJson(req)
            feed?.pushRemote(j.optJSONArray("b"), j.optJSONArray("w"), j.optDouble("r", 1.0))
        }
        return Response.json(JSONObject().put("ok", true).put("mode", Remote.mode).put("source", feed?.activeSource ?: ""))
    }

    private fun readJson(req: Request): JSONObject {
        if (req.contentLength <= 0 || req.contentLength > 1024 * 1024) return JSONObject()
        return JSONObject(String(req.body.readBytes(), Charsets.UTF_8))
    }

    private companion object {
        val CORS = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers" to "Content-Type, X-Pin, X-Filename",
            "Access-Control-Max-Age" to "600",
        )
        const val MAX_UPLOAD = 300L * 1024 * 1024
    }
}
