package app.photowall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale

data class Photo(val name: String, val size: Long, val modified: Long, val file: File)

/** The folder of photos shown on the wall, plus a cache of small previews for the web UI. */
class PhotoStore private constructor(context: Context) {
    val dir: File = (context.getExternalFilesDir("photos") ?: File(context.filesDir, "photos")).also { it.mkdirs() }
    private val thumbDir = File(context.cacheDir, "thumbs").also { it.mkdirs() }

    /** Bumped whenever the set of photos changes through this app. */
    @Volatile
    var version = 0
        private set

    fun list(): List<Photo> = dir.listFiles()
        ?.filter { it.isFile && !it.name.startsWith(".") && isImage(it.name) }
        ?.map { Photo(it.name, it.length(), it.lastModified(), it) }
        ?.sortedWith(compareBy<Photo> { it.modified }.thenBy { it.name })
        ?: emptyList()

    fun find(name: String): Photo? = list().firstOrNull { it.name == name }

    /** Stores an uploaded image. Throws [IllegalArgumentException] if it is not a supported image. */
    fun save(rawName: String, input: InputStream, expectedBytes: Long): Photo {
        val clean = sanitize(rawName)
        val ext = clean.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(ext in EXTENSIONS) { "unsupported extension" }

        val tmp = File(dir, ".upload-${System.nanoTime()}.part")
        try {
            val copied = tmp.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
            if (copied != expectedBytes) throw IOException("incomplete upload")

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not a decodable image" }

            synchronized(this) {
                val base = clean.substringBeforeLast('.')
                var target = File(dir, clean)
                var n = 1
                while (target.exists()) {
                    target = File(dir, "$base ($n).$ext")
                    n++
                }
                if (!tmp.renameTo(target)) throw IOException("rename failed")
                version++
                return Photo(target.name, target.length(), target.lastModified(), target)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    @Synchronized
    fun delete(name: String): Boolean {
        val photo = find(name) ?: return false
        val ok = photo.file.delete()
        thumbDir.listFiles { f -> f.name.startsWith("${name.hashCode()}_") }?.forEach { it.delete() }
        if (ok) version++
        return ok
    }

    fun thumb(photo: Photo): File? {
        val f = File(thumbDir, "${photo.name.hashCode()}_${photo.modified}_${photo.size}.jpg")
        if (f.exists()) return f
        synchronized(thumbLock) {
            if (f.exists()) return f
            val bmp = BitmapLoader.thumbnail(photo.file, 480) ?: return null
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

    private fun sanitize(raw: String): String {
        var n = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        n = n.replace(Regex("[^\\p{L}\\p{N}._ ()\\-]"), "_").trimStart('.')
        if (n.length > 120) {
            val ext = n.substringAfterLast('.', "")
            n = n.substring(0, 100) + (if (ext.isNotEmpty()) ".$ext" else "")
        }
        return n.ifEmpty { "photo.jpg" }
    }

    companion object {
        val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")
        private val thumbLock = Any()
        private var instance: PhotoStore? = null

        @Synchronized
        fun get(context: Context): PhotoStore =
            instance ?: PhotoStore(context.applicationContext).also { instance = it }

        fun isImage(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in EXTENSIONS
    }
}
