import android.content.ContextWrapper
import app.photowall.PhotoStore
import app.photowall.Playlist
import app.photowall.Playlists
import app.photowall.FilmstripStore
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FakeCtx(private val root: File) : ContextWrapper(null) {
    override fun getApplicationContext() = this
    override fun getExternalFilesDir(type: String?) = File(root, "ext/$type").also { it.mkdirs() }
    override fun getFilesDir() = File(root, "files").also { it.mkdirs() }
    override fun getCacheDir() = File(root, "cache").also { it.mkdirs() }
}

fun touch(f: File, ts: Long) { f.parentFile.mkdirs(); f.writeText("x"); f.setLastModified(ts) }
fun <T> eq(actual: T, expected: T, what: String) { check(actual == expected) { "$what: expected $expected but was $actual" } }
inline fun fails(what: String, block: () -> Unit) { try { block() } catch (e: IllegalArgumentException) { return }; error("$what: expected IllegalArgumentException") }


fun makeZip(file: File, entries: List<Pair<String, ByteArray>>) {
    ZipOutputStream(file.outputStream()).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
}

fun filmstripTests(ctx: ContextWrapper, root: File) {
    val fs = FilmstripStore.get(ctx)
    val dims = { _: File -> intArrayOf(1024, 775) }
    val img = ByteArray(2000) { 7 }

    // natural order, nested names, hostile names, junk files
    val z1 = File(root, "a.zip")
    makeZip(z1, listOf(
        "10.jpg" to img, "2.jpg" to img, "1.jpg" to img, "sub/3.png" to img,
        "../evil.jpg" to img, "__MACOSX/1.jpg" to img, ".hidden.jpg" to img, "notes.txt" to img,
        "ComicInfo.xml" to "<ComicInfo><Title>Unknown</Title><Writer>A &amp; B</Writer><Year>1952</Year></ComicInfo>".toByteArray(),
    ))
    val f1 = fs.import(z1, "film_8126.cbz", dims)
    eq(f1.title, "Диафильм 8126", "Title Unknown falls back to the file name")
    eq(f1.frames, listOf("0001.jpg", "0002.jpg", "0003.jpg", "0004.jpg", "0005.png"), "frames renamed 0001..; archive order ../evil, 1, 2, 10, sub/3")
    check(!File(root, "ext/filmstrips/evil.jpg").exists() && !File(root, "evil.jpg").exists() && !File(root, "ext/evil.jpg").exists()) { "zip-slip name did not escape" }
    eq(f1.credits, "Writer: A & B; Year: 1952", "credits from ComicInfo")
    eq(listOf(f1.width, f1.height), listOf(1024, 775), "size of the first frame")
    eq(fs.frameFile(f1.id, 0)!!.length(), 2000L, "frame file written")
    eq(fs.frameFile(f1.id, 99), null, "no such frame")
    eq(fs.get("../x"), null, "ids are validated")

    // title from ComicInfo / info.json, rename, delete
    val z2 = File(root, "b.zip")
    makeZip(z2, listOf("001.jpg" to img, "ComicInfo.xml" to "<ComicInfo><Title>Теремок</Title></ComicInfo>".toByteArray()))
    val f2 = fs.import(z2, "x.cbz", dims)
    eq(f2.title, "Теремок", "Title from ComicInfo")
    val z3 = File(root, "c.zip")
    makeZip(z3, listOf("001.webp" to img, "info.json" to """{"title":"Из Stripa","author":"Unknown"}""".toByteArray()))
    eq(fs.import(z3, "y.cbz", dims).title, "Из Stripa", "Title from info.json")
    check(fs.rename(f2.id, "  Новое имя \u0007") && fs.get(f2.id)!!.title == "Новое имя") { "rename cleans control characters" }
    check(!fs.rename(f2.id, "   ")) { "empty title rejected" }
    eq(fs.list().size, 3, "three filmstrips saved")
    check(fs.delete(f2.id) && fs.get(f2.id) == null && !fs.delete(f2.id)) { "delete" }

    // limits and garbage
    fails("not a zip") { File(root, "bad.zip").also { it.writeText("hello") }.let { fs.import(it, "bad.zip", dims) } }
    fails("no images") { makeZip(File(root, "e.zip"), listOf("a.txt" to img)); fs.import(File(root, "e.zip"), "e.zip", dims) }
    fails("too many files") { makeZip(File(root, "m.zip"), (0..FilmstripStore.MAX_ENTRIES).map { "$it.jpg" to ByteArray(1) }); fs.import(File(root, "m.zip"), "m.zip", dims) }
    fails("entry too large") { makeZip(File(root, "big.zip"), listOf("1.jpg" to ByteArray(41 * 1024 * 1024))); fs.import(File(root, "big.zip"), "big.zip", dims) }
    eq(fs.list().size, 2, "failed imports leave nothing behind")
    eq(File(root, "ext/filmstrips").listFiles()!!.count { it.isDirectory }, 2, "no leftover folders")
    eq(FilmstripStore.naturalCompare("2.jpg", "10.jpg") < 0, true, "natural order 2 < 10")

    // the real archive from the owner, if provided: FILM_CBZ=/path/film_8126.cbz
    System.getenv("FILM_CBZ")?.let { path ->
        val f = fs.import(File(path), File(path).name, dims)
        println("real archive: '" + f.title + "', " + f.frames.size + " frames, " + f.bytes / 1024 + " KB, credits='" + f.credits + "'")
        eq(f.frames.size > 0, true, "real archive imported")
    }
    println("filmstrip tests passed")
}

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
    filmstripTests(ctx, root)
    println("storage + playlists tests passed (" + root + ")")
}
