package app.photowall

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** A saved filmstrip ("диафильм"): ordered frames in one folder plus a manifest. */
data class Filmstrip(
    val id: String,
    val title: String,
    val frames: List<String>,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val addedAt: Long,
    val source: String,
    val credits: String,
)

/**
 * The "saved" filmstrips on the device. They are imported from .cbz archives (a zip of frame images, optionally with
 * ComicInfo.xml), later also downloaded from a catalog. Kept apart from the photos so they never mix into the slideshow.
 */
class FilmstripStore private constructor(context: Context) {
    val root: File = (context.getExternalFilesDir("filmstrips") ?: File(context.filesDir, "filmstrips")).also { it.mkdirs() }

    /** Newest first. */
    fun list(): List<Filmstrip> = (root.listFiles() ?: emptyArray())
        .filter { it.isDirectory && File(it, "manifest.json").exists() }
        .mapNotNull { read(it) }
        .sortedByDescending { it.addedAt }

    fun get(id: String): Filmstrip? = if (ID_REGEX.matches(id)) read(File(root, id)) else null

    /** The file of frame [n] (0-based), or null if there is no such frame. */
    fun frameFile(id: String, n: Int): File? {
        val f = get(id) ?: return null
        val name = f.frames.getOrNull(n) ?: return null
        return File(File(root, id), name).takeIf { it.exists() }
    }

    @Synchronized
    fun rename(id: String, title: String): Boolean {
        val dir = File(root, id)
        if (!ID_REGEX.matches(id) || !File(dir, "manifest.json").exists()) return false
        val clean = cleanTitle(title)
        if (clean.isEmpty()) return false
        val o = JSONObject(File(dir, "manifest.json").readText())
        o.put("title", clean)
        File(dir, "manifest.json").writeText(o.toString())
        return true
    }

    @Synchronized
    fun delete(id: String): Boolean = ID_REGEX.matches(id) && File(root, id).let { it.isDirectory && it.deleteRecursively() }

    /**
     * Imports a .cbz / .zip. Frame names inside the archive are never used as paths (frames are written as 0001.jpg, ...),
     * so a hostile archive cannot write outside the folder; sizes and counts are limited. Throws
     * [IllegalArgumentException] for archives that are not usable. [dims] reads the pixel size of the first frame.
     */
    @Synchronized
    fun import(zip: File, originalName: String, dims: (File) -> IntArray? = { BitmapLoader.displaySize(it) }): Filmstrip {
        val zf = try {
            ZipFile(zip)
        } catch (e: IOException) {
            throw IllegalArgumentException("not a zip archive")
        }
        zf.use {
            val all = it.entries().toList().filter { e -> !e.isDirectory }
            require(all.size <= MAX_ENTRIES) { "too many files in the archive" }
            require(all.sumOf { e -> maxOf(e.size, 0L) } <= MAX_TOTAL) { "the archive is too large when unpacked" }
            val images = all.filter { e -> isFrame(e) }.sortedWith { a, b -> naturalCompare(a.name, b.name) }
            require(images.isNotEmpty()) { "no images in the archive" }

            val id = UUID.randomUUID().toString().replace("-", "").take(8)
            val dir = File(root, id).also { d -> d.mkdirs() }
            try {
                val frames = ArrayList<String>()
                var bytes = 0L
                images.forEachIndexed { i, e ->
                    val ext = e.name.substringAfterLast('.').lowercase(Locale.ROOT).let { x -> if (x == "jpeg") "jpg" else x }
                    val name = String.format(Locale.ROOT, "%04d.%s", i + 1, ext)
                    val out = File(dir, name)
                    out.outputStream().buffered().use { o -> bytes += copyBounded(it.getInputStream(e), o, MAX_ENTRY) }
                    frames.add(name)
                }
                val comic = all.firstOrNull { e -> e.name.substringAfterLast('/').equals("ComicInfo.xml", ignoreCase = true) }
                    ?.let { e -> readText(it, e) }
                val info = all.firstOrNull { e -> e.name.substringAfterLast('/').equals("info.json", ignoreCase = true) }
                    ?.let { e -> readText(it, e) }
                val meta = parseMeta(comic, info)
                val title = cleanTitle(meta.title).ifEmpty { titleFromFileName(originalName) }
                val size = dims(File(dir, frames[0]))
                val manifest = JSONObject()
                    .put("version", 1).put("id", id).put("title", title).put("frames", JSONArray(frames))
                    .put("width", size?.get(0) ?: 0).put("height", size?.get(1) ?: 0).put("bytes", bytes)
                    .put("addedAt", System.currentTimeMillis()).put("source", originalName.take(120)).put("credits", meta.credits)
                File(dir, "manifest.json").writeText(manifest.toString())
                return read(dir) ?: throw IOException("cannot read the manifest back")
            } catch (e: Exception) {
                dir.deleteRecursively()
                throw e
            }
        }
    }

