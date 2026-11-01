import android.content.ContextWrapper
import app.photowall.PhotoStore
import app.photowall.Playlist
import app.photowall.Playlists
import java.io.File

class FakeCtx(private val root: File) : ContextWrapper(null) {
    override fun getApplicationContext() = this
    override fun getExternalFilesDir(type: String?) = File(root, "ext").also { it.mkdirs() }
    override fun getFilesDir() = File(root, "files").also { it.mkdirs() }
    override fun getCacheDir() = File(root, "cache").also { it.mkdirs() }
}

fun touch(f: File, ts: Long) { f.parentFile.mkdirs(); f.writeText("x"); f.setLastModified(ts) }
fun <T> eq(actual: T, expected: T, what: String) { check(actual == expected) { "$what: expected $expected but was $actual" } }
inline fun fails(what: String, block: () -> Unit) { try { block() } catch (e: IllegalArgumentException) { return }; error("$what: expected IllegalArgumentException") }

fun main() {
    val root = File.createTempFile("pwtest", "").also { it.delete(); it.mkdirs() }
    val ctx = FakeCtx(root)
    val store = PhotoStore.get(ctx)
    val d = store.dir
    touch(File(d, "a.jpg"), 1000); touch(File(d, "b.png"), 2000)
    touch(File(d, "A/x.jpg"), 3000); touch(File(d, "A/B/y.jpg"), 4000); touch(File(d, "A/B/C/z.jpg"), 5000)
    touch(File(d, "D/w.jpg"), 6000); touch(File(d, ".live/skip.jpg"), 7000); touch(File(d, "notes.txt"), 8000)
    store.touch()

    eq(store.folders(), listOf("A", "A/B", "A/B/C", "D"), "nested folders in tree order (hidden .live skipped)")
    eq(store.list().map { it.name }, listOf("a.jpg", "b.png", "A/x.jpg", "A/B/y.jpg", "A/B/C/z.jpg", "D/w.jpg"), "photo order")
    eq(store.find("A/B/y.jpg")?.folder, "A/B", "folder of nested photo")

    // creating folders: paths are sanitized, traversal is impossible
    eq(store.createFolder("E/F"), "E/F", "create nested")
    eq(store.createFolder("../../evil"), "evil", "traversal dropped")
    check(File(d, "E/F").isDirectory && File(d, "evil").isDirectory) { "folders exist" }
    eq(store.sanitizeFolderPath("a/../b//c"), "a/b/c", "sanitize path")
    eq(store.sanitizeFolderPath("1/2/3/4/5/6/7/8"), "1/2/3/4/5/6", "depth limit")

    // rename, move
    eq(store.renameFolder("A/B", "Q"), "A/Q", "rename last segment")
    check(store.find("A/Q/y.jpg") != null && store.find("A/Q/C/z.jpg") != null) { "photos followed the rename" }
    fails("rename onto existing") { store.renameFolder("A", "D") }
    eq(store.moveFolder("D", "A"), "A/D", "move folder under another")
    fails("move into itself") { store.moveFolder("A", "A/Q") }
    fails("move into own child") { store.moveFolder("A", "A/D") }
    eq(store.moveFolder("A/D", ""), "D", "move back to top level")

    // moving photos
    val moved = store.move(listOf("a.jpg", "A/x.jpg"), "E/F")
    eq(moved, mapOf("a.jpg" to "E/F/a.jpg", "A/x.jpg" to "E/F/x.jpg"), "photos moved")
    touch(File(d, "x.jpg"), 9000); store.touch()
    eq(store.move(listOf("E/F/x.jpg"), "").values.single(), "x (1).jpg", "name clash gets a suffix")

    // playlists
    val pl = Playlists.get(ctx)
    val saved = pl.save(Playlist("", "Trip", listOf("A"), listOf("D/w.jpg"), false, 480, 720))
    store.touch()
    eq(pl.resolve(store.list(), saved).toSet(), setOf("A/Q/y.jpg", "A/Q/C/z.jpg", "D/w.jpg"), "folder includes sub-folders, plus single photos")
    val rootOnly = pl.save(Playlist("", "Loose", listOf(""), emptyList(), false, 0, 0))
    eq(pl.resolve(store.list(), rootOnly).all { '/' !in it }, true, "'' means only photos outside folders")
    eq(pl.resolve(store.list(), null).size, store.list().size, "no playlist = all photos")

    pl.renameFolder("A", "Z")
    eq(pl.find(saved.id)!!.folders, listOf("Z"), "playlist folder renamed")
    pl.renamePhotos(mapOf("D/w.jpg" to "D/w2.jpg"))
    eq(pl.find(saved.id)!!.photos, listOf("D/w2.jpg"), "playlist photo renamed")
    pl.renameFolder("D", "Dd")
    eq(pl.find(saved.id)!!.photos, listOf("Dd/w2.jpg"), "photo path follows a folder rename")
    pl.removeFolder("Z")
    eq(pl.find(saved.id)!!.folders, emptyList(), "deleted folder removed from playlist")

    // deleting folders
    val before = store.list().size
    store.deleteFolder("A", false)
    eq(store.list().size, before - store.list().count { false }, "photos kept when the folder is deleted")
    check(!File(d, "A").exists()) { "folder removed" }
    check(File(d, "y.jpg").exists() && File(d, "z.jpg").exists()) { "nested photos moved to the top level" }
    store.deleteFolder("E", true)
    check(!File(d, "E").exists()) { "folder with photos removed" }
    check(store.find("E/F/a.jpg") == null) { "photos deleted with the folder" }

    // persistence of playlists
    check(File(ctx.filesDir, "playlists.json").exists()) { "playlists.json written" }
    println("storage + playlists tests passed (" + root + ")")
}
