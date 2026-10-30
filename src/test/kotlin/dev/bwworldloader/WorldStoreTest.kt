package dev.bwworldloader

import dev.bwworldloader.config.SaveFormat
import dev.bwworldloader.config.StorageSettings
import dev.bwworldloader.storage.FileOps
import dev.bwworldloader.storage.WorldStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.logging.Logger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldStoreTest {

    private lateinit var root: File
    private val log = Logger.getLogger("test")

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("bwloader-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        FileOps.deleteRecursively(root.toPath())
    }

    private fun store(
        path: String = "bedwars_worlds",
        format: SaveFormat = SaveFormat.FOLDER,
        import: Boolean = true,
    ) = WorldStore(root, log, StorageSettings(path = path, formatOnSave = format, importFromRoot = import, indexRefreshSeconds = 0))
        .also { it.rebind(StorageSettings(path = path, formatOnSave = format, importFromRoot = import, indexRefreshSeconds = 0)) }

    private fun makeWorld(dir: File, marker: String = "region-data") {
        File(dir, "region").mkdirs()
        File(dir, "region/r.0.0.mca").writeText(marker)
        File(dir, "level.dat").writeText("level")
        File(dir, "session.lock").writeText("lock")
        File(dir, "uid.dat").writeText("uid")
    }

    private fun zip(target: File, entries: Map<String, String>) {
        ZipOutputStream(target.outputStream()).use { z ->
            for ((name, body) in entries) {
                z.putNextEntry(ZipEntry(name))
                if (!name.endsWith("/")) z.write(body.toByteArray())
                z.closeEntry()
            }
        }
    }

    @Test
    fun `storage folder is created and relative paths resolve against the server root`() {
        val s = store("maps/bw")
        assertTrue(File(root, "maps/bw").isDirectory)
        assertEquals(File(root, "maps/bw").toPath().toAbsolutePath().normalize(), s.storageDir)
    }

    @Test
    fun `absolute storage path is honored`() {
        val other = Files.createTempDirectory("bwloader-abs").toFile()
        try {
            val s = store(other.absolutePath)
            makeWorld(File(other, "alpha"))
            s.rescan()
            assertNotNull(s.find("alpha"))
        } finally {
            FileOps.deleteRecursively(other.toPath())
        }
    }

    @Test
    fun `index finds folder and zip templates case-insensitively`() {
        val s = store()
        makeWorld(File(root, "bedwars_worlds/Alpha"))
        zip(File(root, "bedwars_worlds/Beta.zip"), mapOf("region/r.0.0.mca" to "x", "level.dat" to "l"))
        File(root, "bedwars_worlds/notaworld").mkdirs()
        File(root, "bedwars_worlds/.hidden").mkdirs()
        s.rescan()
        assertEquals(WorldStore.Kind.FOLDER, s.find("alpha")?.kind)
        assertEquals(WorldStore.Kind.ZIP, s.find("BETA")?.kind)
        assertNull(s.find("notaworld"))
        assertEquals(listOf("Alpha", "Beta"), s.names())
        assertTrue(s.exists("alpha"))
        assertFalse(s.exists("gamma"))
    }

    @Test
    fun `a map dropped in after the last scan is found on a miss`() {
        val s = store()
        assertNull(s.find("late"))
        Thread.sleep(2100)
        makeWorld(File(root, "bedwars_worlds/late"))
        assertNotNull(s.find("late"))
    }

    @Test
    fun `materialize copies a folder template, skips trash and drops a marker`() {
        val s = store()
        makeWorld(File(root, "bedwars_worlds/alpha"), "R1")
        s.rescan()
        val res = assertIs<WorldStore.Materialized.Ready>(s.materialize("alpha", "alpha", emptySet(), deleteLevelDat = true))
        assertEquals("R1", File(res.worldDir, "region/r.0.0.mca").readText())
        assertFalse(File(res.worldDir, "level.dat").exists())
        assertFalse(File(res.worldDir, "session.lock").exists())
        assertFalse(File(res.worldDir, "uid.dat").exists())
        assertTrue(File(res.worldDir, FileOps.MARKER).exists())
        // the template itself stays untouched
        assertTrue(File(root, "bedwars_worlds/alpha/level.dat").exists())
    }

    @Test
    fun `materialize can keep level dat`() {
        val s = store()
        makeWorld(File(root, "bedwars_worlds/alpha"))
        s.rescan()
        val res = assertIs<WorldStore.Materialized.Ready>(s.materialize("alpha", "bw_temp_1", emptySet(), deleteLevelDat = false))
        assertTrue(File(res.worldDir, "level.dat").exists())
        assertEquals("bw_temp_1", res.worldDir.name)
    }

    @Test
    fun `materialize replaces a stale runtime copy`() {
        val s = store()
        makeWorld(File(root, "bedwars_worlds/alpha"), "fresh")
        File(root, "alpha/region").mkdirs()
        File(root, "alpha/region/old.mca").writeText("stale")
        File(root, "alpha/${FileOps.MARKER}").writeText("1") // a leftover copy made by this plugin
        s.rescan()
        // the leftover copy is disposable, so the template replaces it
        val res = assertIs<WorldStore.Materialized.Ready>(s.materialize("alpha", "alpha", emptySet(), true))
        assertFalse(File(res.worldDir, "region/old.mca").exists())
        assertEquals("fresh", File(res.worldDir, "region/r.0.0.mca").readText())
    }

    @Test
    fun `materialize extracts a zip template and strips a wrapping folder`() {
        val s = store()
        zip(
            File(root, "bedwars_worlds/wrapped.zip"),
            mapOf("wrapped/" to "", "wrapped/region/r.0.0.mca" to "Z", "wrapped/level.dat" to "l", "__MACOSX/x" to "junk"),
        )
        s.rescan()
        val res = assertIs<WorldStore.Materialized.Ready>(s.materialize("wrapped", "wrapped", emptySet(), true))
        assertEquals("Z", File(res.worldDir, "region/r.0.0.mca").readText())
        assertFalse(File(res.worldDir, "wrapped").exists())
        assertFalse(File(res.worldDir, "__MACOSX").exists())
    }

    @Test
    fun `zip slip entries are rejected`() {
        val s = store()
        zip(File(root, "bedwars_worlds/evil.zip"), mapOf("region/r.0.0.mca" to "x", "../../escaped.txt" to "boom"))
        s.rescan()
        val res = s.materialize("evil", "evil", emptySet(), true)
        assertIs<WorldStore.Materialized.Failed>(res)
        assertFalse(File(root.parentFile, "escaped.txt").exists())
        assertFalse(File(root, "../escaped.txt").exists())
    }

    @Test
    fun `missing map is reported`() {
        val s = store()
        assertIs<WorldStore.Materialized.Missing>(s.materialize("nope", "nope", emptySet(), true))
    }

    @Test
    fun `protected worlds are never overwritten`() {
        val s = store()
        makeWorld(File(root, "bedwars_worlds/world"))
        s.rescan()
        assertIs<WorldStore.Materialized.Failed>(s.materialize("world", "world", setOf("world"), true))
    }

    @Test
    fun `path traversal names are rejected`() {
        val s = store()
        assertIs<WorldStore.Materialized.Failed>(s.materialize("../x", "x", emptySet(), true))
        assertIs<WorldStore.Materialized.Failed>(s.materialize("x", "../y", emptySet(), true))
        assertNull(WorldStore.safeName("a/b"))
        assertNull(WorldStore.safeName(".."))
        assertEquals("ok-name_1", WorldStore.safeName(" ok-name_1 "))
    }

    @Test
    fun `legacy root maps are imported on first use and removed from the root`() {
        val s = store()
        makeWorld(File(root, "oldmap"), "legacy")
        assertTrue(s.exists("oldmap"))
        val res = assertIs<WorldStore.Materialized.Ready>(s.materialize("oldmap", "oldmap", emptySet(), true))
        assertTrue(res.importedFromRoot)
        assertEquals("legacy", File(root, "bedwars_worlds/oldmap/region/r.0.0.mca").readText())
        assertEquals("legacy", File(res.worldDir, "region/r.0.0.mca").readText())
    }

    @Test
    fun `legacy import can be disabled`() {
        val s = store(import = false)
        makeWorld(File(root, "oldmap"))
        assertFalse(s.exists("oldmap"))
        assertIs<WorldStore.Materialized.Missing>(s.materialize("oldmap", "oldmap", emptySet(), true))
    }

    @Test
    fun `saveFromRuntime stores a folder template and replaces the old one`() {
        val s = store()
        makeWorld(File(root, "bedwars_worlds/alpha"), "old")
        s.rescan()
        makeWorld(File(root, "runtime_alpha"), "new")
        File(root, "runtime_alpha/${FileOps.MARKER}").writeText("m")
        val t = s.saveFromRuntime("runtime_alpha", "alpha")
        assertEquals(WorldStore.Kind.FOLDER, t.kind)
        assertEquals("new", File(root, "bedwars_worlds/alpha/region/r.0.0.mca").readText())
        assertFalse(File(root, "bedwars_worlds/alpha/session.lock").exists())
        assertFalse(File(root, "bedwars_worlds/alpha/${FileOps.MARKER}").exists())
        assertTrue(File(root, "bedwars_worlds/alpha/level.dat").exists())
    }

    @Test
    fun `saveFromRuntime as zip round-trips through materialize`() {
        val s = store(format = SaveFormat.ZIP)
        makeWorld(File(root, "runtime_beta"), "zipped")
        val t = s.saveFromRuntime("runtime_beta", "beta")
        assertEquals(WorldStore.Kind.ZIP, t.kind)
        assertTrue(File(root, "bedwars_worlds/beta.zip").isFile)
        val res = assertIs<WorldStore.Materialized.Ready>(s.materialize("beta", "beta_run", emptySet(), true))
        assertEquals("zipped", File(res.worldDir, "region/r.0.0.mca").readText())
    }

    @Test
    fun `clone and delete work on the library`() {
        val s = store()
        makeWorld(File(root, "bedwars_worlds/alpha"), "A")
        s.rescan()
        s.clone("alpha", "alpha2")
        assertEquals("A", File(root, "bedwars_worlds/alpha2/region/r.0.0.mca").readText())
        assertTrue(s.delete("alpha"))
        assertFalse(s.exists("alpha"))
        assertTrue(s.exists("alpha2"))
        assertFalse(s.delete("alpha"))
        assertFailsWith<IOException> { s.clone("ghost", "g2") }
    }

    @Test
    fun `exportAsZip normalises both template kinds`() {
        val s = store()
        makeWorld(File(root, "bedwars_worlds/alpha"), "A")
        zip(File(root, "bedwars_worlds/wrapped.zip"), mapOf("wrapped/region/r.0.0.mca" to "W", "wrapped/level.dat" to "l"))
        s.rescan()
        val a = File(root, "out/alpha.zip")
        val w = File(root, "out/wrapped.zip")
        assertTrue(s.exportAsZip("alpha", a.toPath()))
        assertTrue(s.exportAsZip("wrapped", w.toPath()))
        assertFalse(s.exportAsZip("missing", File(root, "out/m.zip").toPath()))
        // they must extract to a world root: this is what BedWars1058's own adapter does with them
        for ((zipFile, expected) in listOf(a to "A", w to "W")) {
            val dir = File(root, "x_${zipFile.nameWithoutExtension}")
            FileOps.unzip(zipFile.toPath(), dir.toPath())
            assertEquals(expected, File(dir, "region/r.0.0.mca").readText())
        }
    }

    @Test
    fun `world folder detection accepts old and new layouts`() {
        val a = File(root, "a").also { File(it, "region").mkdirs() }
        val b = File(root, "b").also { it.mkdirs(); File(it, "level.dat").writeText("x") }
        val c = File(root, "c").also { File(it, "dimensions").mkdirs() }
        val d = File(root, "d").also { it.mkdirs() }
        assertTrue(FileOps.isWorldFolder(a))
        assertTrue(FileOps.isWorldFolder(b))
        assertTrue(FileOps.isWorldFolder(c))
        assertFalse(FileOps.isWorldFolder(d))
    }

    // ------------------------------------------------------------------------------------------
    // /bw setupArena safety
    // ------------------------------------------------------------------------------------------

    private fun storeKeeping(keep: Int, format: SaveFormat = SaveFormat.FOLDER): WorldStore {
        val st = StorageSettings(path = "lib", formatOnSave = format, backupsToKeep = keep, indexRefreshSeconds = 0)
        return WorldStore(root, log, st).also { it.rebind(st) }
    }

    @Test
    fun `saving a setup world replaces the map and keeps the old version as a backup`() {
        val s = storeKeeping(3)
        makeWorld(File(root, "lib/alpha"), "v1")
        s.rescan()
        makeWorld(File(root, "alpha"), "v2")
        s.saveFromRuntime("alpha", "alpha")
        assertEquals("v2", File(root, "lib/alpha/region/r.0.0.mca").readText())
        val backups = s.backupsOf("alpha")
        assertEquals(1, backups.size)
        assertEquals("v1", File(root, "lib/.backups/alpha/${backups[0]}/region/r.0.0.mca").readText())
        assertFalse(File(root, "lib/.alpha.old").exists())
        assertFalse(File(root, "lib/.alpha.saving").exists())
        // backups are not mistaken for maps
        assertEquals(listOf("alpha"), s.names())
    }

    @Test
    fun `only the newest backups are kept`() {
        val s = storeKeeping(2)
        makeWorld(File(root, "lib/alpha"), "v0")
        s.rescan()
        for (i in 1..4) {
            File(root, "alpha").let { FileOps.deleteRecursively(it.toPath()) }
            makeWorld(File(root, "alpha"), "v$i")
            s.saveFromRuntime("alpha", "alpha")
            Thread.sleep(5)
        }
        assertEquals(2, s.backupsOf("alpha").size)
        assertEquals("v4", File(root, "lib/alpha/region/r.0.0.mca").readText())
    }

    @Test
    fun `a failed save leaves the stored map untouched`() {
        val s = storeKeeping(3)
        makeWorld(File(root, "lib/alpha"), "precious")
        s.rescan()
        // the runtime folder is gone (e.g. a failed start): the save must refuse and not harm the library
        assertFailsWith<IllegalArgumentException> { s.saveFromRuntime("alpha", "alpha") }
        assertEquals("precious", File(root, "lib/alpha/region/r.0.0.mca").readText())
        // a world folder that is not a world at all is refused as well
        File(root, "alpha").mkdirs()
        File(root, "alpha/junk.txt").writeText("x")
        assertFailsWith<IllegalArgumentException> { s.saveFromRuntime("alpha", "alpha") }
        assertEquals("precious", File(root, "lib/alpha/region/r.0.0.mca").readText())
    }

    @Test
    fun `zip format also keeps a backup and swaps atomically`() {
        val s = storeKeeping(3, SaveFormat.ZIP)
        zip(File(root, "lib").also { it.mkdirs() }.resolve("alpha.zip"), mapOf("region/r.0.0.mca" to "old", "level.dat" to "l"))
        s.rescan()
        makeWorld(File(root, "alpha"), "new")
        s.saveFromRuntime("alpha", "alpha")
        assertEquals(1, s.backupsOf("alpha").size)
        assertTrue(s.backupsOf("alpha")[0].endsWith(".zip"))
        val out = File(root, "x").also { it.mkdirs() }
        FileOps.unzip(File(root, "lib/alpha.zip").toPath(), out.toPath())
        assertEquals("new", File(out, "region/r.0.0.mca").readText())
        assertFalse(File(root, "lib/alpha.zip.tmp").exists())
    }

    @Test
    fun `with backups disabled the old version is simply replaced, but force-backup still keeps one`() {
        val s = storeKeeping(0)
        makeWorld(File(root, "lib/alpha"), "v1")
        s.rescan()
        makeWorld(File(root, "alpha"), "v2")
        s.saveFromRuntime("alpha", "alpha")
        assertTrue(s.backupsOf("alpha").isEmpty())
        FileOps.deleteRecursively(File(root, "alpha").toPath())
        makeWorld(File(root, "alpha"), "v3")
        s.saveFromRuntime("alpha", "alpha", forceBackup = true)
        assertEquals(1, s.backupsOf("alpha").size)
    }

    @Test
    fun `setup markers never end up in the library`() {
        val s = storeKeeping(3)
        makeWorld(File(root, "alpha"), "edit")
        File(root, "alpha/${FileOps.SETUP_MARKER}").writeText("1")
        s.saveFromRuntime("alpha", "alpha")
        assertFalse(File(root, "lib/alpha/${FileOps.SETUP_MARKER}").exists())
        assertTrue(File(root, "lib/alpha/region/r.0.0.mca").exists())
    }

    @Test
    fun `materialize refuses to overwrite unsaved setup work or a foreign world`() {
        val s = storeKeeping(3)
        makeWorld(File(root, "lib/alpha"), "stored")
        s.rescan()
        // unsaved setup work from a crash
        makeWorld(File(root, "alpha"), "unsaved")
        File(root, "alpha/${FileOps.SETUP_MARKER}").writeText("1")
        val r1 = s.materialize("alpha", "alpha", emptySet(), true)
        val failed = assertIs<WorldStore.Materialized.Failed>(r1)
        assertIs<dev.bwworldloader.storage.UnsafeTargetException>(failed.error)
        assertEquals("unsaved", File(root, "alpha/region/r.0.0.mca").readText())
        // an unrelated world of the admin with the same name (no marker)
        FileOps.deleteRecursively(File(root, "alpha").toPath())
        makeWorld(File(root, "alpha"), "admins-own-world")
        val r2 = s.materialize("alpha", "alpha", emptySet(), true)
        assertIs<dev.bwworldloader.storage.UnsafeTargetException>(assertIs<WorldStore.Materialized.Failed>(r2).error)
        assertEquals("admins-own-world", File(root, "alpha/region/r.0.0.mca").readText())
        // a disposable runtime copy is replaced as before
        FileOps.deleteRecursively(File(root, "alpha").toPath())
        makeWorld(File(root, "alpha"), "stale")
        File(root, "alpha/${FileOps.MARKER}").writeText("1")
        assertIs<WorldStore.Materialized.Ready>(s.materialize("alpha", "alpha", emptySet(), true))
        assertEquals("stored", File(root, "alpha/region/r.0.0.mca").readText())
    }

    @Test
    fun `a setup copy is marked with the setup marker and keeps level dat`() {
        val s = storeKeeping(3)
        makeWorld(File(root, "lib/alpha"), "stored")
        s.rescan()
        val res = assertIs<WorldStore.Materialized.Ready>(s.materialize("alpha", "alpha", emptySet(), deleteLevelDat = false, setup = true))
        assertTrue(File(res.worldDir, FileOps.SETUP_MARKER).exists())
        assertFalse(File(res.worldDir, FileOps.MARKER).exists())
        assertTrue(File(res.worldDir, "level.dat").exists())
    }

    @Test
    fun `pending save is tracked`() {
        val s = storeKeeping(3)
        assertFalse(s.hasPendingSave("Alpha"))
        s.beginPendingSave("alpha")
        assertTrue(s.hasPendingSave("ALPHA"))
        s.endPendingSave("Alpha")
        assertFalse(s.hasPendingSave("alpha"))
    }

    @Test
    fun `deleting a map archives it when backups are on`() {
        val s = storeKeeping(2)
        makeWorld(File(root, "lib/alpha"), "v1")
        s.rescan()
        assertTrue(s.delete("alpha"))
        assertNull(s.find("alpha"))
        assertEquals(1, s.backupsOf("alpha").size)
    }

    @Test
    fun `restore puts a clean world back into the root and never overwrites`() {
        val s = storeKeeping(2)
        makeWorld(File(root, "lib/alpha"), "stored")
        File(root, "lib/alpha/${FileOps.MARKER}").writeText("x")
        s.rescan()
        val dir = s.restoreToRoot("alpha", move = false)
        assertEquals("stored", File(dir, "region/r.0.0.mca").readText())
        assertTrue(File(dir, "level.dat").exists())
        assertFalse(File(dir, FileOps.MARKER).exists())
        assertNotNull(s.find("alpha")) // copy mode keeps the library entry
        assertFailsWith<IOException> { s.restoreToRoot("alpha", move = false) }
        assertEquals("stored", File(dir, "region/r.0.0.mca").readText())
        assertFailsWith<IOException> { s.restoreToRoot("ghost", move = false) }
    }

    @Test
    fun `restore with move removes the map from the library`() {
        val s = storeKeeping(0)
        zip(File(root, "lib").also { it.mkdirs() }.resolve("beta.zip"), mapOf("region/r.0.0.mca" to "z", "level.dat" to "l"))
        s.rescan()
        s.restoreToRoot("beta", move = true)
        assertEquals("z", File(root, "beta/region/r.0.0.mca").readText())
        assertNull(s.find("beta"))
        assertFalse(File(root, ".beta.restoring").exists())
    }
}
