package com.github.jvsena42.loopky.data.anki

import cnames.structs.sqlite3
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import sqlite3.SQLITE_OK
import sqlite3.SQLITE_OPEN_CREATE
import sqlite3.SQLITE_OPEN_READWRITE
import sqlite3.sqlite3_close
import sqlite3.sqlite3_exec
import sqlite3.sqlite3_open_v2
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the SQLite cinterop against a real database on disk.
 *
 * This is the test that was missing when the first real `.apkg` crashed: the connection handle was
 * kept as the `CPointerVar` it was written into, which `memScoped` frees on exit, so every later
 * call read freed memory. Nothing caught it because nothing opened a database — `ZipReader`'s
 * tests never reach SQLite. Any of the cases below would have.
 */
@OptIn(ExperimentalForeignApi::class)
class IosAnkiDbTest {

    private val path = NSTemporaryDirectory() + "loopky-ankidb-test.sqlite"

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(path, null)
    }

    @Test
    fun `reads rows back from a real database`() {
        writeCollection()
        val db = requireNotNull(IosAnkiDb.open(path))
        try {
            val rows = db.query("SELECT flds, tags FROM notes")
            assertEquals(2, rows.size)
            assertEquals("gato", rows[0].text(0))
            assertEquals("animals", rows[0].text(1))
        } finally {
            db.close()
        }
    }

    /**
     * The regression. A handle read after its `memScoped` block has exited is freed memory, and
     * the fault surfaced in `close` — the last call, not the first, which made it look like a
     * shutdown problem rather than a lifetime one.
     */
    @Test
    fun `the handle stays valid across many calls and a close`() {
        writeCollection()
        val db = requireNotNull(IosAnkiDb.open(path))
        repeat(50) { assertEquals(2, db.query("SELECT flds, tags FROM notes").size) }
        db.close()
    }

    @Test
    fun `binds an argument`() {
        writeCollection()
        val db = requireNotNull(IosAnkiDb.open(path))
        try {
            assertEquals(1, db.query("SELECT flds FROM notes WHERE tags = ?", listOf("animals")).size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `an absent table answers empty rather than throwing`() {
        // The shared reader tries both Anki schemas and takes whichever answers, so a missing
        // table is an expected outcome.
        writeCollection()
        val db = requireNotNull(IosAnkiDb.open(path))
        try {
            assertTrue(db.query("SELECT name FROM fields").isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `a path with no database is refused`() {
        NSFileManager.defaultManager.removeItemAtPath(path, null)
        // Opened READONLY, so a missing file is refused rather than created.
        assertNull(IosAnkiDb.open(path))
    }

    @Test
    fun `an integer column reads as an int`() {
        writeCollection()
        val db = requireNotNull(IosAnkiDb.open(path))
        try {
            assertEquals(2, db.query("SELECT COUNT(*) FROM notes").single().int(0))
        } finally {
            db.close()
        }
    }

    /** Builds a small collection with the two columns the shared reader asks `notes` for. */
    private fun writeCollection() = memScoped {
        NSFileManager.defaultManager.removeItemAtPath(path, null)
        val out = alloc<CPointerVar<sqlite3>>()
        val flags = SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE
        check(sqlite3_open_v2(path, out.ptr, flags, null) == SQLITE_OK) { "could not create test db" }
        val handle = requireNotNull(out.value)
        try {
            exec(handle, "CREATE TABLE notes (id INTEGER PRIMARY KEY, flds TEXT, tags TEXT)")
            exec(handle, "INSERT INTO notes VALUES (1, 'gato', 'animals')")
            exec(handle, "INSERT INTO notes VALUES (2, 'casa', 'places')")
        } finally {
            sqlite3_close(handle)
        }
    }

    private fun exec(handle: CPointer<sqlite3>, sql: String) {
        check(sqlite3_exec(handle, sql, null, null, null) == SQLITE_OK) { "failed: $sql" }
    }
}
