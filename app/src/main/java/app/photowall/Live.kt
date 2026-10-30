package app.photowall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** One region that moves: what it is, which effect, how strong, and the math for it. */
class LiveLayer(
    val id: String,
    val cls: String,
    val classRu: String,
    val effect: String,
    var enabled: Boolean,
    var strength: Double,
    val confidence: Double,
    val area: Double,
    val note: String,
    val math: LayerMath,
)

class LiveRecipe(val width: Int, val height: Int, val backend: String, val layers: List<LiveLayer>)

/** "Живые" рецепты фото лежат в `.live/<размер>-<время изменения>/` рядом с фото: recipe.json, маски PNG, превью. */
class LiveStore(ctx: Context) {
    private val store = PhotoStore.get(ctx)
    private val idRegex = Regex("[A-Za-z0-9_-]{1,40}")

    fun has(p: Photo) = store.hasLive(p)

    fun load(p: Photo): LiveRecipe? = try {
        val f = File(store.liveDir(p), "recipe.json")
        if (!f.exists()) null else parse(JSONObject(f.readText()))
    } catch (e: Exception) {
        null
    }

    fun parse(o: JSONObject): LiveRecipe {
        val img = o.optJSONObject("image")
        val arr = o.optJSONArray("layers") ?: JSONArray()
        val layers = ArrayList<LiveLayer>()
        for (i in 0 until arr.length()) {
            val l = arr.getJSONObject(i)
            val id = l.optString("id")
            if (!idRegex.matches(id)) continue
            val effect = l.optString("effect")
            layers.add(
                LiveLayer(
                    id, l.optString("class"), l.optString("class_ru"), effect, l.optBoolean("enabled", true), l.optDouble("strength", 1.0),
                    l.optDouble("confidence", 0.0), l.optDouble("area", 0.0), l.optString("note"),
                    LayerMath.fromJson(effect, l.optJSONObject("params"), l.optJSONArray("bbox")),
                )
            )
        }
        return LiveRecipe(img?.optInt("width", 1) ?: 1, img?.optInt("height", 1) ?: 1, o.optString("backend"), layers)
    }

    /** Saves the service's answer: the recipe without the inline masks, one PNG per mask, the preview and overlay pictures. */
    fun save(p: Photo, resp: JSONObject, preview: ByteArray?, overlay: ByteArray?) {
        val dir = store.liveDir(p)
        dir.deleteRecursively()
        dir.mkdirs()
        val src = resp.getJSONArray("layers")
        val clean = JSONArray()
        for (i in 0 until src.length()) {
            val l = JSONObject(src.getJSONObject(i).toString())
            val id = l.optString("id")
            if (!idRegex.matches(id)) continue
            val b64 = l.optString("mask_png_b64")
            if (b64.isEmpty()) continue
            File(dir, "$id.png").writeBytes(Base64.decode(b64, Base64.DEFAULT))
            l.remove("mask_png_b64")
            clean.put(l)
        }
        val recipe = JSONObject().put("version", 1).put("image", resp.getJSONObject("image"))
            .put("backend", resp.optString("backend")).put("layers", clean)
        File(dir, "recipe.json").writeText(recipe.toString())
        preview?.let { File(dir, "preview.gif").writeBytes(it) }
        overlay?.let { File(dir, "overlay.png").writeBytes(it) }
        store.touch()
    }

    /** Applies the user's edits: [patch] is a list of {id, enabled?, strength?}. */
    fun update(p: Photo, patch: JSONArray): Boolean {
        val f = File(store.liveDir(p), "recipe.json")
        if (!f.exists()) return false
        val o = JSONObject(f.readText())
        val layers = o.getJSONArray("layers")
        for (i in 0 until patch.length()) {
            val c = patch.getJSONObject(i)
            for (j in 0 until layers.length()) {
                val l = layers.getJSONObject(j)
                if (l.optString("id") != c.optString("id")) continue
                if (c.has("enabled")) l.put("enabled", c.optBoolean("enabled"))
                if (c.has("strength")) l.put("strength", c.optDouble("strength", 1.0).coerceIn(0.0, 4.0))
            }
        }
        f.writeText(o.toString())
        store.touch()
        return true
    }

    fun delete(p: Photo) {
        store.liveDir(p).deleteRecursively()
        store.touch()
    }

    fun file(p: Photo, name: String): File? = File(store.liveDir(p), name).takeIf { it.exists() }

    /** Builds the per-frame animator for the decoded photo [bmp]; null if the photo has no usable recipe. */
    fun render(p: Photo, bmpW: Int, bmpH: Int): LiveRender? {
        val recipe = load(p) ?: return null
        return LiveRender.build(recipe, store.liveDir(p), bmpW, bmpH)
    }
}

