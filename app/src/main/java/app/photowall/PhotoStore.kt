package app.photowall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/**
 * [name] is the path relative to the photos folder and the photo's id everywhere ("a.jpg" or "Trip/day1/a.jpg");
 * [folder] is its folder path ("Trip/day1"), or "" for photos that are not in any folder.
 */
data class Photo(val name: String, val folder: String, val size: Long, val modified: Long, val file: File)

/** The photos folder (folders may be nested up to [MAX_DEPTH] levels) plus a cache of small previews for the web UI. */
class PhotoStore private constructor(context: Context) {
    val dir: File = (context.getExternalFilesDir("photos") ?: File(context.filesDir, "photos")).also { it.mkdirs() }
    private val thumbDir = File(context.cacheDir, "thumbs").also { it.mkdirs() }

    /** Bumped whenever photos, folders or playlists change through this app. */
    @Volatile
    var version = 0
        private set

    private var cache: List<Photo> = emptyList()
    private var cacheAt = 0L

    private fun nowMs() = System.nanoTime() / 1_000_000

    @Synchronized
    fun touch() {
        version++
        cacheAt = 0L
    }

    /** All photos: those outside folders first, then each folder in tree order; oldest first inside each group. */
    fun list(): List<Photo> {
        synchronized(this) {
            if (cacheAt != 0L && nowMs() - cacheAt < 2000) return cache
        }
        val out = ArrayList<Photo>()
        collect(dir, "", out)
        for (f in folders()) collect(File(dir, f), f, out)
        synchronized(this) {
            cache = out
            cacheAt = nowMs()
        }
        return out
    }

    /** Paths of all folders ("A", "A/B", ...), parents before children, alphabetical. */
    fun folders(): List<String> {
        val out = ArrayList<String>()
        walk(dir, "", 0, out)
        return out
    }

