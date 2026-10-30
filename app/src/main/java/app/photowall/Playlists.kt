package app.photowall

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.UUID

/**
 * A named set of photos: whole [folders] ("" = photos outside any folder) plus individual [photos] (paths).
 * It can have a daily time window so a different playlist plays in the morning and in the evening.
 */
data class Playlist(
    val id: String,
    val name: String,
    val folders: List<String>,
    val photos: List<String>,
    val scheduleEnabled: Boolean,
    val from: Int,
    val to: Int,
)

class Playlists private constructor(private val ctx: Context) {
    private val file = File(ctx.filesDir, "playlists.json")
    private var items: List<Playlist> = load()

    @Synchronized
    fun all(): List<Playlist> = items

    @Synchronized
    fun find(id: String): Playlist? = items.firstOrNull { it.id == id }

    /** Creates the playlist if [p] has no id yet, otherwise replaces the one with that id. */
    @Synchronized
    fun save(p: Playlist): Playlist {
        val saved = p.copy(
            id = p.id.ifBlank { UUID.randomUUID().toString().take(8) },
            name = p.name.trim().ifEmpty { tr("Плейлист", "Playlist") }.take(60),
            from = p.from.coerceIn(0, 1439),
            to = p.to.coerceIn(0, 1439),
        )
        items = if (items.any { it.id == saved.id }) items.map { if (it.id == saved.id) saved else it } else items + saved
        persist()
        return saved
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val had = items.any { it.id == id }
        if (had) {
            items = items.filter { it.id != id }
            persist()
        }
        return had
    }

    @Synchronized
    fun renameFolder(from: String, to: String) = rewrite { it.copy(folders = it.folders.map { f -> if (f == from) to else f }, photos = it.photos.map { p -> if (p.startsWith("$from/")) "$to/${p.removePrefix("$from/")}" else p }) }

    @Synchronized
    fun removeFolder(name: String) = rewrite { it.copy(folders = it.folders.filter { f -> f != name }, photos = it.photos.filter { p -> !p.startsWith("$name/") }) }

    @Synchronized
    fun renamePhotos(map: Map<String, String>) {
        if (map.isNotEmpty()) rewrite { it.copy(photos = it.photos.map { p -> map[p] ?: p }) }
    }

    private fun rewrite(f: (Playlist) -> Playlist) {
        val next = items.map(f)
        if (next != items) {
            items = next
            persist()
        }
    }

    /** The playlist that should play now, or null for "all photos". */
    fun effective(prefs: Prefs): Playlist? {
        val list = all()
        if (prefs.playlistMode == "schedule") {
            val c = Calendar.getInstance()
            val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
            list.firstOrNull { it.scheduleEnabled && inWindow(now, it.from, it.to) }?.let { return it }
        }
        return list.firstOrNull { it.id == prefs.activePlaylist }
    }

    fun effectiveKey(prefs: Prefs): String = effective(prefs)?.id ?: ""

    /** Photo paths of [p] in playing order: its folders first, then its single photos. */
    fun resolve(all: List<Photo>, p: Playlist?): List<String> {
        if (p == null) return all.map { it.name }
        val byFolder = all.groupBy { it.folder }
        val out = LinkedHashSet<String>()
        for (f in p.folders) byFolder[f]?.forEach { out.add(it.name) }
        val known = all.mapTo(HashSet()) { it.name }
        for (n in p.photos) if (n in known) out.add(n)
        return out.toList()
    }

    fun toJson(p: Playlist): JSONObject = JSONObject()
        .put("id", p.id)
        .put("name", p.name)
        .put("folders", JSONArray(p.folders))
        .put("photos", JSONArray(p.photos))
        .put("scheduleEnabled", p.scheduleEnabled)
        .put("from", p.from)
        .put("to", p.to)

    fun fromJson(o: JSONObject): Playlist {
        fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
        return Playlist(
            id = o.optString("id", ""),
            name = o.optString("name", ""),
            folders = strings(o.optJSONArray("folders")).distinct(),
            photos = strings(o.optJSONArray("photos")).distinct(),
            scheduleEnabled = o.optBoolean("scheduleEnabled", false),
            from = o.optInt("from", 8 * 60),
            to = o.optInt("to", 12 * 60),
        )
    }

    private fun persist() {
        val arr = JSONArray()
        items.forEach { arr.put(toJson(it)) }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
        PhotoStore.get(ctx).touch()
    }

    private fun load(): List<Playlist> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        private var instance: Playlists? = null

        @Synchronized
        fun get(context: Context): Playlists = instance ?: Playlists(context.applicationContext).also { instance = it }
    }
}