/**
 * Animates a photo by moving the nodes of a coarse mesh (Canvas.drawBitmapMesh) instead of every pixel: cheap enough for a
 * projector. Node displacement = sum over layers of mask weight * strength * effect math; the border nodes stay put.
 */
class LiveRender private constructor(
    private val layers: List<LiveLayer>,
    val cols: Int,
    val rows: Int,
    private val weights: List<FloatArray>,
    private val active: List<IntArray>,
    private val aspect: Double,
) {
    val verts = FloatArray((cols + 1) * (rows + 1) * 2)
    private val dx = DoubleArray((cols + 1) * (rows + 1))
    private val dy = DoubleArray((cols + 1) * (rows + 1))
    private val tmp = DoubleArray(2)
    private var lastT = -1.0

    /** Recomputes [verts] for time [t] (seconds), at most ~30 times a second. [iw] x [ih] is the drawable's size. */
    fun update(t: Double, gain: Double, iw: Float, ih: Float) {
        if (t - lastT < 0.033 && lastT >= 0) return
        lastT = t
        java.util.Arrays.fill(dx, 0.0)
        java.util.Arrays.fill(dy, 0.0)
        for ((li, layer) in layers.withIndex()) {
            if (!layer.enabled) continue
            val k = layer.strength * gain
            val w = weights[li]
            for (idx in active[li]) {
                val i = idx % (cols + 1)
                val j = idx / (cols + 1)
                layer.math.displace(i.toDouble() / cols, j.toDouble() / rows, t, aspect, tmp)
                val m = w[idx] * k
                dx[idx] += m * tmp[0]
                dy[idx] += m * tmp[1]
            }
        }
        // displacement is in image-width units: x and y both scale with the width
        for (j in 0..rows) for (i in 0..cols) {
            val idx = j * (cols + 1) + i
            verts[idx * 2] = (i * iw / cols + dx[idx] * iw).toFloat()
            verts[idx * 2 + 1] = (j * ih / rows + dy[idx] * iw).toFloat()
        }
    }

    companion object {
        private const val COLS = 128

        fun build(recipe: LiveRecipe, dir: File, bmpW: Int, bmpH: Int): LiveRender? {
            val layers = recipe.layers.filter { File(dir, "${it.id}.png").exists() }
            if (layers.isEmpty() || bmpW <= 0 || bmpH <= 0) return null
            val cols = COLS
            val rows = (COLS.toLong() * bmpH / bmpW).toInt().coerceIn(32, 160)
            val n = (cols + 1) * (rows + 1)
            val weights = ArrayList<FloatArray>()
            val active = ArrayList<IntArray>()
            val px = IntArray(n)
            for (layer in layers) {
                val mask = BitmapFactory.decodeFile(File(dir, "${layer.id}.png").path)
                val w = FloatArray(n)
                if (mask != null) {
                    val scaled = Bitmap.createScaledBitmap(mask, cols + 1, rows + 1, true)
                    scaled.getPixels(px, 0, cols + 1, 0, 0, cols + 1, rows + 1)
                    for (idx in 0 until n) w[idx] = Color.red(px[idx]) / 255f
                    if (scaled !== mask) scaled.recycle()
                    mask.recycle()
                }
                val list = ArrayList<Int>()
                for (idx in 0 until n) {
                    val i = idx % (cols + 1)
                    val j = idx / (cols + 1)
                    val border = i == 0 || j == 0 || i == cols || j == rows
                    if (border) w[idx] = 0f
                    if (w[idx] > 0.01f) list.add(idx)
                }
                weights.add(w)
                active.add(list.toIntArray())
            }
            return LiveRender(layers, cols, rows, weights, active, bmpW.toDouble() / bmpH)
        }
    }
}

/** Talks to the vision service (see vision/README.md). */
object VisionClient {
    fun analyze(baseUrl: String, jpeg: ByteArray): JSONObject {
        val c = open("$baseUrl/analyze", 120_000)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "image/jpeg")
        c.setFixedLengthStreamingMode(jpeg.size)
        c.outputStream.use { it.write(jpeg) }
        if (c.responseCode != 200) throw java.io.IOException("vision service answered ${c.responseCode}")
        return JSONObject(c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
    }

    fun fetch(baseUrl: String, path: String): ByteArray? = try {
        val c = open(baseUrl + path, 60_000)
        if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
    } catch (e: Exception) {
        null
    }

    private fun open(url: String, timeoutMs: Int): HttpURLConnection {
        require(url.startsWith("http://") || url.startsWith("https://")) { "bad vision url" }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = timeoutMs
        }
    }
}