    private fun walk(d: File, prefix: String, depth: Int, out: MutableList<String>) {
        if (depth >= MAX_DEPTH) return
        d.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase(Locale.ROOT) }
            ?.forEach {
                val path = if (prefix.isEmpty()) it.name else "$prefix/${it.name}"
                out.add(path)
                walk(it, path, depth + 1, out)
            }
    }

    private fun collect(d: File, folder: String, out: MutableList<Photo>) {
        d.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") && isImage(it.name) }
            ?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            ?.forEach { out.add(Photo(if (folder.isEmpty()) it.name else "$folder/${it.name}", folder, it.length(), it.lastModified(), it)) }
    }

    fun find(name: String): Photo? = list().firstOrNull { it.name == name }

    /**
     * Stores an uploaded image, into [folder] if given (created if needed).
     * Throws [IllegalArgumentException] if it is not a supported image.
     */
    fun save(rawName: String, input: InputStream, expectedBytes: Long, folder: String = ""): Photo {
        val clean = sanitize(rawName)
        val ext = clean.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(ext in EXTENSIONS) { "unsupported extension" }
        val folderName = sanitizeFolderPath(folder)
        val targetDir = if (folderName.isEmpty()) dir else File(dir, folderName).also { it.mkdirs() }

        val tmp = File(targetDir, ".upload-${System.nanoTime()}.part")
        try {
            val copied = tmp.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
            if (copied != expectedBytes) throw IOException("incomplete upload")

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not a decodable image" }

            synchronized(this) {
                val target = uniqueFile(targetDir, clean)
                if (!tmp.renameTo(target)) throw IOException("rename failed")
                touch()
                return Photo(if (folderName.isEmpty()) target.name else "$folderName/${target.name}", folderName, target.length(), target.lastModified(), target)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Where this photo's "live" recipe lives. Keyed by size and modified time, so it survives moves and folder renames. */
    fun liveDir(p: Photo) = File(File(dir, ".live"), "${p.size}-${p.modified}")

    fun hasLive(p: Photo) = File(liveDir(p), "recipe.json").exists()

    @Synchronized
    fun delete(name: String): Boolean {
        val photo = find(name) ?: return false
        val ok = photo.file.delete()
        liveDir(photo).deleteRecursively()
        clearThumbs(name)
        if (ok) touch()
        return ok
    }

    // ---- folders ----

    /** Creates a folder (or a whole path "A/B/C") and returns its sanitized path. */
    @Synchronized
    fun createFolder(raw: String): String {
        val path = sanitizeFolderPath(raw)
        require(path.isNotEmpty()) { "empty folder name" }
        File(dir, path).mkdirs()
        touch()
        return path
    }

    /** Renames the last segment of [from]; returns the new path. */
    @Synchronized
    fun renameFolder(from: String, toRaw: String): String {
        require(from in folders()) { "no such folder" }
        val name = sanitizeSegment(toRaw.substringAfterLast('/'))
        require(name.isNotEmpty()) { "empty folder name" }
        val to = joinPath(from.substringBeforeLast('/', ""), name)
        if (to == from) return to
        require(!File(dir, to).exists()) { "folder already exists" }
        require(File(dir, from).renameTo(File(dir, to))) { "cannot rename" }
        touch()
        return to
    }

    /** Moves the folder [from] (with everything in it) under [destParentRaw] ("" = top level); returns the new path. */
    @Synchronized
    fun moveFolder(from: String, destParentRaw: String): String {
        val all = folders()
        require(from in all) { "no such folder" }
        val dest = sanitizeFolderPath(destParentRaw)
        require(dest.isEmpty() || dest in all) { "no such destination" }
        require(dest != from && !dest.startsWith("$from/")) { "cannot move a folder into itself" }
        val to = joinPath(dest, from.substringAfterLast('/'))
        if (to == from) return to
        require(!File(dir, to).exists()) { "folder already exists there" }
        require(depthOf(dest) + 1 + subtreeHeight(from) <= MAX_DEPTH) { "folders are nested too deeply" }
        require(File(dir, from).renameTo(File(dir, to))) { "cannot move" }
        touch()
        return to
    }

    /** Photos inside (including sub-folders) are deleted, or moved out to the top level when [deletePhotos] is false. */
    @Synchronized
    fun deleteFolder(name: String, deletePhotos: Boolean) {
        require(name in folders()) { "no such folder" }
        if (!deletePhotos) {
            for (p in list()) {
                if (p.folder == name || p.folder.startsWith("$name/")) p.file.renameTo(uniqueFile(dir, p.file.name))
            }
        } else {
            for (p in list()) {
                if (p.folder == name || p.folder.startsWith("$name/")) clearThumbs(p.name)
            }
        }
        File(dir, name).deleteRecursively()
        touch()
    }

    /** Moves photos into [folderRaw] ("" = out of any folder). Returns old name -> new name for every moved photo. */
    @Synchronized
    fun move(names: List<String>, folderRaw: String): Map<String, String> {
        val folder = sanitizeFolderPath(folderRaw)
        val destDir = if (folder.isEmpty()) dir else File(dir, folder).also { it.mkdirs() }
        val byName = list().associateBy { it.name }
        val moved = LinkedHashMap<String, String>()
        for (n in names) {
            val p = byName[n] ?: continue
            if (p.folder == folder) continue
            val target = uniqueFile(destDir, p.file.name)
            if (p.file.renameTo(target)) {
                clearThumbs(n)
                moved[n] = joinPath(folder, target.name)
            }
        }
        touch()
        return moved
    }

    private fun depthOf(path: String) = if (path.isEmpty()) 0 else path.count { it == '/' } + 1

    private fun subtreeHeight(path: String): Int =
        folders().filter { it.startsWith("$path/") }.maxOfOrNull { depthOf(it) - depthOf(path) } ?: 0

    // ---- image sizes (shown under the thumbnails) ----

    private val dimCache = java.util.concurrent.ConcurrentHashMap<String, IntArray>()

    /** Pixel size [w, h] as displayed (EXIF-rotated); read from the file once, null if not yet known and [compute] is false. */
    fun dims(p: Photo, compute: Boolean): IntArray? {
        val key = "${p.name}|${p.modified}|${p.size}"
        dimCache[key]?.let { return it }
        if (!compute) return null
        val d = BitmapLoader.displaySize(p.file) ?: return null
        dimCache[key] = d
        return d
    }

    // ---- previews ----

    fun thumb(photo: Photo, side: Int = 480): File? {
        val f = File(thumbDir, "${photo.name.hashCode()}_${photo.modified}_${photo.size}_$side.jpg")
        if (f.exists()) return f
        synchronized(thumbLock) {
            if (f.exists()) return f
            val bmp = BitmapLoader.thumbnail(photo.file, side) ?: return null
            val tmp = File(thumbDir, f.name + ".tmp")
            try {
                FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                tmp.renameTo(f)
            } finally {
                bmp.recycle()
                if (tmp.exists()) tmp.delete()
            }
            return if (f.exists()) f else null
        }
    }

    private fun clearThumbs(name: String) {
        thumbDir.listFiles { f -> f.name.startsWith("${name.hashCode()}_") }?.forEach { it.delete() }
    }

    private fun uniqueFile(targetDir: File, name: String): File {
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var target = File(targetDir, name)
        var n = 1
        while (target.exists()) {
            target = File(targetDir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext")
            n++
        }
        return target
    }

    private fun sanitize(raw: String): String {
        var n = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        n = n.replace(Regex("[^\\p{L}\\p{N}._ ()\\-]"), "_").trimStart('.')
        if (n.length > 120) {
            val ext = n.substringAfterLast('.', "")
            n = n.substring(0, 100) + (if (ext.isNotEmpty()) ".$ext" else "")
        }
        return n.ifEmpty { "photo.jpg" }
    }

    /** One path segment: no slashes, no leading/trailing dots or spaces, at most 60 characters. */
    private fun sanitizeSegment(raw: String): String =
        raw.trim().replace(Regex("[^\\p{L}\\p{N}._ ()\\-]"), "_").trim('.', ' ').take(60)

    /** "A/B/C" with every segment sanitized, empty segments dropped, at most [MAX_DEPTH] levels. */
    fun sanitizeFolderPath(raw: String): String =
        raw.split('/', '\\').map { sanitizeSegment(it) }.filter { it.isNotEmpty() }.take(MAX_DEPTH).joinToString("/")

    companion object {
        /** Folders may be nested this deep ("A/B/C/D/E/F"). */
        const val MAX_DEPTH = 6

        fun joinPath(a: String, b: String) = if (a.isEmpty()) b else "$a/$b"

        val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")
        private val thumbLock = Any()
        private var instance: PhotoStore? = null

        @Synchronized
        fun get(context: Context): PhotoStore =
            instance ?: PhotoStore(context.applicationContext).also { instance = it }

        fun isImage(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in EXTENSIONS
    }
}
