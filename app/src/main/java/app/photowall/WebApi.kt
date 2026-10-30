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
    private var indexHtml: ByteArray? = null

    fun handle(req: Request): Response {
        val path = req.path
        if (req.method == "GET" && (path == "/" || path == "/index.html")) return page()
        if (path == "/api/ping") return Response.json(JSONObject().put("ok", true).put("pin", prefs.pin.isNotEmpty()))

        val pin = prefs.pin
        if (pin.isNotEmpty() && (req.headers["x-pin"] ?: req.query["pin"]) != pin) return Response.error(401, "pin required")

        return when {
            req.method == "GET" && path == "/api/state" -> state()
            req.method == "GET" && path.startsWith("/api/thumb/") -> thumb(path.removePrefix("/api/thumb/"))
            req.method == "GET" && path.startsWith("/photo/") -> original(path.removePrefix("/photo/"))
            req.method == "POST" && path == "/api/upload" -> upload(req)
            req.method == "POST" && path == "/api/delete" -> delete(req)
            req.method == "POST" && path == "/api/settings" -> settings(req)
            req.method == "POST" && path == "/api/control" -> control(req)
            else -> Response.error(404, "not found")
        }
    }

    private fun page(): Response {
        val html = indexHtml ?: ctx.assets.open("web/index.html").use { it.readBytes() }.also { indexHtml = it }
        return Response(200, "text/html; charset=utf-8", bytes = html, headers = mapOf("Cache-Control" to "no-cache"))
    }

    private fun state(): Response {
        val photos = store.list()
        val arr = JSONArray()
        for (p in photos) arr.put(JSONObject().put("name", p.name).put("size", p.size).put("modified", p.modified))
        val obj = JSONObject()
            .put("photos", arr)
            .put("settings", prefs.toJson())
            .put("url", Net.url(ServerService.port))
            .put("active", Remote.active)
            .put("totalBytes", photos.sumOf { it.size })
            .put("freeBytes", store.dir.usableSpace)
        return Response.json(obj)
    }

    private fun thumb(name: String): Response {
        val photo = store.find(name) ?: return Response.error(404, "no such photo")
        val f = store.thumb(photo) ?: return Response.error(415, "cannot decode")
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
        if (req.contentLength <= 0) return Response.error(400, "empty body")
        if (req.contentLength > MAX_UPLOAD) return Response.error(413, "file too large")
        if (store.dir.usableSpace < req.contentLength + 50L * 1024 * 1024) return Response.error(507, "not enough space")
        return try {
            val p = store.save(raw, req.body, req.contentLength)
            Response.json(JSONObject().put("name", p.name).put("size", p.size))
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

    private fun settings(req: Request): Response {
        prefs.update(readJson(req))
        return Response.json(prefs.toJson())
    }

    private fun control(req: Request): Response {
        when (val action = readJson(req).optString("action")) {
            "start" -> ServerService.launchSlideshow(ctx)
            "disco" -> ServerService.launchDisco(ctx)
            "next", "prev", "pause", "resume", "toggle", "dnext", "dprev" -> Remote.send(action)
            else -> return Response.error(400, "unknown action")
        }
        return Response.json(JSONObject().put("ok", true))
    }

    private fun readJson(req: Request): JSONObject {
        if (req.contentLength <= 0 || req.contentLength > 1024 * 1024) return JSONObject()
        return JSONObject(String(req.body.readBytes(), Charsets.UTF_8))
    }

    private companion object {
        const val MAX_UPLOAD = 300L * 1024 * 1024
    }
}