    private fun read(dir: File): Filmstrip? = try {
        val o = JSONObject(File(dir, "manifest.json").readText())
        val arr = o.getJSONArray("frames")
        Filmstrip(
            o.getString("id"), o.optString("title"), (0 until arr.length()).map { arr.getString(it) },
            o.optInt("width"), o.optInt("height"), o.optLong("bytes"), o.optLong("addedAt"), o.optString("source"), o.optString("credits"),
        )
    } catch (e: Exception) {
        null
    }

    private fun isFrame(e: ZipEntry): Boolean {
        val base = e.name.substringAfterLast('/')
        if (e.name.startsWith("__MACOSX") || base.startsWith(".")) return false
        return base.substringAfterLast('.', "").lowercase(Locale.ROOT) in IMAGE_EXTENSIONS
    }

    private fun readText(zf: ZipFile, e: ZipEntry): String? = try {
        val out = java.io.ByteArrayOutputStream()
        copyBounded(zf.getInputStream(e), out, 1024 * 1024)
        String(out.toByteArray(), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    /** Copies at most [limit] bytes; the real size counts, not the one claimed in the zip header. */
    private fun copyBounded(input: InputStream, out: OutputStream, limit: Long): Long {
        input.use {
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                total += n
                require(total <= limit) { "a file in the archive is too large" }
                out.write(buf, 0, n)
            }
            return total
        }
    }

    class Meta(val title: String, val credits: String)

    /** Title and credits from ComicInfo.xml (simple tag extraction, no XML parser: nothing external is ever resolved) or info.json. */
    fun parseMeta(comicInfo: String?, infoJson: String?): Meta {
        var title = ""
        val credits = ArrayList<String>()
        if (comicInfo != null) {
            fun tag(name: String) = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(comicInfo)?.groupValues?.get(1)?.let { unescape(it).trim() } ?: ""
            title = tag("Title").takeUnless { it.equals("Unknown", ignoreCase = true) } ?: ""
            if (title.isEmpty()) title = tag("Series").takeUnless { it.equals("Unknown", ignoreCase = true) } ?: ""
            for (t in listOf("Writer", "Penciller", "Publisher", "Year")) {
                val v = tag(t)
                if (v.isNotEmpty() && !v.equals("Unknown", ignoreCase = true)) credits.add("$t: $v")
            }
        }
        if (infoJson != null) {
            try {
                val o = JSONObject(infoJson)
                if (title.isEmpty()) title = o.optString("title")
                val author = o.optString("author")
                if (author.isNotEmpty() && !author.equals("Unknown", ignoreCase = true)) credits.add("author: $author")
            } catch (e: Exception) {
                // not a Stripa info.json: ignore
            }
        }
        return Meta(title, credits.joinToString("; "))
    }

    companion object {
        private val ID_REGEX = Regex("[0-9a-f]{8}")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        const val MAX_ENTRIES = 1000
        const val MAX_ENTRY = 40L * 1024 * 1024
        const val MAX_TOTAL = 600L * 1024 * 1024

        private var instance: FilmstripStore? = null

        @Synchronized
        fun get(context: Context): FilmstripStore = instance ?: FilmstripStore(context.applicationContext).also { instance = it }

        fun cleanTitle(raw: String): String = raw.filter { it >= ' ' && it != '\u007f' }.trim().take(120)

        /** "film_8126.cbz" -> "Диафильм 8126"; anything else keeps its name without the extension. */
        fun titleFromFileName(name: String): String {
            val base = name.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
            Regex("(?:.*[-_ ])?film[_-]?(\\d+)", RegexOption.IGNORE_CASE).matchEntire(base)?.let { return "Диафильм ${it.groupValues[1]}" }
            return cleanTitle(base.replace('_', ' ')).ifEmpty { "Диафильм" }
        }

        private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

        /** Orders "2.jpg" before "10.jpg" (digit runs compare as numbers). */
        fun naturalCompare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                if (a[i].isDigit() && b[j].isDigit()) {
                    var ei = i
                    while (ei < a.length && a[ei].isDigit()) ei++
                    var ej = j
                    while (ej < b.length && b[ej].isDigit()) ej++
                    val na = a.substring(i, ei).trimStart('0')
                    val nb = b.substring(j, ej).trimStart('0')
                    if (na.length != nb.length) return na.length - nb.length
                    val c = na.compareTo(nb)
                    if (c != 0) return c
                    i = ei
                    j = ej
                } else {
                    val c = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                    if (c != 0) return c
                    i++
                    j++
                }
            }
            return (a.length - i) - (b.length - j)
        }
    }
}
